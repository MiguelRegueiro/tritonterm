package com.tritonterm.android.terminal

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Test

class ArchGuestNetworkDnsTest {
    @Test
    fun excludesIpv6DnsWhenTheActiveNetworkHasNoIpv6DefaultRoute() {
        val servers = listOf(
            InetAddress.getByName("192.0.2.53"),
            InetAddress.getByName("2001:db8::53"),
        )

        assertEquals(listOf("192.0.2.53"), ArchGuestNetworkDns.usableServers(servers, false))
        assertEquals(
            listOf("192.0.2.53", "2001:db8:0:0:0:0:0:53"),
            ArchGuestNetworkDns.usableServers(servers, true),
        )
    }

    @Test
    fun keepsValidatedUniqueSearchDomainsInResolverSafeForm() {
        assertEquals(
            listOf("home-example.ts.net", "lan"),
            ArchGuestNetworkDns.validatedSearchDomains(
                " home-example.ts.net. home-example.ts.net bad;nameserver-1.2.3.4 lan ",
            ),
        )
    }
}
