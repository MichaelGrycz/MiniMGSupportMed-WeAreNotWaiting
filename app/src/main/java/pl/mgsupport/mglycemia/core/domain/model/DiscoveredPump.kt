package pl.mgsupport.mglycemia.core.domain.model

/**
 * A Tandem pump discovered during BLE scanning.
 */
data class DiscoveredPump(
    val name: String,
    val address: String,
    val rssi: Int,
)
