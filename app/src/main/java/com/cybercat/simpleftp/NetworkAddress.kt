package com.cybercat.simpleftp

import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkAddress {
    fun localIpv4Address(): String? = NetworkInterface.getNetworkInterfaces()
        .asSequence()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { networkInterface ->
            networkInterface.inetAddresses.asSequence()
        }
        .filterIsInstance<Inet4Address>()
        .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
        ?.hostAddress
}
