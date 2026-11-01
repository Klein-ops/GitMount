package com.kleinops.gitmount.service

import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket

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

    /**
     * 端口占用预检：尝试 bind 指定端口，成功则立即释放并返回 true；
     * 被占用/无权限时返回 false。用于启动前与保存设置时给出明确提示，
     * 避免「启动失败原因只藏在通知/日志里」。
     * @param bindAddress null = 所有网卡；"127.0.0.1" = 仅回环
     */
    fun portAvailable(port: Int, bindAddress: String?): Boolean {
        if (port !in 1..65535) return false
        return try {
            ServerSocket().use { ss ->
                ss.reuseAddress = true
                if (bindAddress == null) {
                    ss.bind(InetSocketAddress(port))
                } else {
                    ss.bind(InetSocketAddress(bindAddress, port))
                }
            }
            true
        } catch (_: Exception) {
            false
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