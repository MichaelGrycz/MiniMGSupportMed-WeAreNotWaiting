package pl.mgsupport.mglycemia.core.domain.model

/** Why the pump is not currently reporting a sensor glucose value. */
enum class SensorPhase {
    /** New sensor warm-up: the pump counts up from the session start, typically two hours. */
    INITIALIZING,

    /** Mid-session sensor update or another temporary no-value state. */
    UPDATING,

    /** The pump did not provide enough information to distinguish the two. */
    UNKNOWN,
}
