package org.flanigan.proxyhook.common

import java.io.PrintStream
import java.io.PrintWriter

/**
 * @author Sean Flanigan [sflaniga@redhat.com](mailto:sflaniga@redhat.com)
 */
// UGLY HACK
internal class QuietError : Error() {

    override fun fillInStackTrace(): Throwable {
        return this
    }

    override fun getStackTrace(): Array<StackTraceElement> {
        return emptyArray()
    }

    override fun printStackTrace() {}

    override fun printStackTrace(s: PrintStream) {}

    override fun printStackTrace(s: PrintWriter) {}
}
