package org.flanigan.proxyhook.common;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;

public abstract class AbstractProxyHook extends AbstractVerticle {
    private static final Logger log = LoggerFactory.getLogger(AbstractProxyHook.class);

    protected void die(String s) {
        log.fatal(s);
        vertx.close(e -> System.exit(1));
        try {
            Thread.sleep(1000L);
        } catch (InterruptedException e) {
            log.warn("interrupted while waiting for Vertx to shut down", e);
        }
        System.exit(2);
    }

    protected void die(Throwable t) {
        log.fatal("dying", t);
        vertx.close(e -> System.exit(1));
        try {
            Thread.sleep(1000L);
        } catch (InterruptedException e) {
            log.warn("interrupted while waiting for Vertx to shut down", e);
        }
        System.exit(2);
    }

}
