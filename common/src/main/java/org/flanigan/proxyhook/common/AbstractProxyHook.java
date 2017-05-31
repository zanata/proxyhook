package org.flanigan.proxyhook.common;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;

public abstract class AbstractProxyHook extends AbstractVerticle {
    private static final Logger log = LoggerFactory.getLogger(AbstractProxyHook.class);

    protected static final List<String> EVENT_ID_HEADERS =
            Collections.unmodifiableList(Arrays.asList(
                    "X-GitHub-Event", "X-Gitlab-Event",
                    "X-Correlation-ID", "X-GitHub-Delivery"));

    /**
     * Exits after logging the specified error message
     * @param s error message
     * @return nothing; does not return (generics trick from https://stackoverflow.com/a/15019663/14379)
     */
    protected <T> T die(String s) {
        log.fatal(s);
        throw startShutdown();
    }

    /**
     * Exits after logging the specified throwable
     * @param t throwable to log
     * @return nothing; does not return (generics trick from https://stackoverflow.com/a/15019663/14379)
     */
    protected <T> T die(Throwable t) {
        log.fatal("dying", t);
        throw startShutdown();
    }

    private Error startShutdown() {
        log.info("Shutting down");
        vertx.close(e -> System.exit(1));
        vertx.setTimer(3_000, timer -> System.exit(2));
        // throw an error which won't be logged
        throw new QuietError();
    }

}
