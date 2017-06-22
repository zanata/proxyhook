package org.flanigan.proxyhook.common

import io.vertx.core.AbstractVerticle
import io.vertx.core.logging.LoggerFactory
import kotlin.system.exitProcess

/**
 * @author Sean Flanigan [sflaniga@redhat.com](mailto:sflaniga@redhat.com)
 */
abstract class AbstractProxyHook : AbstractVerticle() {

    companion object {
        private val log = LoggerFactory.getLogger(AbstractProxyHook::class.java)

        @JvmField
        val EVENT_ID_HEADERS = listOf(
                "X-GitHub-Event", "X-Gitlab-Event",
                "X-Correlation-ID", "X-GitHub-Delivery")

        /**
         * Exits after logging the specified throwable
         * @param t throwable to log
         */
        @JvmStatic
        fun exit(t: Throwable): Nothing {
            log.fatal("dying", t)
            exitProcess(1)
        }

    }

}

class StartupException(message: String? = null, cause: Throwable? = null): Exception(message, cause) {
    constructor(cause: Throwable?) : this(null, cause)

    override fun fillInStackTrace(): Throwable = this
}
