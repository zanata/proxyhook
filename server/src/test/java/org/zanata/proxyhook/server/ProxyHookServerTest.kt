package org.zanata.proxyhook.server

import org.assertj.core.api.KotlinAssertions.assertThat
import org.junit.Before
import org.junit.Test

/**
 * @author Sean Flanigan [sflaniga@redhat.com](mailto:sflaniga@redhat.com)
 */
class ProxyHookServerTest {
    private lateinit var proxyHookServer: ProxyHookServer

    @Before
    fun setup() {
        this.proxyHookServer = ProxyHookServer(0)
    }

    @Test
    fun describe0() {
        val desc = ProxyHookServer.describe(0)
        assertThat(desc).isEqualTo("0 listeners")
    }

    @Test
    fun describe1() {
        val desc = ProxyHookServer.describe(1)
        assertThat(desc).isEqualTo("1 listener")
    }

    @Test
    fun describe2() {
        val desc = ProxyHookServer.describe(2)
        assertThat(desc).isEqualTo("2 listeners")
    }

    @Test
    fun testTreatAsUtf8() {
        assertThat(proxyHookServer.treatAsUTF8("application/json")).isEqualTo(true)
        assertThat(proxyHookServer.treatAsUTF8("application/xml; charset=utf8")).isEqualTo(true)
        assertThat(proxyHookServer.treatAsUTF8("application/xml; charset=utf-8")).isEqualTo(true)
        assertThat(proxyHookServer.treatAsUTF8("application/xml; charset=ASCII")).isEqualTo(true)
        assertThat(proxyHookServer.treatAsUTF8("application/xml; charset=iso8859-1")).isEqualTo(false)
    }

}
