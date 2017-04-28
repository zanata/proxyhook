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

import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;
import java.util.stream.Collectors;

import org.flanigan.proxyhook.common.AbstractProxyHook;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;

import static java.lang.System.getenv;
import static org.flanigan.proxyhook.common.Constants.MAX_FRAME_SIZE;
import static org.flanigan.proxyhook.common.Constants.PROXYHOOK_PASSWORD;
import static org.flanigan.proxyhook.common.JsonUtil.jsonToMultiMap;
import static org.flanigan.proxyhook.common.JsonUtil.multiMapToJson;

/**
 * @author Sean Flanigan <a href="mailto:sflaniga@redhat.com">sflaniga@redhat.com</a>
 */
public class ProxyHookClient extends AbstractProxyHook {
    private static final String APP_NAME = ProxyHookClient.class.getName();
    private static final Logger log = LoggerFactory.getLogger(ProxyHookClient.class);


    // TODO use http://vertx.io/docs/vertx-core/java/#_vert_x_command_line_interface_api
    // not this mess
    private List<String> getArgs() {
        // command line is of the pattern "vertx run [options] main-verticle"
        // so strip off everything up to the Verticle class name
        List<String> processArgs = vertx.getOrCreateContext().processArgs();
        log.debug("processArgs: " + processArgs);
        int n = processArgs.indexOf(getClass().getName());
        List<String> argsAfterClass = processArgs.subList(n + 1, processArgs.size());
        List<String> result = argsAfterClass.stream().filter(arg -> !arg.startsWith("-")).collect(
                Collectors.toList());
        log.info("args: " + result);
        return result;
    }

    @Override
    public void start() throws Exception {
        // this /shouldn't/ be needed for deployment failures
//        vertx.exceptionHandler(this::die);
        List<String> args = getArgs();
        if (args.isEmpty()) {
            die("Please specify the ProxyHook server URL");
        } else {
            startClient(args);
        }
    }

    private void startClient(List<String> urls) {
        if (urls.size() < 2) {
            throw new RuntimeException("Usage: http://websocket.example.com/listen/ http://target1.example.com/ [http://target2.example.com/ ...]");
        }
        String webSocketAbsoluteUri = urls.get(0);
        List<String> webhookUrls = urls.subList(1, urls.size());
        log.info("starting client for websocket: " + webSocketAbsoluteUri + " posting to webhook URLs: " + webhookUrls);
        URL wsUrl = parseUrl(webSocketAbsoluteUri);
        String webSocketRelativeUri = wsUrl.getFile();
        HttpClientOptions options = new HttpClientOptions()
                .setMaxWebsocketFrameSize(MAX_FRAME_SIZE)
                .setDefaultHost(wsUrl.getHost())
                .setDefaultPort(getPort(wsUrl));
        HttpClient client = vertx.createHttpClient(options);

        client.websocket(webSocketRelativeUri, webSocket -> {
            String password = getenv(PROXYHOOK_PASSWORD);
            if (password == null) password = "";
            log.info("trying to log in");
            JsonObject jsonObject = new JsonObject();
            jsonObject.put("password", password);
            webSocket.writeFinalTextFrame(jsonObject.encode());

            // FIXME try to reconnect in case:
            // - server is restarted
            // - server is still starting
            // - connection breaks because of transient network error
            // OR just die, so that systemd can restart the process

            webSocket.handler((Buffer buf) -> {
                JsonObject msg = buf.toJsonObject();
                log.info("payload: " + msg);

                String type = msg.getString("type");
                switch (type) {
                    case "SUCCESS":
                        log.info("logged in");
                        break;
                    case "FAILED":
                        webSocket.close();
                        client.close();
                        die("login failed");
                        break;
                    case "WEBHOOK":
                        handleWebhook(webhookUrls, client, msg);
                        break;
                    default:
                        webSocket.close();
                        client.close();
                        die("unexpected message type: "+type);
                }
            });
        }, this::die);
    }

    private void handleWebhook(List<String> webhookUrls, HttpClient client, JsonObject msg) {
        // TODO use host, path and/or query from webSocket message?
//                String path = msg.getString("path");
//                String query = msg.getString("query");
        JsonArray headerPairs = msg.getJsonArray("headers");
        MultiMap headers = jsonToMultiMap(headerPairs);
        String bufferText = msg.getString("bufferText");
        byte[] buffer = msg.getBinary("buffer");
//                log.debug("buffer: "+ Arrays.toString(buffer));

        for (String webhookUri: webhookUrls) {
            HttpClientRequest request = client.postAbs(webhookUri, response -> {
                log.info("Webhook POST response headers: " + multiMapToJson(response.headers()));
                log.info("Webhook POST response status: " + response.statusCode() + " " + response.statusMessage());
            });
//                request.putHeader("content-type", "text/plain")
            request.headers().addAll(headers);
            if (bufferText != null) {
                request.end(Buffer.buffer(bufferText));
            } else {
                request.end(Buffer.buffer(buffer));
            }
        }
    }

    private URL parseUrl(String url) {
        try {
            return new URL(url);
        } catch (MalformedURLException e) {
            throw new RuntimeException("Invalid url: " + url);
        }
    }

    private int getPort(URL url) {
        int port = url.getPort();
        if (port == -1) {
            String protocol = url.getProtocol();
            char chend = protocol.charAt(protocol.length() - 1);
            if (chend == 'p') {
                port = 80;
            } else if (chend == 's'){
                port = 443;
            }
        }
        return port;
    }

}
