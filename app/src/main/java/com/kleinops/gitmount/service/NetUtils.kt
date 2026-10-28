package com.kleinops.gitmount.service

import java.net.Inet4Address
import java.net.NetworkInterface

/** 网络工具：获取可用于局域网访问的本机 IPv4 地址 */
object NetUtils {

    fun lanIpv4(): String? {
        return try {
            NetworkInterface.getNetworkInterfaces()
                .toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .mapNotNull { it.hostAddress }
                .let { list ->
                    // 优先私网地址，其次任意 IPv4
                    list.firstOrNull { isPrivate(it) } ?: list.firstOrNull()
                }
        } catch (e: Exception) {
            null
        }
    }

    private fun isPrivate(ip: String): Boolean {
        if (ip.startsWith("192.168.") || ip.startsWith("10.")) return true
        if (ip.startsWith("172.")) {
            // 172.16.0.0/12
            val second = ip.split(".").getOrNull(1)?.toIntOrNull() ?: return false
            return second in 16..31
        }
        return false
    }
}