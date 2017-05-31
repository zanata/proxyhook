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
package org.flanigan.proxyhook.server;

import java.util.Set;

import org.flanigan.proxyhook.common.AbstractProxyHook;
import org.flanigan.proxyhook.common.MessageType;
import org.mindrot.jbcrypt.BCrypt;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.http.CaseInsensitiveHeaders;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.core.shareddata.LocalMap;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.ErrorHandler;

import static java.lang.System.getenv;
import static org.flanigan.proxyhook.common.Constants.MAX_BODY_SIZE;
import static org.flanigan.proxyhook.common.Constants.MAX_FRAME_SIZE;
import static org.flanigan.proxyhook.common.Constants.PATH_WEBHOOK;
import static org.flanigan.proxyhook.common.Constants.PATH_WEBSOCKET;
import static org.flanigan.proxyhook.common.Constants.PROXYHOOK_PASSHASH;
import static org.flanigan.proxyhook.common.JsonUtil.multiMapToJson;
import static org.flanigan.proxyhook.common.Keys.BUFFER;
import static org.flanigan.proxyhook.common.Keys.BUFFER_TEXT;
import static org.flanigan.proxyhook.common.Keys.HEADERS;
import static org.flanigan.proxyhook.common.Keys.HOST;
import static org.flanigan.proxyhook.common.Keys.PASSWORD;
import static org.flanigan.proxyhook.common.Keys.PATH;
import static org.flanigan.proxyhook.common.Keys.PING_ID;
import static org.flanigan.proxyhook.common.Keys.QUERY;
import static org.flanigan.proxyhook.common.Keys.TYPE;
import static org.flanigan.proxyhook.common.MessageType.FAILED;
import static org.flanigan.proxyhook.common.MessageType.PING;
import static org.flanigan.proxyhook.common.MessageType.PONG;
import static org.flanigan.proxyhook.common.MessageType.SUCCESS;
import static org.flanigan.proxyhook.common.MessageType.WEBHOOK;

/**
 * @author Sean Flanigan <a href="mailto:sflaniga@redhat.com">sflaniga@redhat.com</a>
 */
public class ProxyHookServer extends AbstractProxyHook {
    private static final String APP_NAME = ProxyHookServer.class.getName();
    private static final Logger log = LoggerFactory.getLogger(ProxyHookServer.class);

    // HTTP status codes
    private static final int HTTP_OK = 200;
//    private static final int HTTP_NO_CONTENT = 204;
//    private static final int HTTP_INTERNAL_SERVER_ERROR = 500;
//    private static final int HTTP_NOT_IMPLEMENTED = 501;
//    private static final int HTTP_BAD_GATEWAY = 502;
    private static final int HTTP_SERVICE_UNAVAILABLE = 503;
//    private static final int HTTP_GATEWAY_TIMEOUT = 504;

    @Override
    public void start() throws Exception {
        String passhash = getenv(PROXYHOOK_PASSHASH);
        if (passhash != null) {
            log.info("password is set");
        } else {
            log.warn("{0} is not set; authentication is disabled", PROXYHOOK_PASSHASH);
        }
        String host = System.getProperty("http.address", "127.0.0.1");
        int port = Integer.getInteger("http.port", 8080);
        log.info("Starting webhook/websocket server on " + host + ":" + port);
        logOpenShiftDetails();

        HttpServerOptions options = new HttpServerOptions()
                // 60s timeout based on pings every 50s
                .setIdleTimeout(60)
                .setMaxWebsocketFrameSize(MAX_FRAME_SIZE)
                .setPort(port)
                .setHost(host);
        HttpServer server = vertx.createHttpServer(options);
        EventBus eventBus = vertx.eventBus();
        // a set of textHandlerIds for connected websockets
        // TODO clustering: should use getClusterWideMap and getCounter
        LocalMap<String, Boolean> connections = vertx.sharedData().getLocalMap("connections");

        vertx.setPeriodic(50_000, timerId -> {
            // TODO clustering: should iterate through websockets of this verticle only (eg a local HashMap?)
            connections.keySet().forEach(connection -> {

                // this is probably the correct way (ping frame triggers pong, closes websocket if no data received before idleTimeout in TCPSSLOptions):
//                WebSocketFrameImpl frame = new WebSocketFrameImpl(FrameType.PING, io.netty.buffer.Unpooled.copyLong(System.currentTimeMillis()));
//                webSocket.writeFrame(frame);

                JsonObject object = new JsonObject();
                object.put(TYPE, PING);
                object.put(PING_ID, String.valueOf(System.currentTimeMillis()));
                eventBus.send(connection, object.encode());
            });
        });

        Router router = Router.router(vertx);
        router.exceptionHandler(t -> log.error("Unhandled exception", t));
        router.route()
                .handler(BodyHandler.create().setBodyLimit(MAX_BODY_SIZE))
//                .handler(LoggerHandler.create())
                .failureHandler(ErrorHandler.create());
        // we need to respond to GET / so that health checks will work:
        router.get("/").handler(routingContext ->
                routingContext.response().setStatusCode(HTTP_OK).end(APP_NAME + " ("+describe(connections.size())+")")
        );
        // see https://github.com/vert-x3/vertx-health-check if we need more features
        router.get("/ready").handler(routingContext -> routingContext.response()
                // if there are no connections, webhooks won't be delivered, thus HTTP_SERVICE_UNAVAILABLE
                .setStatusCode(connections.isEmpty() ? HTTP_SERVICE_UNAVAILABLE : HTTP_OK)
                .end(APP_NAME + " (" + describe(connections.size()) + ")")
        );
        router.post("/" + PATH_WEBHOOK).handler(routingContext -> {
            log.info("handling POST request");
            HttpServerRequest req = routingContext.request();
            MultiMap headers = req.headers();
            for (String header : EVENT_ID_HEADERS) {
                if (headers.contains(header)) {
                    log.info("{0}: {1}", header, headers.getAll(header));
                }
            }
            int statusCode;
            Set<String> listeners = connections.keySet();
            log.info("handling POST for {0} listeners", listeners.size());
            if (!listeners.isEmpty()) {
                Buffer body = routingContext.getBody();
                String msgString = encodeWebhook(req, body);
                for (String connection : listeners) {
                    eventBus.send(connection, msgString);
                }
                log.info("Webhook "+ req.path()+" received "+body.length()+" bytes. Forwarded to "+describe(listeners.size())+".");
                statusCode = HTTP_OK;
            } else {
                // nothing to do
                log.warn("Webhook "+ req.path()+" received, but there are no listeners connected.");

                // returning an error should make it easier for client to redeliver later (when there is a listener)
                statusCode = HTTP_SERVICE_UNAVAILABLE;
            }
            routingContext.response()
                    .setStatusCode(statusCode)
                    .end("Received by "+APP_NAME+" ("+describe(listeners.size())+")");
        });
        server.requestHandler(router::accept);
        server.websocketHandler((ServerWebSocket webSocket) -> {
            if (!webSocket.path().equals("/"+PATH_WEBSOCKET)) {
                log.warn("wrong path for websocket connection");
                webSocket.reject();
                return;
            }
            webSocket.handler((Buffer buffer) -> {
                JsonObject msg = buffer.toJsonObject();
                String type = msg.getString(TYPE);
                MessageType messageType = MessageType.valueOf(type);
                switch (messageType) {
                    case LOGIN:
                        String password = msg.getString(PASSWORD);
                        if (passhash == null) {
                            log.info("unverified websocket connection");
                            JsonObject object = new JsonObject();
                            object.put(TYPE, SUCCESS);
                            webSocket.writeTextMessage(object.encode());
                            registerWebsocket(connections, webSocket);
                        } else if (BCrypt.checkpw(password, passhash)) {
                            log.info("password accepted");
                            JsonObject object = new JsonObject();
                            object.put(TYPE, SUCCESS);
                            webSocket.writeTextMessage(object.encode());
                            registerWebsocket(connections, webSocket);
                        } else {
                            log.warn("password rejected");
                            JsonObject object = new JsonObject();
                            object.put(TYPE, FAILED);
                            webSocket.writeTextMessage(object.encode());
                            webSocket.close();
                        }
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
                        log.warn("unexpected message: {0}", msg);
                        JsonObject object = new JsonObject();
                        object.put(TYPE, FAILED);
                        webSocket.writeTextMessage(object.encode());
                        webSocket.close();
                }
            });
        });
        server.listen(startupResult -> {
            if (startupResult.failed()) {
                die(startupResult.cause());
            }
        });
    }

    private void logOpenShiftDetails() {
        String appDns = System.getenv("OPENSHIFT_APP_DNS");
        if (appDns != null) {
            log.info("Running on OpenShift");
            log.info("Webhooks should be POSTed to https://{0}/webhook (secure) or http://{0}/webhook (insecure)", appDns);
            log.info("ProxyHook client should connect to wss://{0}:8433/listen (secure) or ws://{0}:8000/listen (insecure)", appDns);
        }
    }

    private void registerWebsocket(LocalMap<String, Boolean> connections,
            ServerWebSocket webSocket) {
        // TODO enhancement: register specific webhook path using webSocket.path() or webSocket.query()
        String id = webSocket.textHandlerID();
        String clientIP = getClientIP(webSocket);
        log.info("Adding connection. ID: " + id + " IP: " + clientIP);
        connections.put(id, true);
        log.info("Total connections: {0}", connections.size());
        webSocket.closeHandler((Void event) -> {
            log.info("Connection closed. ID: {0} IP: {1}", id, clientIP);
            connections.remove(id);
            log.info("Total connections: {0}", connections.size());
        });
        webSocket.exceptionHandler(e -> {
            log.warn("Connection error. ID: {0} IP: {1}", e, id, clientIP);
            connections.remove(id);
            log.info("Total connections: {0}", connections.size());
        });
    }

    private String getClientIP(ServerWebSocket webSocket) {
        String clientIP = webSocket.headers().get("X-Client-Ip");
        if (clientIP == null) clientIP = webSocket.headers().get("X-Forwarded-For");
        if (clientIP == null) clientIP = webSocket.remoteAddress().host();
        return clientIP;
    }

    static String describe(int size) {
        if (size == 1) {
            return "1 listener";
        } else {
            return "" + size + " listeners";
        }
    }

    private String encodeWebhook(HttpServerRequest req, Buffer buffer) {
        JsonObject msg = new JsonObject();
        msg.put(TYPE, WEBHOOK);
        msg.put(PATH, req.path());
        msg.put(QUERY, req.query());
        MultiMap headers = new CaseInsensitiveHeaders().addAll(req.headers());
        msg.put(HOST, headers.get("Host"));
        headers.remove("Host");
//                    headers.remove("Content-Length");
        // serialise MultiMap
        msg.put(HEADERS, multiMapToJson(headers));

        if (treatAsUTF8(headers.get(HttpHeaders.CONTENT_TYPE))) {
            // toString will blow up if not valid UTF-8
            msg.put(BUFFER_TEXT, buffer.toString());
        } else {
            msg.put(BUFFER, buffer.getBytes());
        }
        return msg.encode();
    }

    boolean treatAsUTF8(String contentType) {
        String[] contentTypeSplit = contentType.toLowerCase().split("; *");
        for (String s: contentTypeSplit) {
            if (s.matches("charset=(utf-?8|ascii)")) {
                return true;
            }
        }
        switch (contentType) {
            // Only allows unicode:
            case "application/json":
            // Defaults to unicode.
            // An XML doc could specify another (non-Unicode) charset internally, but we don't support this
            case "application/xml":
            // Defaults to ASCII:
            case "text/xml":
                return true;
            default:
                // If in doubt, treat as non-Unicode
                return false;
        }
    }

}
