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

import java.util.Collection;
import java.util.Set;

import org.flanigan.proxyhook.common.AbstractProxyHook;
import org.mindrot.jbcrypt.BCrypt;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.http.CaseInsensitiveHeaders;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.core.shareddata.LocalMap;

import static java.lang.System.getenv;
import static org.flanigan.proxyhook.common.Constants.MAX_FRAME_SIZE;
import static org.flanigan.proxyhook.common.Constants.PROXYHOOK_PASSHASH;
import static org.flanigan.proxyhook.common.JsonUtil.multiMapToJson;

/**
 * @author Sean Flanigan <a href="mailto:sflaniga@redhat.com">sflaniga@redhat.com</a>
 */
public class ProxyHookServer extends AbstractProxyHook {
    private static final String APP_NAME = ProxyHookServer.class.getName();
    private static final Logger log = LoggerFactory.getLogger(ProxyHookServer.class);

    @Override
    public void start() throws Exception {
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

    private void registerWebsocket(LocalMap<String, Boolean> connections,
            ServerWebSocket webSocket) {
        // TODO enhancement: register specific webhook path using webSocket.path() or webSocket.query()
        String id = webSocket.textHandlerID();
        log.info("registering websocket connection with id: " + id);
        connections.put(id, true);
        webSocket.closeHandler((Void event) -> {
            log.info("un-registering websocket connection with id: " + id);
            connections.remove(id);
        });
    }

    static String describe(Collection collection) {
        if (collection.size() == 1) {
            return "1 listener";
        } else {
            return "" + collection.size() + " listeners";
        }
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


}
