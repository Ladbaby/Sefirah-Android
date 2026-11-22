package sefirah.domain.model

import sefirah.domain.util.AddressUtils

data class PairedDevice(
    override val deviceId: String,
    override val deviceName: String,
    val certificate: ByteArray,
    val addresses: List<AddressEntry> = emptyList(),
    val avatar: String? = null,
    val lastConnected: Long? = null,
    val address: String? = null,
    val port: Int? = null,
    val connectionState: ConnectionState = ConnectionState.Disconnected(),
) : BaseRemoteDevice() {

    /**
     * Returns the addresses to attempt, most likely first.
     *
     * Ordering matters more than it looks: the desktop advertises one IP per adapter, and the phone
     * accumulates one IP per subnet it has ever joined. Trying them in insertion order means every
     * reconnect starts with the oldest, deadest entry and can burn the whole attempt budget there.
     *
     * When the user has explicitly enabled addresses we honour their order and nothing else.
     * Otherwise we rank by usefulness: same subnet as this device first, then user priority, then
     * most recently observed.
     *
     * @param localAddresses this device's own IPs, used to detect a shared subnet.
     */
    fun getAddressesToTry(localAddresses: Collection<String> = emptyList()): List<String> {
        val enabled = addresses.filter { it.isEnabled }.sortedBy { it.priority }
        if (enabled.isNotEmpty()) return enabled.map { it.address }

        return addresses.sortedWith(
            compareByDescending<AddressEntry> { AddressUtils.isLikelyReachable(it.address, localAddresses) }
                .thenBy { it.priority }
                .thenByDescending { it.lastSeen }
        ).map { it.address }
    }

    override fun equals(other: Any?): Boolean = when {
        this === other -> true
        other !is PairedDevice -> false
        else -> deviceId == other.deviceId && deviceName == other.deviceName &&
            address == other.address && addresses == other.addresses && avatar == other.avatar &&
            lastConnected == other.lastConnected && connectionState == other.connectionState &&
            port == other.port && (certificate.contentEquals(other.certificate))
    }

    override fun hashCode(): Int {
        var result = deviceId.hashCode()
        result = 31 * result + deviceName.hashCode()
        result = 31 * result + (address?.hashCode() ?: 0)
        result = 31 * result + addresses.hashCode()
        result = 31 * result + (avatar?.hashCode() ?: 0)
        result = 31 * result + (lastConnected?.hashCode() ?: 0)
        result = 31 * result + connectionState.hashCode()
        result = 31 * result + (port ?: 0)
        result = 31 * result + certificate.contentHashCode()
        return result
    }
}
