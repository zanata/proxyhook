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
    fun deliverProxiedWebhook() {
        // this future will succeed if the test passes,
        // or fail if something goes wrong.
        val testFinished = CompletableFuture<Unit>()

        val serverPort = Future.future<Int>()
        server.deployVerticle(ProxyHookServer(0, serverPort))

        val webhookPort = Future.future<Int>()
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
        }.listen(0) { // listen on random port
            if (it.failed()) {
                webhookPort.fail(it.cause())
            } else {
                val actualPort = it.result().actualPort()
                log.info("webhook receiver ready on port $actualPort")
                webhookPort.complete(actualPort)
            }
        }

        CompositeFuture.all(serverPort, webhookPort).compose<Unit> {
            // wait for proxyhook server and webhook receiver before starting client
            if (it.failed()) testFinished.completeExceptionally(it.cause())
            val websocketUrl = "ws://localhost:${serverPort.result()}/listen"
            val receiveUrl = "http://localhost:${webhookPort.result()}/"

            log.info("deploying client")
            val clientReady = Future.future<Unit>()
            client.deployVerticle(ProxyHookClient(clientReady, websocketUrl, receiveUrl)) {
                if (it.failed()) {
                    testFinished.completeExceptionally(it.cause())
                }
            }
            clientReady
        }.setHandler {
            // wait for client login before sending webhook to proxyhook server
            if (it.failed()) {
                testFinished.completeExceptionally(it.cause())
            } else {
                log.info("client ready, preparing to POST webhook")
                val postUrl = "http://localhost:${serverPort.result()}/webhook"

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

        testFinished.get(TEST_TIMEOUT_MS, MILLISECONDS)
    }

}
