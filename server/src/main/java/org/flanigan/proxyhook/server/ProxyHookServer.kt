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
package org.flanigan.proxyhook.server

import org.flanigan.proxyhook.common.AbstractProxyHook
import org.flanigan.proxyhook.common.MessageType
import org.mindrot.jbcrypt.BCrypt
import io.vertx.core.buffer.Buffer
import io.vertx.core.http.CaseInsensitiveHeaders
import io.vertx.core.http.HttpHeaders
import io.vertx.core.http.HttpServerOptions
import io.vertx.core.http.HttpServerRequest
import io.vertx.core.http.ServerWebSocket
import io.vertx.core.json.JsonObject
import io.vertx.core.logging.LoggerFactory
import io.vertx.core.shareddata.LocalMap
import io.vertx.ext.web.Router
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.ext.web.handler.ErrorHandler

import java.lang.System.getenv
import org.flanigan.proxyhook.common.Constants.MAX_BODY_SIZE
import org.flanigan.proxyhook.common.Constants.MAX_FRAME_SIZE
import org.flanigan.proxyhook.common.Constants.PATH_WEBHOOK
import org.flanigan.proxyhook.common.Constants.PATH_WEBSOCKET
import org.flanigan.proxyhook.common.Constants.PROXYHOOK_PASSHASH
import org.flanigan.proxyhook.common.JsonUtil.multiMapToJson
import org.flanigan.proxyhook.common.Keys.BUFFER
import org.flanigan.proxyhook.common.Keys.BUFFER_TEXT
import org.flanigan.proxyhook.common.Keys.HEADERS
import org.flanigan.proxyhook.common.Keys.HOST
import org.flanigan.proxyhook.common.Keys.PASSWORD
import org.flanigan.proxyhook.common.Keys.PATH
import org.flanigan.proxyhook.common.Keys.PING_ID
import org.flanigan.proxyhook.common.Keys.QUERY
import org.flanigan.proxyhook.common.Keys.TYPE
import org.flanigan.proxyhook.common.MessageType.FAILED
import org.flanigan.proxyhook.common.MessageType.PING
import org.flanigan.proxyhook.common.MessageType.PONG
import org.flanigan.proxyhook.common.MessageType.SUCCESS
import org.flanigan.proxyhook.common.MessageType.WEBHOOK

/**
 * @author Sean Flanigan [sflaniga@redhat.com](mailto:sflaniga@redhat.com)
 */
class ProxyHookServer(val port: Int? = null) : AbstractProxyHook() {
    //    private static final int HTTP_GATEWAY_TIMEOUT = 504;

    @Throws(Exception::class)
    override fun start() {
        val passhash = getenv(PROXYHOOK_PASSHASH)
        if (passhash != null) {
            log.info("password is set")
        } else {
            log.warn("{0} is not set; authentication is disabled", PROXYHOOK_PASSHASH)
        }
        val host = System.getProperty("http.address", "127.0.0.1")
        val listenPort: Int = port ?: Integer.getInteger("http.port", 8080)!!
        log.info("Starting webhook/websocket server on $host:$listenPort")
        logOpenShiftDetails()

        val options = HttpServerOptions()
                // 60s timeout based on pings every 50s
                .setIdleTimeout(60)
                .setMaxWebsocketFrameSize(MAX_FRAME_SIZE)
                .setPort(listenPort)
                .setHost(host)
        val server = vertx.createHttpServer(options)
        val eventBus = vertx.eventBus()
        // a set of textHandlerIds for connected websockets
        // TODO clustering: should use getClusterWideMap and getCounter
        val connections = vertx.sharedData().getLocalMap<String, Boolean>("connections")

        vertx.setPeriodic(50_000) {
            // TODO clustering: should iterate through websockets of this verticle only (eg a local HashMap?)
            connections.keys.forEach { connection ->

                // this is probably the correct way (ping frame triggers pong, closes websocket if no data received before idleTimeout in TCPSSLOptions):
                //                WebSocketFrameImpl frame = new WebSocketFrameImpl(FrameType.PING, io.netty.buffer.Unpooled.copyLong(System.currentTimeMillis()));
                //                webSocket.writeFrame(frame);

                val obj = JsonObject()
                obj.put(TYPE, PING)
                obj.put(PING_ID, System.currentTimeMillis().toString())
                eventBus.send(connection, obj.encode())
            }
        }

        val router = Router.router(vertx)
        router.exceptionHandler { t -> log.error("Unhandled exception", t) }
        router.route()
                .handler(BodyHandler.create().setBodyLimit(MAX_BODY_SIZE.toLong()))
                //                .handler(LoggerHandler.create())
                .failureHandler(ErrorHandler.create())
        // we need to respond to GET / so that health checks will work:
        router.get("/").handler { routingContext -> routingContext.response().setStatusCode(HTTP_OK).end(APP_NAME + " (" + describe(connections.size) + ")") }
        // see https://github.com/vert-x3/vertx-health-check if we need more features
        router.get("/ready").handler { routingContext ->
            routingContext.response()
                    // if there are no connections, webhooks won't be delivered, thus HTTP_SERVICE_UNAVAILABLE
                    .setStatusCode(if (connections.isEmpty()) HTTP_SERVICE_UNAVAILABLE else HTTP_OK)
                    .end(APP_NAME + " (" + describe(connections.size) + ")")
        }
        router.post("/" + PATH_WEBHOOK).handler { routingContext ->
            log.info("handling POST request")
            val req = routingContext.request()
            val headers = req.headers()
            for (header in EVENT_ID_HEADERS) {
                if (headers.contains(header)) {
                    log.info("{0}: {1}", header, headers.getAll(header))
                }
            }
            val statusCode: Int
            val listeners = connections.keys
            log.info("handling POST for {0} listeners", listeners.size)
            if (!listeners.isEmpty()) {
                val body = routingContext.body
                val msgString = encodeWebhook(req, body)
                for (connection in listeners) {
                    eventBus.send(connection, msgString)
                }
                log.info("Webhook " + req.path() + " received " + body.length() + " bytes. Forwarded to " + describe(listeners.size) + ".")
                statusCode = HTTP_OK
            } else {
                // nothing to do
                log.warn("Webhook " + req.path() + " received, but there are no listeners connected.")

                // returning an error should make it easier for client to redeliver later (when there is a listener)
                statusCode = HTTP_SERVICE_UNAVAILABLE
            }
            routingContext.response()
                    .setStatusCode(statusCode)
                    .end("Received by " + APP_NAME + " (" + describe(listeners.size) + ")")
        }
        server.requestHandler({ router.accept(it) })
        server.websocketHandler { webSocket: ServerWebSocket ->
            if (webSocket.path() != "/" + PATH_WEBSOCKET) {
                log.warn("wrong path for websocket connection")
                webSocket.reject()
                return@websocketHandler
            }
            webSocket.handler { buffer: Buffer ->
                val msg = buffer.toJsonObject()
                val type = msg.getString(TYPE)
                val messageType = MessageType.valueOf(type)
                when (messageType) {
                    MessageType.LOGIN -> {
                        val password = msg.getString(PASSWORD)
                        if (passhash == null) {
                            log.info("unverified websocket connection")
                            val obj = JsonObject()
                            obj.put(TYPE, SUCCESS)
                            webSocket.writeTextMessage(obj.encode())
                            registerWebsocket(connections, webSocket)
                        } else if (BCrypt.checkpw(password, passhash)) {
                            log.info("password accepted")
                            val obj = JsonObject()
                            obj.put(TYPE, SUCCESS)
                            webSocket.writeTextMessage(obj.encode())
                            registerWebsocket(connections, webSocket)
                        } else {
                            log.warn("password rejected")
                            val obj = JsonObject()
                            obj.put(TYPE, FAILED)
                            webSocket.writeTextMessage(obj.encode())
                            webSocket.close()
                        }
                    }
                    PING -> {
                        val pingId = msg.getString(PING_ID)
                        log.debug("received PING with id {}", pingId)
                        val pong = JsonObject()
                        pong.put(TYPE, PONG)
                        pong.put(PING_ID, pingId)
                        webSocket.writeTextMessage(pong.encode())
                    }
                    PONG -> {
                        val pongId = msg.getString(PING_ID)
                        // TODO check ping ID
                        log.debug("received PONG with id {}", pongId)
                    }
                    else -> {
                        log.warn("unexpected message: {0}", msg)
                        val obj = JsonObject()
                        obj.put(TYPE, FAILED)
                        webSocket.writeTextMessage(obj.encode())
                        webSocket.close()
                    }
                }
            }
        }
        server.listen { startupResult ->
            if (startupResult.failed()) {
                die(startupResult.cause())
            } else {
                log.info("Started server on port ${server.actualPort()}")
            }
        }
    }

    private fun logOpenShiftDetails() {
        val appDns = System.getenv("OPENSHIFT_APP_DNS")
        if (appDns != null) {
            log.info("Running on OpenShift")
            log.info("Webhooks should be POSTed to https://{0}/webhook (secure) or http://{0}/webhook (insecure)", appDns)
            log.info("ProxyHook client should connect to wss://{0}:8433/listen (secure) or ws://{0}:8000/listen (insecure)", appDns)
        }
    }

    private fun registerWebsocket(connections: LocalMap<String, Boolean>,
                                  webSocket: ServerWebSocket) {
        // TODO enhancement: register specific webhook path using webSocket.path() or webSocket.query()
        val id = webSocket.textHandlerID()
        val clientIP = getClientIP(webSocket)
        log.info("Adding connection. ID: $id IP: $clientIP")
        connections.put(id, true)
        log.info("Total connections: {0}", connections.size)
        webSocket.closeHandler {
            log.info("Connection closed. ID: {0} IP: {1}", id, clientIP)
            connections.remove(id)
            log.info("Total connections: {0}", connections.size)
        }
        webSocket.exceptionHandler { e ->
            log.warn("Connection error. ID: {0} IP: {1}", e, id, clientIP)
            connections.remove(id)
            log.info("Total connections: {0}", connections.size)
        }
    }

    private fun getClientIP(webSocket: ServerWebSocket): String {
        var clientIP: String? = webSocket.headers().get("X-Client-Ip")
        if (clientIP == null) clientIP = webSocket.headers().get("X-Forwarded-For")
        if (clientIP == null) clientIP = webSocket.remoteAddress().host()!!
        return clientIP
    }

    private fun encodeWebhook(req: HttpServerRequest, buffer: Buffer): String {
        val msg = JsonObject()
        msg.put(TYPE, WEBHOOK)
        msg.put(PATH, req.path())
        msg.put(QUERY, req.query())
        val headers = CaseInsensitiveHeaders().addAll(req.headers())
        msg.put(HOST, headers.get("Host"))
        headers.remove("Host")
        //                    headers.remove("Content-Length");
        // serialise MultiMap
        msg.put(HEADERS, multiMapToJson(headers))

        if (treatAsUTF8(headers.get(HttpHeaders.CONTENT_TYPE))) {
            // toString will blow up if not valid UTF-8
            msg.put(BUFFER_TEXT, buffer.toString())
        } else {
            msg.put(BUFFER, buffer.bytes)
        }
        return msg.encode()
    }

    internal fun treatAsUTF8(contentType: String?): Boolean {
        if (contentType == null) return false // equiv. to application/octet-stream
        val contentTypeSplit = contentType.toLowerCase().split("; *".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
        for (s in contentTypeSplit) {
            if (s.matches("charset=(utf-?8|ascii)".toRegex())) {
                return true
            }
        }
        when (contentType) {
        // Only allows unicode:
            "application/json",
                // Defaults to unicode.
                // An XML doc could specify another (non-Unicode) charset internally, but we don't support this
            "application/xml",
                // Defaults to ASCII:
            "text/xml" -> return true
            else ->
                // If in doubt, treat as non-Unicode
                return false
        }
    }

    /**
     * Exits after logging the specified throwable
     * @param t throwable to log
     * *
     * @return nothing; does not return (generics trick from https://stackoverflow.com/a/15019663/14379)
     */
    private fun die(t: Throwable): Nothing {
        log.fatal("dying", t)
        @Suppress("UNREACHABLE_CODE")
        return startShutdown()
    }

    companion object {
        private val APP_NAME = ProxyHookServer::class.java.name
        private val log = LoggerFactory.getLogger(ProxyHookServer::class.java)

        // HTTP status codes
        private val HTTP_OK = 200
        //    private static final int HTTP_NO_CONTENT = 204;
        //    private static final int HTTP_INTERNAL_SERVER_ERROR = 500;
        //    private static final int HTTP_NOT_IMPLEMENTED = 501;
        //    private static final int HTTP_BAD_GATEWAY = 502;
        private val HTTP_SERVICE_UNAVAILABLE = 503

        internal fun describe(size: Int): String {
            if (size == 1) {
                return "1 listener"
            } else {
                return "" + size + " listeners"
            }
        }
    }

}
