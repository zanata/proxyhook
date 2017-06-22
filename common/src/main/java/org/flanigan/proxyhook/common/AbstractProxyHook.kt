package org.flanigan.proxyhook.common

import io.vertx.core.AbstractVerticle
import io.vertx.core.logging.LoggerFactory

/**
 * @author Sean Flanigan [sflaniga@redhat.com](mailto:sflaniga@redhat.com)
 */
abstract class AbstractProxyHook : AbstractVerticle() {

    protected fun startShutdown(): Nothing {
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
