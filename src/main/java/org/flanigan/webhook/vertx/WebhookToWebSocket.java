package org.flanigan.webhook.vertx;

import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.http.*;
import io.vertx.core.http.impl.HeadersAdaptor;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.core.shareddata.LocalMap;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class WebhookToWebSocket extends AbstractVerticle {
    private static final String APP_NAME = WebhookToWebSocket.class.getName();
    private static final Logger log = LoggerFactory.getLogger(WebhookToWebSocket.class);
    // 10 megabytes
    public static final int MAX_FRAME_SIZE = 10_000_000;

    private static String describe(Set set) {
        if (set.size() == 1) {
            return "1 listener";
        } else {
            return "" + set.size() + " listeners";
        }
    }

    // TODO use http://vertx.io/docs/vertx-core/java/#_vert_x_command_line_interface_api
    // not this mess
    private List<String> getArgs() {
        // command line is of the pattern "vertx run [options] main-verticle"
        // so strip off everything up to the Verticle class name
        List<String> processArgs = vertx.getOrCreateContext().processArgs();
        log.debug("processArgs: " + processArgs);
        int n = processArgs.indexOf(getClass().getName());
        List<String> argsAfterClass = processArgs.subList(n + 1, processArgs.size());
        List<String> result = argsAfterClass.stream().filter(arg -> !arg.startsWith("-")).collect(Collectors.toList());
        log.info("args: " + result);
        return result;
    }

    @Override
    public void start() throws Exception {
        // this /shouldn't/ be needed for deployment failures
//        vertx.exceptionHandler(this::die);
        List<String> args = getArgs();
        if (args.isEmpty()) {
            startServer();
        } else {
            startClient(args);
        }
    }

//    public static void main(String[] args) {
//        CaseInsensitiveHeaders h = new CaseInsensitiveHeaders();
//        h.add("Host", "example.com");
//        System.out.println("host="+h.get("host"));
//        System.out.println("mmap="+h.toString());
//        h.remove("HOST");
//        System.out.println("mmap="+h.toString());
//    }

    private void startServer() {
        String host = System.getProperty("http.address", "127.0.0.1");
        int port = Integer.getInteger("http.port", 8080);
        log.info("Starting webhook/websocket server on " + host + ":" + port);

        HttpServerOptions options = new HttpServerOptions()
                .setMaxWebsocketFrameSize(MAX_FRAME_SIZE)
                .setPort(port)
                .setHost(host);
        HttpServer server = vertx.createHttpServer(options);
        EventBus eventBus = vertx.eventBus();
        LocalMap<String, Boolean> connections = vertx.sharedData().getLocalMap("connections");

        server.requestHandler((HttpServerRequest req) -> {
            // TODO only forward POST method
            // TODO keySet might be a little expensive
            Set<String> listeners = connections.keySet();
            req.bodyHandler(buffer -> {
                if (req.method() != HttpMethod.POST) return;
//                if (req.uri().equals("/listen")) return;
                // nothing to do?
                if (listeners.isEmpty()) return;
                for (String connection : listeners) {
                    JsonObject msg = new JsonObject();
                    msg.put("path", req.path());
                    msg.put("query", req.query());
                    MultiMap headers = new CaseInsensitiveHeaders().addAll(req.headers());
                    msg.put("host", headers.get("Host"));
                    headers.remove("Host");
//                    headers.remove("Content-Length");
                    // serialise MultiMap
                    msg.put("headers", multiMapToJson(headers));

                    if (isUTF8(headers.get(HttpHeaders.CONTENT_TYPE))) {
                        // toString will blow up if not valid UTF-8
                        msg.put("bufferText", buffer.toString());
                    } else {
                        msg.put("buffer", buffer.getBytes());
                    }
                    eventBus.send(connection, msg.encode());
                }
                log.info("Webhook "+req.path()+" received "+buffer.length()+" bytes. Forwarded to "+describe(listeners)+".");
            });
            req.response()
                    .setStatusCode(200)
                    .setStatusMessage("Received by "+APP_NAME+" ("+describe(listeners)+")")
                    .end("Received.");
        });
        server.websocketHandler((ServerWebSocket webSocket) -> {
            // TODO security: check a password or something (needs SSL)
            if (!webSocket.path().equals("/listen")) {
                webSocket.reject();
                return;
            }
            // TODO enhancement: register specific webhook path using webSocket.path() or webSocket.query()
            String id = webSocket.textHandlerID();
            log.info("registering new connection with id: " + id);
            connections.put(id, true);

            webSocket.closeHandler((Void event) -> {
                log.info("un-registering connection with id: " + id);
                connections.remove(id);
            });
        }).listen(e -> {
            if (e.failed()) {
                die(e.cause());
//                die(e.cause(), server);
            }
        });
    }

    boolean isUTF8(String contentType) {
        if (contentType.equals("application/json")) {
            return true;
        }
        String[] contentTypeSplit = contentType.toLowerCase().split("; *");
        for (String s: contentTypeSplit) {
            if (s.matches("content=utf-?8")) {
                return true;
            }
        }
        return false;
    }

    private JsonArray multiMapToJson(MultiMap headers) {
        JsonArray headerList = new JsonArray();
        headers.forEach(entry ->
                headerList.add(new JsonArray().add(entry.getKey()).add(entry.getValue())));
        return headerList;
    }

    private MultiMap jsonToMultiMap(JsonArray pairs) {
        MultiMap map = new HeadersAdaptor(new DefaultHttpHeaders());
        pairs.forEach(pair -> {
            JsonArray p = (JsonArray) pair;
            map.add(p.getString(0), p.getString(1));
        });
        return map;
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
            // FIXME try to reconnect in case:
            // - server is restarted
            // - server is still starting
            // - connection breaks because of transient network error
            // OR just die, so that systemd can restart the process
            webSocket.frameHandler((WebSocketFrame frame) -> {
                // FIXME handle fragments
                if (!frame.isFinal()) {
                    log.warn("Ignoring unexpected non-final frame");
                    return;
                }
                if (frame.isContinuation()) {
                    log.warn("Ignoring unexpected continuation frame");
                    return;
                }
                if (!frame.isText()) {
                    log.warn("Ignoring unexpected non-text frame");
                    return;
                }
                String payload = frame.textData();
                log.info("payload: " + payload);

                JsonObject msg = new JsonObject(payload);
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
            });
        }, this::die);
    }

    private void die(Throwable e) {
        log.fatal("dying", e);
        vertx.close();
    }

}

