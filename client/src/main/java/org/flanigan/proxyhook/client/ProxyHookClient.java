/*
 * Copyright 2017, Red Hat, Inc. and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.flanigan.proxyhook.client;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.flanigan.proxyhook.common.AbstractProxyHook;
import org.flanigan.proxyhook.common.MessageType;
import io.vertx.core.AsyncResult;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.WebSocketFrame;
import io.vertx.core.http.impl.FrameType;
import io.vertx.core.http.impl.ws.WebSocketFrameImpl;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;

import static java.lang.System.getenv;
import static java.util.Arrays.asList;
import static java.util.Collections.unmodifiableList;
import static java.util.stream.Collectors.collectingAndThen;
import static java.util.stream.Collectors.toSet;
import static org.flanigan.proxyhook.common.Constants.MAX_FRAME_SIZE;
import static org.flanigan.proxyhook.common.Constants.PATH_WEBSOCKET;
import static org.flanigan.proxyhook.common.Constants.PROXYHOOK_PASSWORD;
import static org.flanigan.proxyhook.common.JsonUtil.jsonToMultiMap;
import static org.flanigan.proxyhook.common.JsonUtil.multiMapToJson;
import static org.flanigan.proxyhook.common.Keys.BUFFER;
import static org.flanigan.proxyhook.common.Keys.BUFFER_TEXT;
import static org.flanigan.proxyhook.common.Keys.HEADERS;
import static org.flanigan.proxyhook.common.Keys.PASSWORD;
import static org.flanigan.proxyhook.common.Keys.PING_ID;
import static org.flanigan.proxyhook.common.Keys.TYPE;
import static org.flanigan.proxyhook.common.MessageType.LOGIN;
import static org.flanigan.proxyhook.common.MessageType.PONG;

/**
 * @author Sean Flanigan <a href="mailto:sflaniga@redhat.com">sflaniga@redhat.com</a>
 */
public class ProxyHookClient extends AbstractProxyHook {
    private static final String APP_NAME = ProxyHookClient.class.getName();
    private static final Logger log = LoggerFactory.getLogger(ProxyHookClient.class);
    // Request header references:
    // https://docs.gitlab.com/ce/user/project/integrations/webhooks.html
    // https://gitlab.com/gitlab-org/gitlab-ce/blob/v9.1.2/app/models/hooks/web_hook.rb#L60
    // https://developer.github.com/webhooks/#delivery-headers
    // https://en.wikipedia.org/wiki/List_of_HTTP_header_fields#Request_fields
    private static final Set<String> HEADERS_TO_COPY = Stream.of(
            "Accept",
            "Accept-Charset",
            "Accept-Datetime",
            "Accept-Encoding",
            "Accept-Language",
            "Authorization",
            "Content-Length",
            "Content-MD5",
            "Content-Type",
            "Cookie",
            "Date",
            "Expect",
            "Forwarded",
            "From",
            "Front-End-Https",
            "Max-Forwards",
            "Pragma",
            "Referer", // sic
            "TE",
            "User-Agent",
            "Via",
            "Warning",
            "X-Client-Ip",
            "X-Correlation-ID",
            "X-Forwarded-For",
            "X-Forwarded-Host",
            "X-Forwarded-Proto",
            "X-Forwarded-Server",
            "X-Gitlab-Event",
            "X-Gitlab-Token",
            "X-GitHub-Delivery",
            "X-GitHub-Event",
            "X-HTTP-Method-Override",
            "X-Hub-Signature",
            "X-Request-Id")
            .map(String::toLowerCase)
            .collect(collectingAndThen(toSet(), Collections::unmodifiableSet));

    // deliberately not included: Connection, Host, Origin, If-*, Cache-Control, Proxy-Authorization, Range, Upgrade
    private static List<String> processArgs;

    public static void main(String[] args) throws Throwable {
        processArgs = unmodifiableList(asList(args));
        BlockingQueue<AsyncResult<String>> q = new ArrayBlockingQueue<>(1);
        Vertx.vertx().deployVerticle(new ProxyHookClient(), q::offer);
        AsyncResult<String> deploymentResult = q.take();
        if (deploymentResult.failed()) throw deploymentResult.cause();
    }

    // TODO use http://vertx.io/docs/vertx-core/java/#_vert_x_command_line_interface_api
    // not this mess
    private List<String> getArgs() {
        if (processArgs != null) return processArgs;
        // command line is of the pattern "vertx run [options] main-verticle"
        // so strip off everything up to the Verticle class name
        List<String> processArgs = vertx.getOrCreateContext().processArgs();
        log.debug("processArgs: " + processArgs);
        int n = processArgs.indexOf(getClass().getName());
        List<String> argsAfterClass = processArgs.subList(n + 1, processArgs.size());
        List<String> result = argsAfterClass.stream().filter(arg -> !arg.startsWith("-")).collect(
                Collectors.toList());
        log.debug("args: " + result);
        return result;
    }

    @Override
    public void start() throws Exception {
        // this /shouldn't/ be needed for deployment failures
//        vertx.exceptionHandler(this::die);
        List<String> args = getArgs();
        if (args.size() < 2) {
            die("Usage: wss://proxyhook.example.com/" + PATH_WEBSOCKET + " http://target1.example.com/webhook [http://target2.example.com/webhook ...]");
        }
        startClient(args);
    }

    private void startClient(List<String> urls) {
        assert (urls.size() >= 2);
        String webSocketAbsoluteUri = urls.get(0);
        List<String> webhookUrls = urls.subList(1, urls.size());
        log.info("starting client for websocket: " + webSocketAbsoluteUri + " posting to webhook URLs: " + webhookUrls);

        webhookUrls.forEach(this::checkURI);

        URI wsUri = parseUri(webSocketAbsoluteUri);
        String webSocketRelativeUri = getRelativeUri(wsUri);
        boolean useSSL = getSSL(wsUri);
        HttpClientOptions wsOptions = new HttpClientOptions()
                // 60s timeout based on pings from every 50s (both directions)
                .setIdleTimeout(60)
                .setConnectTimeout(10_000)
                .setDefaultHost(wsUri.getHost())
                .setDefaultPort(getWebsocketPort(wsUri))
                .setMaxWebsocketFrameSize(MAX_FRAME_SIZE)
                .setSsl(useSSL)
                ;
        HttpClient wsClient = vertx.createHttpClient(wsOptions);
        HttpClient httpClient = vertx.createHttpClient();

        connect(webhookUrls, webSocketRelativeUri, wsClient, httpClient);
    }

    private void connect(List<String> webhookUrls,
            String webSocketRelativeUri, HttpClient wsClient,
            HttpClient httpClient) {
        wsClient.websocket(webSocketRelativeUri, webSocket -> {
            String password = getenv(PROXYHOOK_PASSWORD);
            if (password == null) password = "";
            log.info("trying to log in");
            JsonObject login = new JsonObject();
            login.put(TYPE, LOGIN);
            login.put(PASSWORD, password);
            webSocket.writeTextMessage(login.encode());

            // tries to reconnect in case:
            // - server is restarted
            // - server is still starting
            // - connection breaks because of transient network error
            // TODO OR just die, so that (eg) systemd can restart the process

            long periodicTimer = vertx.setPeriodic(50_000, timerId -> {
                // ping frame triggers pong frame (inside vert.x), closes websocket if no data received before idleTimeout in TCPSSLOptions):
                // TODO avoid importing from internal vertx package
                WebSocketFrame frame = new WebSocketFrameImpl(FrameType.PING, io.netty.buffer.Unpooled.copyLong(System.currentTimeMillis()));
                webSocket.writeFrame(frame);

                // this doesn't work with a simple idle timeout, because sending the PING is considered write activity
//                JsonObject object = new JsonObject();
//                object.put(TYPE, PING);
//                object.put(PING_ID, String.valueOf(System.currentTimeMillis()));
//                webSocket.writeTextMessage(object.encode());
            });
            webSocket.handler((Buffer buf) -> {
                JsonObject msg = buf.toJsonObject();
                log.debug("payload: {0}", msg);

                String type = msg.getString(TYPE);
                MessageType messageType = MessageType.valueOf(type);
                switch (messageType) {
                    case SUCCESS:
                        log.info("logged in");
                        break;
                    case FAILED:
                        webSocket.close();
                        wsClient.close();
                        die("login failed");
                        break;
                    case WEBHOOK:
                        handleWebhook(webhookUrls, httpClient, msg);
                        break;
                    case PING:
                        String pingId = msg.getString(PING_ID);
                        log.debug("received PING with id {}", pingId);
                        JsonObject pong = new JsonObject();
                        pong.put(TYPE, PONG);
                        pong.put(PING_ID, pingId);
                        webSocket.writeTextMessage(pong.encode());
                        break;
                    case PONG:
                        String pongId = msg.getString(PING_ID);
                        // TODO check ping ID
                        log.debug("received PONG with id {}", pongId);
                        break;
                    default:
                        // TODO this might happen if the server is newer than the client
                        // should we log a warning and keep going, to be more robust?
                        webSocket.close();
                        wsClient.close();
                        die("unexpected message type: " + type);
                        break;
                }
            });
            webSocket.closeHandler(v -> {
                log.info("websocket closed");
                vertx.cancelTimer(periodicTimer);
                vertx.setTimer(300, timer -> connect(webhookUrls,
                        webSocketRelativeUri, wsClient, httpClient));
            });
            webSocket.exceptionHandler(e -> {
                log.error("websocket stream exception", e);
                vertx.cancelTimer(periodicTimer);
                vertx.setTimer(2_000, timer -> connect(webhookUrls,
                        webSocketRelativeUri, wsClient, httpClient));
            });
        }, e -> {
            log.error("websocket connection exception", e);
            vertx.setTimer(2_000, timer -> connect(webhookUrls,
                    webSocketRelativeUri, wsClient, httpClient));
        });
    }

    private void checkURI(String uri) {
        String hostname = parseUri(uri).getHost();
        try {
            @SuppressWarnings("unused")
            InetAddress address = InetAddress.getByName(hostname);
        } catch (UnknownHostException e) {
            die("Unable to resolve URI "+uri+": " + e.getMessage());
        }
    }

    private void handleWebhook(List<String> webhookUrls, HttpClient client, JsonObject msg) {
        log.info("Webhook received");

        // TODO use host, path and/or query from webSocket message?
//                String path = msg.getString("path");
//                String query = msg.getString("query");
        JsonArray headerPairs = msg.getJsonArray(HEADERS);
        MultiMap headers = jsonToMultiMap(headerPairs);

        for (String header : EVENT_ID_HEADERS) {
            if (headers.contains(header)) {
                log.info("Webhook header {0}: {1}", header, headers.getAll(header));
            }
        }

        String bufferText = msg.getString(BUFFER_TEXT);
        byte[] buffer = msg.getBinary(BUFFER);
//                log.debug("buffer: "+ Arrays.toString(buffer));

        for (String webhookUri: webhookUrls) {
            HttpClientRequest request = client.postAbs(webhookUri, response -> {
                log.info("Webhook POSTed to URL: " + webhookUri);
                log.info("Webhook POST response status: " + response.statusCode() + " " + response.statusMessage());
                log.info("Webhook POST response headers: " + multiMapToJson(response.headers()));
            });
//                request.putHeader("content-type", "text/plain")
            // some headers break things (eg Host), so we use a whitelist
//            request.headers().addAll(headers);
            copyWebhookHeaders(headers, request.headers());
            if (bufferText != null) {
                request.end(Buffer.buffer(bufferText));
            } else {
                request.end(Buffer.buffer(buffer));
            }
        }
    }

    private void copyWebhookHeaders(MultiMap fromHeaders, MultiMap toHeaders) {
        for (String header : fromHeaders.names()) {
            if (HEADERS_TO_COPY.contains(header.toLowerCase())) {
                toHeaders.set(header, fromHeaders.getAll(header));
            }
        }
    }

    private String getRelativeUri(URI uri) {
        return uri.getRawPath() +
                (uri.getQuery() == null ? "" : "?" + uri.getQuery());
    }

    private boolean getSSL(URI wsUri) {
        String protocol = wsUri.getScheme();
        switch (protocol) {
            case "ws":
                return false;
            case "wss":
                return true;
            default:
                return die("expected URI with ws: or wss: : " + wsUri);
        }
    }

    private int getWebsocketPort(URI wsUri) {
        int port = wsUri.getPort();
        if (port == -1) {
            String protocol = wsUri.getScheme();
            switch (protocol) {
                case "ws":
                    return 80;
                case "wss":
                    return 443;
                default:
                    return die("expected URI with ws: or wss: : " + wsUri);
            }
        }
        return port;
    }

    private URI parseUri(String uri) {
        try {
            return new URI(uri);
        } catch (URISyntaxException e) {
            return die("Invalid URI: " + uri);
        }
    }

}
