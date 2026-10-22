package pl.mgsupport.mglycemia.core.domain.plugin.capabilities

import pl.mgsupport.mglycemia.core.domain.model.CgmReading
import pl.mgsupport.mglycemia.core.domain.plugin.PluginCapabilityInterface
import kotlinx.coroutines.flow.Flow

/**
 * Capability for plugins that provide continuous glucose readings.
 * Examples: standalone CGMs (Dexcom G7), pumps that stream CGM data (Tandem pumps).
 */
interface GlucoseSource : PluginCapabilityInterface {
    /** Observable stream of glucose readings as they arrive. */
    fun observeReadings(): Flow<CgmReading>

    /** Get the most recent glucose reading on demand. */
    suspend fun getCurrentReading(): Result<CgmReading>
}
