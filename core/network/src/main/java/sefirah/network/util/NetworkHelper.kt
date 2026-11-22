package sefirah.network.util

import io.ktor.network.sockets.SocketAddress
import io.ktor.network.sockets.toJavaAddress
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketException

object NetworkHelper {
    val localAddress: String?
        get() = getDeviceIpAddress()?.hostAddress

    /** All non-loopback, non-cellular IPv4 addresses currently assigned to this device. */
    val localAddresses: List<String>
        get() = getLocalInterfaceAddresses().map { it.address.hostAddress }

    // rmnet is related to cellular connections or USB tethering mechanisms.
    // See: https://android.googlesource.com/kernel/msm/+/android-msm-flo-3.4-kitkat-mr1/Documentation/usb/gadget_rmnet.txt
    fun getDeviceIpAddress(): InetAddress? {
        return getLocalInterfaceAddresses().firstOrNull()?.address
    }

    /**
     * Enumerates this device's usable IPv4 interface addresses, skipping loopback and `rmnet`
     * (cellular / USB tethering) interfaces. Used both for display and to work out which subnets
     * to broadcast on.
     */
    fun getLocalInterfaceAddresses(): List<InterfaceAddress> {
        val result = mutableListOf<InterfaceAddress>()
        try {
            for (networkInterface in NetworkInterface.getNetworkInterfaces() ?: return result) {
                if (networkInterface.displayName.contains("rmnet")) continue
                if (!networkInterface.isUp || networkInterface.isLoopback) continue

                for (inetAddress in networkInterface.inetAddresses) {
                    if (inetAddress.isLoopbackAddress) continue
                    if (inetAddress !is Inet4Address) continue
                    val prefixLength = runCatching { networkInterface.interfaceAddresses }
                        .getOrNull()
                        ?.firstOrNull { it.address == inetAddress }
                        ?.networkPrefixLength
                        ?: 24.toShort()
                    result += InterfaceAddress(inetAddress, prefixLength.toInt())
                }
            }
        } catch (_: SocketException) {
        }
        return result
    }

    /**
     * Computes the directed broadcast address for every local IPv4 subnet.
     *
     * The limited broadcast (255.255.255.255) only leaves via the default route, which right after
     * joining a Wi-Fi network can still be cellular. Sending to each subnet's directed broadcast as
     * well is what lets discovery work on whatever interface the peer actually shares.
     */
    fun subnetBroadcastAddresses(): List<String> {
        return getLocalInterfaceAddresses().mapNotNull { (inetAddress, prefixLength) ->
            val mask = prefixMask(prefixLength)
            val ip = inetAddress.address
            if (ip.size != 4) return@mapNotNull null
            val broadcast = ByteArray(4) { i -> (ip[i].toInt() or mask[i].toInt().inv()).toByte() }
            // A /32 has no broadcast address, and the all-ones address is already covered.
            if (prefixLength >= 31) null
            else runCatching { InetAddress.getByAddress(broadcast).hostAddress }.getOrNull()
        }.distinct()
    }

    private fun prefixMask(prefixLength: Int): ByteArray {
        val clamped = prefixLength.coerceIn(0, 32)
        return ByteArray(4) { i ->
            val bits = (clamped - i * 8).coerceIn(0, 8)
            (0xFF shl (8 - bits)).toByte()
        }
    }

    /**
     * Extracts the peer's numeric IP from a received datagram.
     *
     * Deliberately avoids [SocketAddress.toString]: Ktor delegates to
     * `java.net.InetSocketAddress.toString()`, which renders as `/192.168.1.5:5149` — storing that
     * and later handing it to `SSLSocketFactory.createSocket` fails name resolution.
     */
    fun senderAddress(address: SocketAddress): String? {
        val javaAddress = runCatching { address.toJavaAddress() }.getOrNull() as? InetSocketAddress
            ?: return null
        val inetAddress = javaAddress.address ?: return null
        if (inetAddress !is Inet4Address) return null
        return inetAddress.hostAddress
    }

    data class InterfaceAddress(val address: InetAddress, val prefixLength: Int)
}
