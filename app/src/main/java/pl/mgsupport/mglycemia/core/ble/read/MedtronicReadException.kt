/*
 * GlycemicGPT code (GPL-3.0). Failure type for the Medtronic 700-series read layer.
 */
package pl.mgsupport.mglycemia.core.ble.read

import pl.mgsupport.mglycemia.core.domain.model.SensorPhase

/**
 * Thrown when a Medtronic pump payload cannot be turned into a trustworthy domain value: a malformed
 * or truncated frame, a failed E2E-CRC, an unexpected control-point response, or a reading that
 * falls outside the configured [pl.mgsupport.mglycemia.core.domain.pump.SafetyLimits].
 *
 * The read layer **rejects** rather than clamps: a value we cannot fully trust is never silently
 * coerced into range, because a wrong glucose or battery number is worse than a missing one.
 */
class MedtronicReadException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The pump answered the CGM read, but the sensor has no usable value yet (warm-up, sensor update,
 * expired sensor). The pump reports glucose 0 in that state, which [CgmReader] rejects like any other
 * out-of-range value. Callers must treat this as "no reading yet", not as a fault worth showing.
 */
class SensorNotReadyException(
    message: String,
    val phase: SensorPhase = SensorPhase.UNKNOWN,
    /** Minutes until the two-hour warm-up ends, when [phase] is [SensorPhase.INITIALIZING]. */
    val remainingMinutes: Int? = null,
) : Exception(message)
