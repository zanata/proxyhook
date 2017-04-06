package org.flanigan.proxyhook;

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
import org.mindrot.jbcrypt.BCrypt;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static java.lang.System.getenv;

public class ProxyHook extends AbstractVerticle {
    private static final String APP_NAME = ProxyHook.class.getName();
    private static final Logger log = LoggerFactory.getLogger(ProxyHook.class);
    // 10 megabytes. GitHub payloads are a maximum of 5MB.
    // https://developer.github.com/webhooks/#payloads
    private static final int MAX_FRAME_SIZE = 10_000_000;
    private static final String PROXYHOOK_PASSHASH = "PROXYHOOK_PASSHASH";
    private static final String PROXYHOOK_PASSWORD = "PROXYHOOK_PASSWORD";

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

    public static void main(String[] args) {
        String password = getenv(PROXYHOOK_PASSWORD);
        if (password == null) {
            System.err.println("Please set environment variable " + PROXYHOOK_PASSWORD);
            System.exit(1);
        }
//        Console console = System.console();
//        if (console == null) {
//            System.err.println("No console found");
//            System.exit(1);
//        }
//        String password = new String(console.readPassword("Please enter password: "));
        String hashed = BCrypt.hashpw(password, BCrypt.gensalt(12));
        System.out.println(PROXYHOOK_PASSHASH + "='" + hashed + "'");
    }

    private void startServer() {
        String passhash = getenv(PROXYHOOK_PASSHASH);
        if (passhash != null) {
            log.info("password is set");
        } else {
            log.warn("{} is not set; authentication is disabled", PROXYHOOK_PASSHASH);
        }
        String host = System.getProperty("http.address", "127.0.0.1");
        int port = Integer.getInteger("http.port", 8080);
        log.info("Starting webhook/websocket server on " + host + ":" + port);

        HttpServerOptions options = new HttpServerOptions()
                .setMaxWebsocketFrameSize(MAX_FRAME_SIZE)
                .setPort(port)
                .setHost(host);
        HttpServer server = vertx.createHttpServer(options);
        EventBus eventBus = vertx.eventBus();
        // a set of textHandlerIds for connected websockets
        LocalMap<String, Boolean> connections = vertx.sharedData().getLocalMap("connections");

        server.requestHandler((HttpServerRequest req) -> {
            log.info("handling request");
            // TODO only forward POST method
            // TODO keySet might be a little expensive
            Set<String> listeners = connections.keySet();
            req.bodyHandler(buffer -> {
                if (req.method() != HttpMethod.POST) return;
//                if (req.uri().equals("/listen")) return;
                // nothing to do?
                if (listeners.isEmpty()) return;
                log.info("handling POST for {} listeners", listeners.size());
                String msgString = encodeWebhook(req, buffer);
                for (String connection : listeners) {
                    eventBus.send(connection, msgString);
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
                log.warn("wrong path for websocket connection");
                webSocket.reject();
                return;
            }
            if (passhash != null) {
                webSocket.handler((Buffer buffer) -> {
                    JsonObject jsonObject = buffer.toJsonObject();
                    String password = jsonObject.getString("password");
                    if (BCrypt.checkpw(password, passhash)) {
                        log.info("password accepted");
                        JsonObject object = new JsonObject();
                        object.put("type", "SUCCESS");
                        webSocket.writeFinalTextFrame(object.encode());
                        registerWebsocket(connections, webSocket);
                    } else {
                        // FIXME return a login error
                        log.warn("password rejected");
                        JsonObject object = new JsonObject();
                        object.put("type", "FAILED");
                        webSocket.writeFinalTextFrame(object.encode());
                        webSocket.close();
                    }
                });
            } else {
                log.info("unverified websocket connection");
                registerWebsocket(connections, webSocket);
            }
        }).listen(e -> {
            if (e.failed()) {
                die(e.cause());
//                die(e.cause(), server);
            }
        });
    }

    private void registerWebsocket(LocalMap<String, Boolean> connections, ServerWebSocket webSocket) {
        // TODO enhancement: register specific webhook path using webSocket.path() or webSocket.query()
        String id = webSocket.textHandlerID();
        log.info("registering websocket connection with id: " + id);
        connections.put(id, true);
        webSocket.closeHandler((Void event) -> {
            log.info("un-registering websocket connection with id: " + id);
            connections.remove(id);
        });
    }

    private String encodeWebhook(HttpServerRequest req, Buffer buffer) {
        JsonObject msg = new JsonObject();
        msg.put("type", "WEBHOOK");
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
        return msg.encode();
    }

    private boolean isUTF8(String contentType) {
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

    private void die(String s) {
        log.fatal(s);
        vertx.close(e -> System.exit(1));
        try {
            Thread.sleep(1000L);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        System.exit(2);
    }

    private void die(Throwable t) {
        log.fatal("dying", t);
        vertx.close(e -> System.exit(1));
        try {
            Thread.sleep(1000L);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        System.exit(2);
    }

}
