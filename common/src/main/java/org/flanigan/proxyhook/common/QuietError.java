package org.flanigan.proxyhook.common;

import java.io.PrintStream;
import java.io.PrintWriter;

// UGLY HACK
class QuietError extends Error {

    @Override
    public synchronized Throwable fillInStackTrace() {
        return this;
    }

    @Override
    public StackTraceElement[] getStackTrace() {
        return new StackTraceElement[0];
    }

    @Override
    public void printStackTrace() {
    }

    @Override
    public void printStackTrace(PrintStream s) {
    }

    @Override
    public void printStackTrace(PrintWriter s) {
    }
}
