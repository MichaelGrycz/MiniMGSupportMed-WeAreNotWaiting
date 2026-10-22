package pl.mgsupport.cgm.core.domain.plugin.capabilities

import pl.mgsupport.cgm.core.domain.model.BasalReading
import pl.mgsupport.cgm.core.domain.model.BolusEvent
import pl.mgsupport.cgm.core.domain.model.IoBReading
import pl.mgsupport.cgm.core.domain.plugin.PluginCapabilityInterface
import pl.mgsupport.cgm.core.domain.pump.SafetyLimits
import java.time.Instant

/**
 * Capability for plugins that provide insulin delivery data (read-only).
 * Examples: insulin pumps, smart pens.
 */
interface InsulinSource : PluginCapabilityInterface {
    suspend fun getIoB(): Result<IoBReading>
    suspend fun getBasalRate(): Result<BasalReading>
    suspend fun getBolusHistory(
        since: Instant,
        limits: SafetyLimits,
    ): Result<List<BolusEvent>>
}
