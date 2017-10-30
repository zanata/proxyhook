package org.zanata.proxyhook.client

import io.vertx.core.AsyncResult
import io.vertx.core.Future
import io.vertx.core.Handler
import io.vertx.kotlin.coroutines.awaitResult
import kotlinx.coroutines.experimental.CommonPool
import kotlin.coroutines.experimental.Continuation
import kotlin.coroutines.experimental.CoroutineContext
import kotlin.coroutines.experimental.startCoroutine

/**
 * @author Sean Flanigan <a href="mailto:sflaniga@redhat.com">sflaniga@redhat.com</a>
 */

// https://github.com/Kotlin/kotlin-coroutines/blob/6833648d6d3ddbaf7b31511a635f11fd6c7a3115/kotlin-coroutines-informal.md#wrapping-callbacks

/**
 * Creates a Vert.x Future for the results of 'block', which will be executed in a coroutine.
 */
fun <T> future(context: CoroutineContext = CommonPool, block: suspend () -> T): Future<T> =
        FutureCoroutine<T>(context, Future.future()).also { block.startCoroutine(completion = it) }

/**
 * Executes the callback in a coroutine, passing it a completion handler. A Vert.x Future for the
 * AsyncResult passed to this handler is returned.
 */
fun <T> futureResult(callback: (Handler<AsyncResult<T>>) -> Unit): Future<T> = future {
    awaitResult<T> {
        callback(it)
    }
}

private class FutureCoroutine<T>(override val context: CoroutineContext, f: Future<T>) : Future<T> by f, Continuation<T> {
    override fun resume(value: T) { complete(value) }
    override fun resumeWithException(exception: Throwable) { fail(exception) }
}
