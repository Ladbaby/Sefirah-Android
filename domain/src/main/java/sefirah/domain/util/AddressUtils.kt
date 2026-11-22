package sefirah.domain.util

import sefirah.domain.model.AddressEntry

/**
 * Helpers for the peer IP addresses that get persisted and retried across network changes.
 *
 * A phone roams between subnets, and the desktop exposes one IP per adapter, so the persisted
 * address list is only useful if it stays small, ordered by usefulness, and free of junk.
 *
 * Everything here is IPv4 only, matching the rest of the app (see NetworkHelper and NsdService,
 * both of which discard IPv6).
 */
object AddressUtils {

    /** Upper bound on persisted addresses, so a reconnect never fans out across dozens of dead subnets. */
    const val MAX_ADDRESSES = 8

    private val IPV4_PATTERN = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

    fun isIpv4(address: String): Boolean =
        IPV4_PATTERN.matches(address) && address.split('.').all { it.toIntOrNull() in 0..255 }

    /**
     * Normalises an address that may have been captured from a socket address [toString] dump,
     * such as "/192.168.1.5:5149" or "[::ffff:192.168.1.5]:5149".
     *
     * @return a bare IPv4 literal, or null when the input isn't one.
     */
    fun normalizeAddress(raw: String?): String? {
        if (raw.isNullOrBlank()) return null

        var value = raw.trim().removePrefix("/")

        // Drop an IPv6 scope id such as "%wlan0".
        value = value.substringBefore('%')

        // Drop a ":port" suffix, handling the "[v6]:port" form.
        value = if (value.startsWith("[")) {
            value.removePrefix("[").substringBefore(']')
        } else if (value.count { it == ':' } == 1) {
            value.substringBefore(':')
        } else {
            value
        }

        // Unwrap IPv4-mapped IPv6 ("::ffff:192.168.1.5") so it compares as plain IPv4.
        val mapped = value.substringAfterLast("::ffff:", missingDelimiterValue = "")
        if (mapped.isNotEmpty()) value = mapped

        return value.takeIf { isIpv4(it) }
    }

    /** True when [address] sits in the same IPv4 /24 as one of this device's own addresses. */
    fun isLikelyReachable(address: String, localAddresses: Collection<String>): Boolean {
        if (!isIpv4(address)) return false
        val prefix = address.substringBeforeLast('.')
        return localAddresses.any { isIpv4(it) && it.substringBeforeLast('.') == prefix }
    }

    /**
     * Merges newly discovered addresses into the persisted list, refreshing "last seen" timestamps
     * and pruning the oldest unconfirmed entries so the list stays bounded.
     */
    fun merge(
        existing: List<AddressEntry>,
        discovered: Collection<String>,
        now: Long = System.currentTimeMillis(),
        maxEntries: Int = MAX_ADDRESSES,
    ): List<AddressEntry> {
        val fresh = discovered.mapNotNull { normalizeAddress(it) }.toSet()
        if (fresh.isEmpty()) return existing

        val merged = existing
            .map { entry -> if (entry.address in fresh) entry.copy(lastSeen = now) else entry }
            .toMutableList()

        val known = merged.mapTo(mutableSetOf()) { it.address }
        fresh.filterNot { it in known }.forEach { address ->
            merged += AddressEntry(
                address = address,
                isEnabled = false,
                priority = merged.size,
                lastSeen = now,
            )
        }

        return prune(merged, maxEntries)
    }

    /**
     * Keeps every user-enabled entry plus the most recently seen of the rest, capped at [maxEntries],
     * preserving the original ordering.
     */
    fun prune(entries: List<AddressEntry>, maxEntries: Int = MAX_ADDRESSES): List<AddressEntry> {
        val (enabled, disabled) = entries.partition { it.isEnabled }
        if (enabled.size >= maxEntries) return enabled

        val kept = enabled.mapTo(mutableSetOf()) { it.address } +
            disabled.sortedByDescending { it.lastSeen }.take(maxEntries - enabled.size).map { it.address }

        return entries.filter { it.address in kept }
    }
}
