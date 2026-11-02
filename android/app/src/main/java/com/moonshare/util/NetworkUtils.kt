package com.moonshare.util

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections

data class LocalAddress(
    val address: InetAddress,
    val interfaceName: String,
    val interfaceLabel: String,
    val prefixLength: Int
) {
    val isIpv6: Boolean get() = address is Inet6Address
    val isLinkLocal: Boolean get() = address.isLinkLocalAddress

    /** Human-friendly endpoint. Link-local IPv6 keeps the interface name for the phone UI. */
    fun display(): String {
        val host = address.hostAddress ?: return "?"
        return if (isIpv6 && isLinkLocal) "$host%$interfaceName" else host
    }

    fun hostForPc(): String {
        val host = address.hostAddress ?: return ""
        return host.substringBefore('%')
    }
}

object NetworkUtils {

    fun localAddresses(): List<LocalAddress> {
        val result = ArrayList<LocalAddress>()
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (nif in interfaces) {
                if (!nif.isUp || nif.isLoopback) continue
                val label = interfaceLabel(nif.name)
                for (address in Collections.list(nif.inetAddresses)) {
                    if (address.isLoopbackAddress || address.isMulticastAddress) continue
                    if (address !is Inet4Address && address !is Inet6Address) continue
                    val prefix = try {
                        nif.interfaceAddresses.firstOrNull { it.address == address }?.networkPrefixLength?.toInt() ?: -1
                    } catch (_: Exception) {
                        -1
                    }
                    result.add(LocalAddress(address, nif.name, label, prefix))
                }
            }
        } catch (_: Exception) {
            // Fall through with whatever was collected.
        }

        return result.sortedWith(
            compareBy(
                { if (it.address is Inet4Address) 0 else 1 },
                { if (it.isLinkLocal) 1 else 0 },
                { it.interfaceName },
                { it.address.hostAddress ?: "" }
            )
        )
    }

    fun ipv4Addresses(): List<LocalAddress> = localAddresses().filter { it.address is Inet4Address }

    fun ipv6Addresses(): List<LocalAddress> = localAddresses().filter { it.address is Inet6Address }

    fun displayLines(): String {
        val addresses = localAddresses()
        if (addresses.isEmpty()) return "无可用局域网地址"
        return addresses.joinToString("\n") { "${it.display()}  ·  ${it.interfaceLabel}" }
    }

    fun discoveryIps(): List<String> = localAddresses().map { it.hostForPc() }.filter { it.isNotEmpty() }.distinct()

    private fun interfaceLabel(name: String): String = when {
        name.startsWith("wlan") -> "Wi-Fi"
        name.startsWith("eth") -> "以太网"
        name.startsWith("rmnet") || name.startsWith("ccmni") -> "蜂窝网络"
        name.startsWith("tun") || name.startsWith("ppp") -> "VPN"
        name.startsWith("ap") -> "热点"
        else -> name
    }
}
