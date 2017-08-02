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
package org.zanata.proxyhook.client

import io.netty.buffer.Unpooled
import io.vertx.core.AbstractVerticle
import io.vertx.core.Future
import io.vertx.core.MultiMap
import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.core.http.HttpClient
import io.vertx.core.http.HttpClientOptions
import io.vertx.core.http.HttpClientResponse
import io.vertx.core.http.WebSocket
import io.vertx.core.http.impl.FrameType
import io.vertx.core.http.impl.ws.WebSocketFrameImpl
import io.vertx.core.json.JsonObject
import io.vertx.core.logging.LoggerFactory
import org.zanata.proxyhook.common.*
import org.zanata.proxyhook.common.Constants.EVENT_ID_HEADERS
import org.zanata.proxyhook.common.Constants.MAX_FRAME_SIZE
import org.zanata.proxyhook.common.Constants.PATH_WEBSOCKET
import org.zanata.proxyhook.common.Constants.PROXYHOOK_PASSWORD
import org.zanata.proxyhook.common.Keys.BUFFER
import org.zanata.proxyhook.common.Keys.BUFFER_TEXT
import org.zanata.proxyhook.common.Keys.HEADERS
import org.zanata.proxyhook.common.Keys.PASSWORD
import org.zanata.proxyhook.common.Keys.PING_ID
import org.zanata.proxyhook.common.Keys.TYPE
import org.zanata.proxyhook.common.MessageType.LOGIN
import org.zanata.proxyhook.common.MessageType.PONG
import java.lang.System.getenv
import java.net.InetAddress
import java.net.URI
import java.net.URISyntaxException
import java.net.UnknownHostException

/**
 * The client component of ProxyHook, implemented as a vert.x verticle.
 * @param args first arg is websocket URL for proxyhook server.
 * other args are URLs where proxied webhooks will be delivered.
 * @param ready optional Future which will complete when deployment is complete.
 * @author Sean Flanigan [sflaniga@redhat.com](mailto:sflaniga@redhat.com)
 */
class ProxyHookClient(var ready: Future<Unit>? = null, var args: List<String>? = null) : AbstractVerticle() {
    constructor(ready: Future<Unit>?, vararg args: String) : this(ready, args.asList())

    companion object {
        private val APP_NAME = ProxyHookClient::class.java.name
        private val log = LoggerFactory.getLogger(ProxyHookClient::class.java)

        private val sslInsecure: Boolean by lazy {
            val insecure = getenv("SSL_INSECURE").equals(other = "true", ignoreCase = true)
            if (insecure) log.warn("SSL hostname verification is disabled")
            insecure
        }

        // Request header references:
        // https://docs.gitlab.com/ce/user/project/integrations/webhooks.html
        // https://gitlab.com/gitlab-org/gitlab-ce/blob/v9.1.2/app/models/hooks/web_hook.rb#L60
        // https://developer.github.com/webhooks/#delivery-headers
        // https://en.wikipedia.org/wiki/List_of_HTTP_header_fields#Request_fields
        private val HEADERS_TO_COPY = setOf(
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
                // gitlab signature: not yet: https://gitlab.com/gitlab-org/gitlab-ce/issues/4689
                "X-Hub-Signature",
                "X-Request-Id",
                "X-Trello-Webhook",
                "X-Zanata-Webhook")
                // deliberately not included: Connection, Host, Origin, If-*, Cache-Control, Proxy-Authorization, Range, Upgrade
                .map { it.toLowerCase() }

        @JvmStatic fun main(args: Array<String>) {
            Vertx.vertx().deployVerticle(ProxyHookClient(ready = null, args = args.toList()), { result ->
                result.otherwise { e ->
                    exit(e)
                }
            })
        }
    }

    // TODO use http://vertx.io/docs/vertx-core/java/#_vert_x_command_line_interface_api
    // not this mess.
    // Command line is of the pattern "vertx run [options] main-verticle [verticle_args...]"
    // so strip off everything up to the Verticle class name.
    private fun findArgs(): List<String> {
        args?.let { return it }
        val processArgs = vertx.orCreateContext.processArgs() ?: listOf()
        log.debug("processArgs: " + processArgs)
        val n = processArgs.indexOf(javaClass.name)
        val argsAfterClass = processArgs.subList(n + 1, processArgs.size)
        val result = argsAfterClass.filter { arg -> !arg.startsWith("-") }
        log.debug("args: " + result)
        args = result
        return result
    }

    override fun start(startFuture: Future<Void>) {
        val args = findArgs()
        if (args.size < 2) {
            throw StartupException("Usage: wss://proxyhook.example.com/$PATH_WEBSOCKET http://target1.example.com/webhook [http://target2.example.com/webhook ...]")
        }
        startClient(args[0], args.subList(1, args.size), startFuture)
    }

    private fun startClient(webSocketUrl: String, webhookUrls: List<String>, startFuture: Future<Void>) {
        log.info("starting client for websocket: $webSocketUrl posting to webhook URLs: $webhookUrls")

        webhookUrls.forEach { this.checkURI(it) }

        val wsUri = parseUri(webSocketUrl)
        val webSocketRelativeUri = getRelativeUri(wsUri)
        val useSSL = getSSL(wsUri)
        val wsOptions = HttpClientOptions().apply {
            // 60s timeout based on pings from every 50s (both directions)
            idleTimeout = 60
            connectTimeout = 10_000
            defaultHost = wsUri.host
            defaultPort = getWebsocketPort(wsUri)
            maxWebsocketFrameSize = MAX_FRAME_SIZE
            isSsl = useSSL
            isVerifyHost = !sslInsecure
        }
        val wsClient = vertx.createHttpClient(wsOptions)
        val httpOptions = HttpClientOptions().apply {
            isVerifyHost = !sslInsecure
        }
        val httpClient = vertx.createHttpClient(httpOptions)

        connect(webhookUrls, webSocketRelativeUri, wsClient, httpClient, startFuture)
    }

    private fun connect(webhookUrls: List<String>,
                        webSocketRelativeUri: String, wsClient: HttpClient,
                        httpClient: HttpClient, startFuture: Future<Void>? = null) {
        wsClient.websocket(webSocketRelativeUri, { webSocket ->
            var password: String? = getenv(PROXYHOOK_PASSWORD)
            if (password == null) password = ""
            log.info("trying to log in")
            val login = JsonObject()
            login.put(TYPE, LOGIN)
            login.put(PASSWORD, password)
            webSocket.writeTextMessage(login.encode())

            // tries to reconnect in case:
            // - server is restarted
            // - server is still starting
            // - connection breaks because of transient network error
            // TODO OR just die, so that (eg) systemd can restart the process

            val periodicTimer = vertx.setPeriodic(50_000) {
                sendPingFrame(webSocket)
            }
            webSocket.handler { buf: Buffer ->
                handleWebSocket(webhookUrls, buf, webSocket, wsClient, httpClient, startFuture)
            }
            webSocket.closeHandler {
                log.info("websocket closed")
                vertx.cancelTimer(periodicTimer)
                vertx.setTimer(300) {
                    connect(webhookUrls,
                            webSocketRelativeUri, wsClient, httpClient)
                }
            }
            webSocket.exceptionHandler { e ->
                log.error("websocket stream exception", e)
                vertx.cancelTimer(periodicTimer)
                vertx.setTimer(2000) {
                    connect(webhookUrls,
                            webSocketRelativeUri, wsClient, httpClient)
                }
            }
        }) { e ->
            log.error("websocket connection exception", e)
            vertx.setTimer(2000) {
                connect(webhookUrls,
                        webSocketRelativeUri, wsClient, httpClient)
            }
        }
    }

    // TODO too many params
    private fun handleWebSocket(webhookUrls: List<String>, buf: Buffer, webSocket: WebSocket, wsClient: HttpClient, httpClient: HttpClient, startFuture: Future<Void>?) {
        val msg = buf.toJsonObject()
        log.debug("payload: {0}", msg)

        val type = msg.getString(TYPE)
        val messageType = MessageType.valueOf(type)
        when (messageType) {
            MessageType.SUCCESS -> {
                log.info("logged in")
                ready?.complete()
                startFuture?.complete()
            }
            MessageType.FAILED -> {
                webSocket.close()
                wsClient.close()
                startFuture?.fail("login failed")
            }
            MessageType.WEBHOOK -> handleWebhook(webhookUrls, httpClient, msg)
            MessageType.PING -> {
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
                // TODO this might happen if the server is newer than the client
                // should we log a warning and keep going, to be more robust?
                webSocket.close()
                wsClient.close()
                startFuture?.fail("unexpected message type: " + type)
            }
        }
    }

    private fun sendPingFrame(webSocket: WebSocket) {
        // ping frame triggers pong frame (inside vert.x), closes websocket if no data received before idleTimeout in TCPSSLOptions):
        // TODO avoid importing from internal vertx package
        val frame = WebSocketFrameImpl(FrameType.PING, Unpooled.copyLong(System.currentTimeMillis()))
        webSocket.writeFrame(frame)

        // this doesn't work with a simple idle timeout, because sending the PING is considered write activity
        //                JsonObject object = new JsonObject();
        //                object.put(TYPE, PING);
        //                object.put(PING_ID, String.valueOf(System.currentTimeMillis()));
        //                webSocket.writeTextMessage(object.encode());
    }

    private fun checkURI(uri: String) {
        val hostname = parseUri(uri).host
        try {
            // ignoring result:
            InetAddress.getByName(hostname)
        } catch (e: UnknownHostException) {
            throw StartupException("Unable to resolve URI " + uri + ": " + e.message)
        }
    }

    private fun handleWebhook(webhookUrls: List<String>, client: HttpClient, msg: JsonObject) {
        log.info("Webhook received: {0}", msg)
        // TODO use host, path and/or query from webSocket message?
        //                String path = msg.getString("path");
        //                String query = msg.getString("query");
        val headerPairs = msg.getJsonArray(HEADERS)
        val headers = jsonToMultiMap(headerPairs)

        EVENT_ID_HEADERS
                .filter { headers.contains(it) }
                .forEach { log.info("Webhook header {0}: {1}", it, headers.getAll(it)) }

        val bufferText = msg.getString(BUFFER_TEXT)
        val buffer = msg.getBinary(BUFFER)
        //                log.debug("buffer: "+ Arrays.toString(buffer));

        for (webhookUri in webhookUrls) {
            val request = client.postAbs(webhookUri) { response ->
                logWebhookResponse(webhookUri, response)
            }
            //                request.putHeader("content-type", "text/plain")
            // some headers break things (eg Host), so we use a whitelist
            //            request.headers().addAll(headers);
            copyWebhookHeaders(headers, request.headers())
            if (bufferText != null) {
                request.end(Buffer.buffer(bufferText))
            } else {
                request.end(Buffer.buffer(buffer))
            }
        }
    }

    private fun logWebhookResponse(webhookUri: String, response: HttpClientResponse) {
        log.info("Webhook POSTed to URL: " + webhookUri)
        log.info("Webhook POST response status: " + response.statusCode() + " " + response.statusMessage())
        log.info("Webhook POST response headers: " + multiMapToJson(response.headers()))
    }

    private fun copyWebhookHeaders(fromHeaders: MultiMap, toHeaders: MultiMap) {
        for (header in fromHeaders.names()) {
            if (HEADERS_TO_COPY.contains(header.toLowerCase())) {
                toHeaders.set(header, fromHeaders.getAll(header))
            }
        }
    }

    private fun getRelativeUri(uri: URI): String {
        return uri.rawPath + if (uri.query == null) "" else "?" + uri.query
    }

    private fun getSSL(wsUri: URI): Boolean {
        val protocol = wsUri.scheme
        when (protocol) {
            "ws" -> return false
            "wss" -> return true
            else -> throw StartupException("expected URI with ws: or wss: : " + wsUri)
        }
    }

    private fun getWebsocketPort(wsUri: URI): Int {
        val port = wsUri.port
        if (port == -1) {
            val protocol = wsUri.scheme
            when (protocol) {
                "ws" -> return 80
                "wss" -> return 443
                else -> throw StartupException("expected URI with ws: or wss: : " + wsUri)
            }
        }
        return port
    }

    private fun parseUri(uri: String): URI {
        try {
            return URI(uri)
        } catch (e: URISyntaxException) {
            throw StartupException("Invalid URI: " + uri)
        }
    }

}
