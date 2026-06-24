package com.cybercat.simpleftp

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkAddress {
    fun localWifiIpv4Address(context: Context): String? {
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
        return connectivityManager?.wifiIpv4Address() ?: localWifiInterfaceIpv4Address()
    }

    @Suppress("DEPRECATION")
    private fun ConnectivityManager.wifiIpv4Address(): String? = allNetworks
        .asSequence()
        .filter { network ->
            getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
        .mapNotNull { getLinkProperties(it) }
        .flatMap { linkProperties -> linkProperties.linkAddresses.asSequence() }
        .map { linkAddress -> linkAddress.address }
        .filterIsInstance<Inet4Address>()
        .firstOrNull(::isUsableIpv4)
        ?.hostAddress

    private fun localWifiInterfaceIpv4Address(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces()
            .asSequence()
            .filter { networkInterface ->
                networkInterface.isUp &&
                    !networkInterface.isLoopback &&
                    networkInterface.name.startsWith("wlan", ignoreCase = true)
            }
            .flatMap { networkInterface -> networkInterface.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull(::isUsableIpv4)
            ?.hostAddress
    }.getOrNull()

    private fun isUsableIpv4(address: Inet4Address): Boolean =
        !address.isLoopbackAddress && !address.isLinkLocalAddress
}
