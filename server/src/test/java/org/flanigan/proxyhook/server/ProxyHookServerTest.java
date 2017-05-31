package org.flanigan.proxyhook.server;

import org.junit.Before;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;


/**
 * @author Sean Flanigan <a href="mailto:sflaniga@redhat.com">sflaniga@redhat.com</a>
 */
@SuppressWarnings("ArraysAsListWithZeroOrOneArgument")
public class ProxyHookServerTest {
    private ProxyHookServer proxyHookServer;

    @Before
    public void setup() {
        this.proxyHookServer = new ProxyHookServer();
    }

    @Test
    public void describe0() throws Exception {
        String desc = ProxyHookServer.describe(0);
        assertThat(desc).isEqualTo("0 listeners");
    }

    @Test
    public void describe1() throws Exception {
        String desc = ProxyHookServer.describe(1);
        assertThat(desc).isEqualTo("1 listener");
    }

    @Test
    public void describe2() throws Exception {
        String desc = ProxyHookServer.describe(2);
        assertThat(desc).isEqualTo("2 listeners");
    }

    @Test
    public void testTreatAsUtf8() throws Exception {
        assertThat(proxyHookServer.treatAsUTF8("application/json")).isEqualTo(true);
        assertThat(proxyHookServer.treatAsUTF8("application/xml; charset=utf8")).isEqualTo(true);
        assertThat(proxyHookServer.treatAsUTF8("application/xml; charset=utf-8")).isEqualTo(true);
        assertThat(proxyHookServer.treatAsUTF8("application/xml; charset=ASCII")).isEqualTo(true);
        assertThat(proxyHookServer.treatAsUTF8("application/xml; charset=iso8859-1")).isEqualTo(false);
    }


}
