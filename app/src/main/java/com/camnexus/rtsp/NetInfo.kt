package com.camnexus.rtsp

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 局域网地址探测：给设置页显示「浏览器打开 http://IP:端口」用。
 *
 * 优先 wlan 接口，其次任意已启用的局域网 IPv4 地址；取不到时返回空串。
 */
object NetInfo {

    fun localIpv4(): String = runCatching {
        val candidates = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback }
            .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
        for (nic in candidates) {
            val address = nic.inetAddresses.toList()
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress && !it.isLoopbackAddress }
            if (address != null) return@runCatching address.hostAddress ?: ""
        }
        ""
    }.getOrDefault("")
}
