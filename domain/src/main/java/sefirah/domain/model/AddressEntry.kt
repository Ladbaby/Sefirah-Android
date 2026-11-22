package sefirah.domain.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable

@Parcelize
@Serializable
data class AddressEntry(
    val address: String,
    val isEnabled: Boolean = false,
    val priority: Int = 0,
    /** Wall clock time this address was last observed to be reachable, 0 if never. */
    val lastSeen: Long = 0L,
) : Parcelable
