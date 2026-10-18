package com.trustmark.share

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

object BiliNetwork {
    fun proxyOrVpnActive(context: Context): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network)
        val vpn = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        val proxy = manager.getLinkProperties(network)?.httpProxy != null || !System.getProperty("http.proxyHost").isNullOrBlank()
        return vpn || proxy
    }
}
