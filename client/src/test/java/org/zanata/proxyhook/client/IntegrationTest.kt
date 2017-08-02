package org.zanata.proxyhook.client

import io.vertx.core.AsyncResult
import io.vertx.core.Future
import io.vertx.core.Handler
import io.vertx.core.Vertx
import io.vertx.core.http.HttpServer
import io.vertx.core.logging.LoggerFactory
import io.vertx.ext.unit.junit.VertxUnitRunner
import kotlinx.coroutines.experimental.runBlocking
import org.asynchttpclient.DefaultAsyncHttpClient
import org.zanata.proxyhook.server.ProxyHookServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit.MILLISECONDS

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
    fun rootDeployment() {
        deliverProxiedWebhook("")
    }

    @Test
    fun subPathDeployment() {
        deliverProxiedWebhook("/proxyhook")
    }

    private fun deliverProxiedWebhook(prefix: String): Unit = runBlocking {
        // this future will succeed if the test passes,
        // or fail if something goes wrong.
        val testFinished = CompletableFuture<Unit>()

        val (_, serverPort) = startServer(prefix)

        val webhookPort = createWebhookReceiver(testFinished)
        val receiveUrl = "http://localhost:${webhookPort.await()}/"
        val websocketUrl = "ws://localhost:${serverPort.await()}$prefix/listen"
        val postUrl = "http://localhost:${serverPort.await()}$prefix/webhook"
        // wait for proxyhook server and webhook receiver before starting client
        val client = startClient(testFinished, websocketUrl, receiveUrl)

        // wait for client login before sending webhook to proxyhook server
        client.await()
        log.info("client ready, preparing to POST webhook")

        postWebhookToServer(postUrl, testFinished)

        testFinished.get(TEST_TIMEOUT_MS, MILLISECONDS)
    }

    private suspend fun startServer(prefix: String): Pair<Future<String>, Future<Int>> {
        val actualPort = Future.future<Int>()
        val deploymentId = futureVx<String> { it: Handler<AsyncResult<String>> ->
            server.deployVerticle(ProxyHookServer(port = 0, prefix = prefix, actualPort = actualPort), it)
        }
        return deploymentId to actualPort
    }

    private fun createWebhookReceiver(testFinished: CompletableFuture<Unit>): Future<Int> = future {
        val httpServer: HttpServer = vx {
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
            }.listen(0, it) // listen on random port
        }
        val actualPort = httpServer.actualPort()
        log.info("webhook receiver ready on port $actualPort")
        actualPort
    }

    private fun startClient(testFinished: CompletableFuture<Unit>, websocketUrl: String, receiveUrl: String): Future<Unit> {
        log.info("deploying client")
        val clientReady = Future.future<Unit>()
        client.deployVerticle(ProxyHookClient(clientReady, websocketUrl, receiveUrl)) {
            if (it.failed()) {
                testFinished.completeExceptionally(it.cause())
            }
        }
        return clientReady
    }

    private fun postWebhookToServer(postUrl: String, testFinished: CompletableFuture<Unit>) {
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
