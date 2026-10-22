package pl.mgsupport.cgm.core.domain.plugin.capabilities

import pl.mgsupport.cgm.core.domain.model.BgmReading
import pl.mgsupport.cgm.core.domain.plugin.PluginCapabilityInterface
import kotlinx.coroutines.flow.Flow

/**
 * Capability for plugins that provide fingerstick blood glucose readings.
 * Examples: Contour Next One, AccuChek Guide.
 */
interface BgmSource : PluginCapabilityInterface {
    /** Observable stream of fingerstick readings as they arrive. */
    fun observeReadings(): Flow<BgmReading>

    /** Get the most recent fingerstick reading. */
    suspend fun getLatestReading(): Result<BgmReading>
}
