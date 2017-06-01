package org.flanigan.proxyhook.common

import io.vertx.core.AbstractVerticle
import io.vertx.core.logging.LoggerFactory

/**
 * @author Sean Flanigan [sflaniga@redhat.com](mailto:sflaniga@redhat.com)
 */
abstract class AbstractProxyHook : AbstractVerticle() {

    /**
     * Exits after logging the specified error message
     * @param s error message
     * *
     * @return nothing; does not return (generics trick from https://stackoverflow.com/a/15019663/14379)
     */
    protected fun <T> die(s: String): T {
        log.fatal(s)
        throw startShutdown()
    }

    /**
     * Exits after logging the specified throwable
     * @param t throwable to log
     * *
     * @return nothing; does not return (generics trick from https://stackoverflow.com/a/15019663/14379)
     */
    protected fun <T> die(t: Throwable): T {
        log.fatal("dying", t)
        throw startShutdown()
    }

    private fun startShutdown(): Error {
        log.info("Shutting down")
        vertx.close { System.exit(1) }
        vertx.setTimer(3000) { System.exit(2) }
        // throw an error which won't be logged
        throw QuietError()
    }

    companion object {
        private val log = LoggerFactory.getLogger(AbstractProxyHook::class.java)

        @JvmField
        val EVENT_ID_HEADERS = listOf(
                "X-GitHub-Event", "X-Gitlab-Event",
                "X-Correlation-ID", "X-GitHub-Delivery")
    }

}
