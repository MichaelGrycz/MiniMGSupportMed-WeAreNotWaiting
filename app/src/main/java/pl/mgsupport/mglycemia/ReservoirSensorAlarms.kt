package pl.mgsupport.mglycemia

/**
 * Czysta (bez Androida) logika progów dla alarmów zbiornika / sensora / łącza.
 *
 * Źródło danych (tylko do odczytu, ten plik niczego nie pobiera):
 * - zbiornik: [pl.mgsupport.mglycemia.core.ble.read.IddStatusRecord.reservoirRemainingIu],
 * - wiek sensora: `CgmMeasurement.timeOffsetMinutes / 60.0` (minuty od startu sesji;
 *   fazę liczy `CgmReader.sensorPhaseFor`). Pompa nie podaje absolutnego startu sesji
 *   ani maksymalnego czasu życia, a `HistoryLogRecord.pumpTimeSeconds` to względny
 *   offset bez kotwicy i `ReadingStore` nie przechowuje startu sesji — wywołujący musi
 *   wyliczyć wiek (np. maksymalny offset z sesji albo czas od pierwszego pakietu sesji)
 *   i przekazać go jako [shouldWarnSensorExpiry]. Brak wieku (`null`) = brak alarmu.
 *
 * Założenie dla sensora: czas życia różni się per model i nie jest podawany przez pompę w tej
 * charakterystyce. Guardian 4 / Guardian Connect / Simplera to zwykle 7 dni, Enlite ~6 dni, a
 * sensory noszone dłużej (extended wear) potrafią pracować 10-14 dni. Dlatego limit jest
 * parametrem ([shouldWarnSensorExpiry]) z domyślną wartością [DEFAULT_SENSOR_MAX_AGE_HOURS],
 * a nie stałą prawdą. Autorytatywnym źródłem jest annuncjacja pompy `CHANGE_SENSOR`
 * ([pl.mgsupport.mglycemia.core.ble.read.SensorMessageState]) — gdy pompa sama woła o wymianę,
 * żaden wiek nie jest potrzebny.
 */
object ReservoirSensorAlarms {

    /** Domyślny próg zbiornika w jednostkach insuliny. */
    const val DEFAULT_RESERVOIR_THRESHOLD_IU = 20.0

    /** Dopuszczalny zakres konfigurowalnego progu zbiornika (IU). */
    const val MIN_RESERVOIR_THRESHOLD_IU = 5.0
    const val MAX_RESERVOIR_THRESHOLD_IU = 50.0

    /**
     * Domyślny maksymalny czas życia sensora w godzinach (Guardian 4 / Simplera, 7 dni).
     * Tylko wartość startowa — użytkownik może podać własną dla dłużej noszonych sensorów.
     */
    const val DEFAULT_SENSOR_MAX_AGE_HOURS = 168.0

    /** Zgodność wsteczna: historyczna nazwa domyślnego limitu. */
    const val SENSOR_MAX_AGE_HOURS = DEFAULT_SENSOR_MAX_AGE_HOURS

    /** Dopuszczalny zakres limitu życia sensora (1-14 dni). */
    const val MIN_SENSOR_MAX_AGE_HOURS = 24.0
    const val MAX_SENSOR_MAX_AGE_HOURS = 336.0

    /** Znane czasy życia sensorów (godziny) — punkt startowy dla ustawienia, nie twarda prawda. */
    val KNOWN_SENSOR_MAX_AGE_HOURS =
        linkedMapOf(
            "Guardian 4 / Simplera" to 168.0,
            "Guardian Connect" to 168.0,
            "Enlite" to 144.0,
            "Extended wear" to 336.0,
        )

    /** Domyślne wyprzedzenie ostrzeżenia o końcu sensora (48 h przed 168 h). */
    const val DEFAULT_SENSOR_WARN_BEFORE_HOURS = 48.0

    /** Dopuszczalny zakres timeoutu braku łącza w minutach. */
    const val MIN_STALE_LINK_TIMEOUT_MIN = 30L
    const val MAX_STALE_LINK_TIMEOUT_MIN = 45L

    /** Domyślny timeout braku łącza w minutach. */
    const val DEFAULT_STALE_LINK_TIMEOUT_MIN = 30L

    /** Wcina próg zbiornika do [MIN_RESERVOIR_THRESHOLD_IU]..[MAX_RESERVOIR_THRESHOLD_IU]. */
    fun coerceReservoirThreshold(thresholdIu: Double): Double =
        thresholdIu.coerceIn(MIN_RESERVOIR_THRESHOLD_IU, MAX_RESERVOIR_THRESHOLD_IU)

    /** Wcina timeout braku łącza do [MIN_STALE_LINK_TIMEOUT_MIN]..[MAX_STALE_LINK_TIMEOUT_MIN]. */
    fun coerceStaleLinkTimeout(timeoutMin: Long): Long =
        timeoutMin.coerceIn(MIN_STALE_LINK_TIMEOUT_MIN, MAX_STALE_LINK_TIMEOUT_MIN)

    /**
     * Alarm niskiego zbiornika, gdy pozostało <= próg. `null`/nie-liczba = brak alarmu.
     *
     * Granica jest inkluzywna: `remaining == threshold` alarmuje.
     */
    fun shouldAlarmReservoir(remainingIu: Double?, thresholdIu: Double): Boolean {
        if (remainingIu == null || !remainingIu.isFinite()) return false
        if (!thresholdIu.isFinite()) return false
        return remainingIu <= thresholdIu
    }

    /** Wcina limit życia sensora do [MIN_SENSOR_MAX_AGE_HOURS]..[MAX_SENSOR_MAX_AGE_HOURS]. */
    fun coerceSensorMaxAgeHours(hours: Double): Double =
        if (!hours.isFinite()) DEFAULT_SENSOR_MAX_AGE_HOURS
        else hours.coerceIn(MIN_SENSOR_MAX_AGE_HOURS, MAX_SENSOR_MAX_AGE_HOURS)

    /**
     * Ostrzeżenie o końcu sensora dla domyślnego limitu [DEFAULT_SENSOR_MAX_AGE_HOURS].
     * Preferuj przeciążenie z jawnym [maxAgeHours], gdy znany jest model sensora.
     */
    fun shouldWarnSensorExpiry(ageHours: Double?, warnBeforeHours: Double): Boolean =
        shouldWarnSensorExpiry(ageHours, DEFAULT_SENSOR_MAX_AGE_HOURS, warnBeforeHours)

    /**
     * Ostrzeżenie o końcu sensora, gdy wiek >= ([maxAgeHours] - [warnBeforeHours]).
     *
     * Granica inkluzywna. `null`/nie-liczba/ujemny wiek, nie-liczba lub niepozytywny limit albo
     * [warnBeforeHours] >= [maxAgeHours] = brak alarmu (nie zgadujemy).
     */
    fun shouldWarnSensorExpiry(
        ageHours: Double?,
        maxAgeHours: Double,
        warnBeforeHours: Double,
    ): Boolean {
        if (ageHours == null || !ageHours.isFinite() || ageHours < 0.0) return false
        if (!maxAgeHours.isFinite() || maxAgeHours <= 0.0) return false
        if (!warnBeforeHours.isFinite() || warnBeforeHours <= 0.0) return false
        if (warnBeforeHours >= maxAgeHours) return false
        return ageHours >= maxAgeHours - warnBeforeHours
    }

    /**
     * Alarm zerwanego łącza, gdy od ostatniego pakietu minęło >= [timeoutMin] minut.
     * `null` = brak alarmu. Ujemna różnica czasu (skos zegara) = brak alarmu.
     * Granica inkluzywna: dokładnie [timeoutMin] minut już alarmuje.
     */
    fun shouldAlarmStaleLink(lastPacketMs: Long?, nowMs: Long, timeoutMin: Long): Boolean {
        if (lastPacketMs == null) return false
        if (timeoutMin <= 0L) return false
        val elapsedMs = nowMs - lastPacketMs
        if (elapsedMs < 0L) return false
        return elapsedMs >= timeoutMin * 60_000L
    }
}
