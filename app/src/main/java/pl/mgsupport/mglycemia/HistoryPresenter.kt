package pl.mgsupport.mglycemia

import pl.mgsupport.mglycemia.core.ble.messages.BolusActivationType
import pl.mgsupport.mglycemia.core.ble.messages.HistoryBasalDeliveryContext
import pl.mgsupport.mglycemia.core.ble.messages.MedtronicHistoryEvent
import pl.mgsupport.mglycemia.core.ble.messages.MedtronicHistoryEventType
import pl.mgsupport.mglycemia.core.ble.messages.MedtronicHistoryParser
import pl.mgsupport.mglycemia.core.ble.messages.MedtronicHistoryRecord
import pl.mgsupport.mglycemia.core.domain.model.CgmReading
import pl.mgsupport.mglycemia.core.domain.model.HistoryLogRecord
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Base64
import kotlin.math.abs
import kotlin.math.roundToInt

/** Earliest epoch second treated as real wall-clock time (2000-01-01T00:00:00Z). */
private const val MIN_SANE_EPOCH_SECONDS = 946_684_800L

/** Latest epoch second treated as real wall-clock time (2100-01-01T00:00:00Z). */
private const val MAX_SANE_EPOCH_SECONDS = 4_102_444_800L

private const val UNKNOWN_TYPE_LABEL = "Nieznane zdarzenie"

private val LOCAL_TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

/**
 * Renders [HistoryLogRecord] entries as short, single-line descriptions for a UI history list.
 *
 * Timestamps are best-effort: [HistoryLogRecord.pumpTimeSeconds] carries the record's raw relative
 * offset (the pump emits no absolute per-record time), so a local wall-clock time is shown only when
 * that value already lands in a plausible epoch range; otherwise the raw offset is shown as
 * `t+<seconds> s`. Record bodies are decoded through [MedtronicHistoryParser.parseRecordBody]; any
 * failure still yields a line containing the event type id.
 *
 * Read-only presentation helper: every method returns a non-empty string and never throws.
 */
class HistoryPresenter {

    /** One line for [record]: time, sequence number, event type id, Polish label, optional detail. */
    fun describe(record: HistoryLogRecord): String =
        try {
            buildLine(record)
        } catch (e: Exception) {
            fallbackLine(record)
        }

    /** Lines for the newest [max] [records] (sequence number descending). Negative [max] yields none. */
    fun describeAll(records: List<HistoryLogRecord>, max: Int = 30): List<String> =
        records
            .sortedByDescending { it.sequenceNumber }
            .take(max.coerceAtLeast(0))
            .map { describe(it) }

    private fun buildLine(record: HistoryLogRecord): String {
        val typeId = record.eventTypeId and 0xFFFF
        val parsed = parseOrNull(record)
        val type = parsed?.eventType ?: MedtronicHistoryEventType.from(typeId)
        val detail = parsed?.let { eventDetail(it.event) }.orEmpty()
        return buildString {
            append(timeLabel(record.pumpTimeSeconds))
            append(" | #")
            append(record.sequenceNumber)
            append(" | ")
            append(hexId(typeId))
            append(' ')
            append(typeLabel(type))
            if (detail.isNotEmpty()) {
                append(" | ")
                append(detail)
            }
        }
    }

    /** Last-resort line; uses only formatting helpers that cannot throw on any input. */
    private fun fallbackLine(record: HistoryLogRecord): String =
        timeLabel(record.pumpTimeSeconds) +
            " | #" + record.sequenceNumber +
            " | " + hexId(record.eventTypeId) + " " + UNKNOWN_TYPE_LABEL

    private fun parseOrNull(record: HistoryLogRecord): MedtronicHistoryRecord? =
        try {
            val body = Base64.getDecoder().decode(record.rawBytesB64)
            MedtronicHistoryParser.parseRecordBody(body)
        } catch (e: Exception) {
            null
        }

    // -- Time ---------------------------------------------------------------

    private fun timeLabel(pumpTimeSeconds: Long): String {
        if (pumpTimeSeconds in MIN_SANE_EPOCH_SECONDS..MAX_SANE_EPOCH_SECONDS) {
            try {
                val zoned = Instant.ofEpochSecond(pumpTimeSeconds).atZone(ZoneId.systemDefault())
                return LOCAL_TIME_FORMATTER.format(zoned)
            } catch (e: Exception) {
                // Out-of-range epoch for this platform: fall through to the raw offset.
            }
        }
        return offsetLabel(pumpTimeSeconds)
    }

    private fun offsetLabel(seconds: Long): String {
        val sign = if (seconds < 0) "-" else "+"
        return "t$sign${abs(seconds)} s"
    }

    private fun instantLabel(instant: Instant): String =
        try {
            LOCAL_TIME_FORMATTER.format(instant.atZone(ZoneId.systemDefault()))
        } catch (e: Exception) {
            instant.epochSecond.toString()
        }

    // -- Event detail -------------------------------------------------------

    private fun eventDetail(event: MedtronicHistoryEvent): String =
        when (event) {
            is MedtronicHistoryEvent.BolusAmounts -> bolusAmounts(event)
            is MedtronicHistoryEvent.BolusProgrammedDetail -> bolusProgrammedDetail(event)
            is MedtronicHistoryEvent.BolusDeliveredDetail ->
                event.activationType?.let(::activationLabel).orEmpty()
            is MedtronicHistoryEvent.BasalRateChanged -> basalChange(event)
            is MedtronicHistoryEvent.AutoBasalMicroBolus -> "${units(event.amountIu)} U"
            is MedtronicHistoryEvent.SgMeasurement -> sgValue(event.sgValueRaw)
            is MedtronicHistoryEvent.BgReading -> bgReading(event)
            is MedtronicHistoryEvent.InsulinDelivery -> insulinDelivery(event)
            is MedtronicHistoryEvent.Annunciation -> annunciation(event)
            is MedtronicHistoryEvent.ReferenceTime -> event.time?.let(::instantLabel).orEmpty()
            is MedtronicHistoryEvent.Unparsed -> ""
        }

    private fun bolusAmounts(event: MedtronicHistoryEvent.BolusAmounts): String {
        if (!event.fastIu.isFinite() || !event.extendedIu.isFinite()) return ""
        val total = event.fastIu + event.extendedIu
        return if (event.extendedIu > 0.0 && event.durationMin > 0) {
            "${units(total)} U " +
                "(natychmiast ${units(event.fastIu)} U + " +
                "przedłużony ${units(event.extendedIu)} U przez ${event.durationMin} min)"
        } else {
            "${units(total)} U"
        }
    }

    private fun bolusProgrammedDetail(event: MedtronicHistoryEvent.BolusProgrammedDetail): String {
        val reasons = buildList {
            if (event.isCorrection) add("korekta")
            if (event.isMeal) add("posiłek")
        }
        val activation = event.activationType?.let(::activationLabel)
        return when {
            reasons.isEmpty() -> activation.orEmpty()
            activation == null -> reasons.joinToString(" + ")
            else -> reasons.joinToString(" + ") + " ($activation)"
        }
    }

    private fun basalChange(event: MedtronicHistoryEvent.BasalRateChanged): String {
        val context =
            when (event.context) {
                HistoryBasalDeliveryContext.ARTIFICIAL_PANCREAS_CONTROLLER -> "automatyczna"
                HistoryBasalDeliveryContext.REMOTE_CONTROL -> "zdalna"
                else -> null
            }
        val change = "${rate(event.oldRateIuPerHour)} → ${rate(event.newRateIuPerHour)} U/h"
        return if (context != null) "$change ($context)" else change
    }

    private fun sgValue(rawMgDl: Int): String =
        if (rawMgDl in MedtronicHistoryParser.SG_SENTINELS ||
            rawMgDl !in CgmReading.MIN_MG_DL..CgmReading.MAX_MG_DL
        ) {
            "brak odczytu"
        } else {
            "$rawMgDl mg/dL"
        }

    private fun bgReading(event: MedtronicHistoryEvent.BgReading): String {
        if (!event.mgDl.isFinite()) return ""
        val kind = if (event.calibration) "kalibracja" else "BG"
        val rejected = if (event.calibration && !event.accepted) ", odrzucona" else ""
        return "$kind ${event.mgDl.roundToInt()} mg/dL$rejected"
    }

    private fun insulinDelivery(event: MedtronicHistoryEvent.InsulinDelivery): String {
        val action = if (event.stopped) "wstrzymano" else "wznowiono"
        return "$action, powód ${event.reason}"
    }

    private fun annunciation(event: MedtronicHistoryEvent.Annunciation): String =
        if (event.cleared) {
            "typ ${hexId(event.annunciationType)} (wyczyszczony)"
        } else {
            "typ ${hexId(event.annunciationType)}, status ${event.statusRaw}"
        }

    private fun activationLabel(type: BolusActivationType): String? =
        when (type) {
            BolusActivationType.MANUAL -> "ręczny"
            BolusActivationType.RECOMMENDED -> "z rekomendacji"
            BolusActivationType.MANUALLY_CHANGED_RECOMMENDED -> "zmieniony ręcznie"
            BolusActivationType.COMMANDED -> "automatyczny"
            BolusActivationType.UNDETERMINED, BolusActivationType.UNKNOWN -> null
        }

    // -- Labels and formatting ----------------------------------------------

    private fun typeLabel(type: MedtronicHistoryEventType): String =
        when (type) {
            MedtronicHistoryEventType.REFERENCE_TIME -> "Czas referencyjny"
            MedtronicHistoryEventType.BOLUS_PROGRAMMED_P1 -> "Bolus zaprogramowany (kwoty)"
            MedtronicHistoryEventType.BOLUS_PROGRAMMED_P2 -> "Bolus zaprogramowany (szczegóły)"
            MedtronicHistoryEventType.BOLUS_DELIVERED_P1 -> "Bolus dostarczony (kwoty)"
            MedtronicHistoryEventType.BOLUS_DELIVERED_P2 -> "Bolus dostarczony (szczegóły)"
            MedtronicHistoryEventType.DELIVERED_BASAL_RATE_CHANGED -> "Zmiana bazy"
            MedtronicHistoryEventType.MAX_BOLUS_AMOUNT_CHANGED -> "Zmiana limitu bolusa"
            MedtronicHistoryEventType.AUTO_BASAL_DELIVERY -> "Automatyczna baza (mikrobolus)"
            MedtronicHistoryEventType.CL1_TRANSITION -> "Zmiana trybu pracy zamkniętej pętli"
            MedtronicHistoryEventType.THERAPY_CONTEXT -> "Kontekst terapii"
            MedtronicHistoryEventType.MEAL -> "Posiłek"
            MedtronicHistoryEventType.BG_READING -> "Pomiar glukozy z krwi"
            MedtronicHistoryEventType.CALIBRATION_COMPLETE -> "Kalibracja zakończona"
            MedtronicHistoryEventType.CALIBRATION_REJECTED -> "Kalibracja odrzucona"
            MedtronicHistoryEventType.INSULIN_DELIVERY_STOPPED ->
                "Wstrzymanie podawania insuliny"
            MedtronicHistoryEventType.INSULIN_DELIVERY_RESTARTED ->
                "Wznowienie podawania insuliny"
            MedtronicHistoryEventType.SG_MEASUREMENT -> "Pomiar glukozy z sensora"
            MedtronicHistoryEventType.CGM_ANALYTICS_DATA_BACKFILL -> "Uzupełnienie danych CGM"
            MedtronicHistoryEventType.NGP_REFERENCE_TIME -> "Czas odniesienia NGP"
            MedtronicHistoryEventType.ANNUNCIATION_CLEARED -> "Alarm rozwiązany"
            MedtronicHistoryEventType.ANNUNCIATION_CONSOLIDATED -> "Alarm lub ostrzeżenie"
            MedtronicHistoryEventType.MAX_AUTO_BASAL_RATE_CHANGED -> "Zmiana limitu auto-bazy"
            MedtronicHistoryEventType.UNDEFINED -> UNKNOWN_TYPE_LABEL
        }

    private fun units(value: Double): String = if (value.isFinite()) "%.2f".format(value) else "?"

    private fun rate(value: Double): String = if (value.isFinite()) "%.3f".format(value) else "?"

    private fun hexId(id: Int): String = "0x%04X".format(id and 0xFFFF)
}
