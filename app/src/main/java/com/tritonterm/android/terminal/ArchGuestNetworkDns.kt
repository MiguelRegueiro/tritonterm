package com.tritonterm.android.terminal

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import java.net.Inet6Address
import java.net.InetAddress

/** DNS configuration Android assigned to the app's current default network, including VPNs. */
internal object ArchGuestNetworkDns {
    fun environment(context: Context): Array<String> {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val properties = connectivity?.let { manager ->
            manager.activeNetwork?.let(manager::getLinkProperties)
        }
        val dnsServers = properties?.let { usableServers(it) }.orEmpty()
        val searchDomains = properties?.domains?.let(::validatedSearchDomains).orEmpty()
        return arrayOf(
            "APP_DNS_SERVERS=${dnsServers.joinToString(",")}",
            "APP_DNS_SEARCH_DOMAINS=${searchDomains.joinToString(",")}",
        )
    }

    internal fun usableServers(properties: LinkProperties): List<String> {
        val hasIpv6DefaultRoute = properties.routes.any {
            it.destination.prefixLength == 0 && it.destination.address is Inet6Address
        }
        return usableServers(properties.dnsServers, hasIpv6DefaultRoute)
    }

    internal fun usableServers(
        servers: List<InetAddress>,
        hasIpv6DefaultRoute: Boolean,
    ): List<String> = servers.asSequence()
        .filterNot { it is Inet6Address && (!hasIpv6DefaultRoute || it.isLinkLocalAddress) }
        .mapNotNull { it.hostAddress?.substringBefore('%') }
        .distinct()
        .take(2)
        .toList()

    /** Android exposes DNS search domains as a whitespace-separated string. */
    internal fun validatedSearchDomains(raw: String): List<String> {
        val result = mutableListOf<String>()
        var lineLength = "search ".length
        raw.split(Regex("\\s+"))
            .map { it.trim().trimEnd('.').lowercase() }
            .filter { it.isNotEmpty() }
            .forEach { domain ->
                if (domain in result || domain.length > 253) return@forEach
                val valid = domain.split('.').all { label ->
                    label.isNotEmpty() && label.length <= 63 &&
                        label.first().isAsciiAlphaNumeric() && label.last().isAsciiAlphaNumeric() &&
                        label.all { it.isAsciiAlphaNumeric() || it == '-' }
                }
                if (!valid) return@forEach
                val addedLength = domain.length + if (result.isEmpty()) 0 else 1
                if (lineLength + addedLength <= 256) {
                    result += domain
                    lineLength += addedLength
                }
            }
        return result
    }

    private fun Char.isAsciiAlphaNumeric(): Boolean =
        this in 'a'..'z' || this in '0'..'9'
}
