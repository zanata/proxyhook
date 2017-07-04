/**
 * @author Sean Flanigan [sflaniga@redhat.com](mailto:sflaniga@redhat.com)
 */
package org.flanigan.proxyhook.common

import io.netty.handler.codec.http.DefaultHttpHeaders
import io.vertx.core.MultiMap
import io.vertx.core.http.impl.HeadersAdaptor
import io.vertx.core.json.JsonArray
import io.vertx.core.logging.LoggerFactory
import kotlin.system.exitProcess

private val log = LoggerFactory.getLogger("org.flanigan.proxyhook.common")

/**
 * Exits after logging the specified throwable
 * @param t throwable to log
 */
fun exit(t: Throwable): Nothing {
    log.fatal("dying", t)
    exitProcess(1)
}

fun jsonToMultiMap(pairs: JsonArray): MultiMap {
    val map = HeadersAdaptor(DefaultHttpHeaders())
    pairs.forEach { pair ->
        val p = pair as JsonArray
        map.add(p.getString(0), p.getString(1))
    }
    return map
}

fun multiMapToJson(headers: MultiMap): JsonArray {
    val headerList = JsonArray()
    headers.forEach { entry -> headerList.add(JsonArray().add(entry.key).add(entry.value)) }
    return headerList
}

// NB vertex will pass these over the wire as strings, not ordinals
enum class MessageType {
    LOGIN, SUCCESS, FAILED,
    PING, PONG,
    WEBHOOK
}

class StartupException(message: String? = null, cause: Throwable? = null): Exception(message, cause) {
    constructor(cause: Throwable?) : this(null, cause)

    override fun fillInStackTrace(): Throwable = this
}
