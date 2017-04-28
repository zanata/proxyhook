package org.flanigan.proxyhook.server;

import org.junit.Before;
import org.junit.Test;

import static java.util.Arrays.asList;
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
        String desc = ProxyHookServer.describe(asList());
        assertThat(desc).isEqualTo("0 listeners");
    }

    @Test
    public void describe1() throws Exception {
        String desc = ProxyHookServer.describe(asList("a"));
        assertThat(desc).isEqualTo("1 listener");
    }

    @Test
    public void describe2() throws Exception {
        String desc = ProxyHookServer.describe(asList("a", "b"));
        assertThat(desc).isEqualTo("2 listeners");
    }

}
