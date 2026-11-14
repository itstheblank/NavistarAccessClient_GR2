package com.example.navistaraccessclient.iot

import android.content.Context
import android.net.ConnectivityManager
import java.net.Inet4Address

object NetworkUtils {
    /** IPv4 của mạng đang dùng (gửi trong healthcheck.IpAddress). Cần quyền ACCESS_NETWORK_STATE (đã có). */
    fun currentIpv4(context: Context): String? = try {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val lp = cm?.getLinkProperties(cm.activeNetwork)
        lp?.linkAddresses
            ?.map { it.address }
            ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
            ?.hostAddress
    } catch (e: Exception) { null }
}
