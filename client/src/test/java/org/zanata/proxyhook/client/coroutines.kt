package org.zanata.proxyhook.client

import io.vertx.core.AsyncResult
import io.vertx.core.Future
import io.vertx.core.Handler
import kotlinx.coroutines.experimental.CommonPool
import kotlin.coroutines.experimental.Continuation
import kotlin.coroutines.experimental.CoroutineContext
import kotlin.coroutines.experimental.startCoroutine
import kotlin.coroutines.experimental.suspendCoroutine

/**
 * @author Sean Flanigan <a href="mailto:sflaniga@redhat.com">sflaniga@redhat.com</a>
 */

// https://github.com/Kotlin/kotlin-coroutines/blob/6833648d6d3ddbaf7b31511a635f11fd6c7a3115/kotlin-coroutines-informal.md#wrapping-callbacks
inline suspend fun <T> vx(crossinline callback: (Handler<AsyncResult<T>>) -> Unit) =
        suspendCoroutine<T> { cont ->
            callback(Handler { result: AsyncResult<T> ->
                if (result.succeeded()) {
                    cont.resume(result.result())
                } else {
                    cont.resumeWithException(result.cause())
                }
            })
        }

fun <T> future(context: CoroutineContext = CommonPool, block: suspend () -> T): Future<T> =
        FutureCoroutine<T>(context, Future.future()).also { block.startCoroutine(completion = it) }

fun <T> futureVx(callback: (Handler<AsyncResult<T>>) -> Unit): Future<T> = future {
    vx<T> {
        callback(it)
    }
}

private class FutureCoroutine<T>(override val context: CoroutineContext, f: Future<T>) : Future<T> by f, Continuation<T> {
    override fun resume(value: T) { complete(value) }
    override fun resumeWithException(exception: Throwable) { fail(exception) }
}

suspend fun <T> Future<T>.await(): T =
        suspendCoroutine<T> { cont: Continuation<T> ->
            setHandler { future ->
                if (future.succeeded()) cont.resume(future.result())
                else cont.resumeWithException(future.cause())
            }
        }
