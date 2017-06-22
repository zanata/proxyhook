package org.flanigan.proxyhook.client

import io.vertx.core.CompositeFuture
import io.vertx.core.Future
import io.vertx.core.Vertx
import io.vertx.core.logging.LoggerFactory
import io.vertx.ext.unit.junit.VertxUnitRunner
import org.asynchttpclient.DefaultAsyncHttpClient
import org.flanigan.proxyhook.server.ProxyHookServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit.MILLISECONDS

@Suppress("INTERFACE_STATIC_METHOD_CALL_FROM_JAVA6_TARGET")
@RunWith(VertxUnitRunner::class)
class IntegrationTest {

    companion object {
        private val log = LoggerFactory.getLogger(IntegrationTest::class.java)
        private const val TEST_TIMEOUT_MS = 60_000L
        private const val webhookPayload = "This is the webhook payload."
    }

    // we use separate vert.x instances for each simulated tier
    // (in production they would be separate machines)
    lateinit private var server: Vertx
    lateinit private var webhook: Vertx
    lateinit private var client: Vertx

    @Before
    fun before() {
        server = Vertx.vertx()
        webhook = Vertx.vertx()
        client = Vertx.vertx()
    }

    @After
    fun after() {
        // to minimise shutdown errors:
        // 1. we want the client to stop delivering before the webhook receiver stops
        // 2. we want the client to disconnect from server before server stops
        client.close()
        webhook.close()
        server.close()
    }

    @Test
    fun deliverProxiedWebhook2() {
        deliverProxiedWebhook()
    }
    @Test
    fun deliverProxiedWebhook3() {
        deliverProxiedWebhook()
    }
    @Test
    fun deliverProxiedWebhook4() {
        deliverProxiedWebhook()
    }
    @Test
    fun deliverProxiedWebhook5() {
        deliverProxiedWebhook()
    }
    @Test
    fun deliverProxiedWebhook6() {
        deliverProxiedWebhook()
    }
    @Test
    fun deliverProxiedWebhook7() {
        deliverProxiedWebhook()
    }
    @Test
    fun deliverProxiedWebhook8() {
        deliverProxiedWebhook()
    }
    @Test
    fun deliverProxiedWebhook9() {
        deliverProxiedWebhook()
    }
    @Test
    fun deliverProxiedWebhook10() {
        deliverProxiedWebhook()
    }
    @Test
    fun deliverProxiedWebhook11() {
        deliverProxiedWebhook()
    }
    @Test
    fun deliverProxiedWebhook() {
        // this future will succeed if the test passes,
        // or fail if something goes wrong.
        val testFinished = CompletableFuture<Unit>()

        val serverPort = chooseRandomPort()
        val serverReady = Future.future<String>()
        server.deployVerticle(ProxyHookServer(serverPort), serverReady.completer())
        val postUrl = "http://localhost:$serverPort/webhook"
        val websocketUrl = "ws://localhost:$serverPort/listen"

        val webhookPort = Future.future<Int>()

        val clientReady = Future.future<Unit>()

        // wait for proxyhook server and webhook receiver before starting client
        CompositeFuture.all(serverReady, webhookPort).setHandler {
            if (it.failed()) testFinished.completeExceptionally(it.cause())
            val receiveUrl = "http://localhost:${webhookPort.result()}/"

            client.deployVerticle(ProxyHookClient(clientReady, websocketUrl, receiveUrl)) {
                if (it.failed()) {
                    testFinished.completeExceptionally(it.cause())
                }
            }
        }
        // wait for client login before sending webhook to proxyhook server
        clientReady.setHandler {
            if (it.failed()) {
                testFinished.completeExceptionally(it.cause())
            } else {
                log.info("client ready, preparing to POST webhook")

                val httpClient = DefaultAsyncHttpClient()
                httpClient.preparePost(postUrl)
                        .setBody(webhookPayload)
                        .execute()
                        .toCompletableFuture()
                        .whenComplete { response, e ->
                            if (e != null) {
                                testFinished.completeExceptionally(e)
                            } else if (response != null && response.statusCode != 200) {
                                testFinished.completeExceptionally(AssertionError("bad response ${response.statusCode}: ${response.responseBody}"))
                            }
                        }
            }
        }
        webhook.createHttpServer().requestHandler { req ->
            req.response().statusCode = 200
            req.response().end()

            req.bodyHandler { buffer ->
                val payload = buffer.toString(Charsets.UTF_8)
                if (payload == webhookPayload) {
                    // we received the proxy webhook with its payload, so the test has passed:
                    testFinished.complete(Unit)
                } else {
                    testFinished.completeExceptionally(AssertionError("wrong payload: $payload"))
                }
            }

        }.listen(0) {
            if (it.failed()) webhookPort.fail(it.cause())
            else webhookPort.complete(it.result().actualPort())
        }

        testFinished.get(TEST_TIMEOUT_MS, MILLISECONDS)
    }

    /**
     * Warning: the port might be taken by another process before the caller gets a chance to use it.
     */
    fun chooseRandomPort(): Int {
        ServerSocket(0).use { return it.localPort }
    }
}
