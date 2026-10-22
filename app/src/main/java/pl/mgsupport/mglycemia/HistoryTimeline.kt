package pl.mgsupport.mglycemia

import pl.mgsupport.mglycemia.core.ble.messages.BolusActivationType
import pl.mgsupport.mglycemia.core.ble.messages.MedtronicHistoryEvent
import pl.mgsupport.mglycemia.core.ble.messages.MedtronicHistoryEventType
import pl.mgsupport.mglycemia.core.ble.messages.MedtronicHistoryParser
import pl.mgsupport.mglycemia.core.ble.messages.MedtronicHistoryRecord
import pl.mgsupport.mglycemia.core.domain.model.HistoryLogRecord
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Base64

enum class InsulinEventKind {
    MANUAL_BOLUS, CORRECTION, MEAL_BOLUS, MEAL_CORRECTION, AUTOMATIC_BOLUS, AUTO_BASAL, UNKNOWN_BOLUS,
}

data class ChartInsulinEvent(val id: String, val timestampMs: Long, val units: Double, val kind: InsulinEventKind)

data class StoredHistoryRecord(val sourceId: String, val record: HistoryLogRecord)

/**
 * Read-only projection of the complete persisted history, not a download batch. Only delivered P1
 * records contribute boluses; programmed records supply metadata, never another insulin amount.
 * Source IDs must identify a pump/log incarnation (change the ID after a pump log reset).
 */
object HistoryTimeline {
    fun project(records: List<StoredHistoryRecord>, fromMs: Long, toMs: Long): List<ChartInsulinEvent> {
        if (fromMs > toMs) return emptyList()
        return records.groupBy { it.sourceId }.flatMap { (sourceId, source) ->
            projectSource(sourceId, source.map { it.record })
        }.filter { it.timestampMs in fromMs..toMs }
            .sortedWith(compareBy<ChartInsulinEvent> { it.timestampMs }.thenBy { it.id })
    }

    private data class Timed(val parsed: MedtronicHistoryRecord, val timeMs: Long?, val segment: Int)
    private data class Program(val timeMs: Long, val segment: Int, val detail: MedtronicHistoryEvent.BolusProgrammedDetail?)

    private fun projectSource(sourceId: String, records: List<HistoryLogRecord>): List<ChartInsulinEvent> {
        var reference: Instant? = null
        var segment = 0
        var previousSequence: Long? = null
        val timed = ArrayList<Timed>()
        for (raw in records.distinctBy { it.sequenceNumber }.sortedBy { unsignedSequence(it.sequenceNumber) }) {
            val sequence = unsignedSequence(raw.sequenceNumber)
            if (previousSequence != null && sequence != previousSequence + 1) {
                // A missing record might be a time reference. Backfill will reproject once it arrives.
                reference = null
                segment++
            }
            previousSequence = sequence
            val bytes = try { Base64.getDecoder().decode(raw.rawBytesB64) } catch (_: IllegalArgumentException) { null }
            val parsed = bytes?.let { MedtronicHistoryParser.parseRecordBody(it) }
            if (parsed == null || parsed.sequenceNumber != raw.sequenceNumber ||
                parsed.relativeOffsetSeconds.toLong() != raw.pumpTimeSeconds ||
                (bytes[0].toInt() and 0xff or ((bytes[1].toInt() and 0xff) shl 8)) != raw.eventTypeId
            ) {
                // A corrupt/mismatched record could itself have been a clock reset.
                reference = null
                segment++
                continue
            }
            if (parsed.eventType == MedtronicHistoryEventType.NGP_REFERENCE_TIME ||
                parsed.eventType == MedtronicHistoryEventType.REFERENCE_TIME
            ) {
                // Unlike the legacy extractor, a bad reference MUST invalidate the previous anchor.
                reference = (parsed.event as? MedtronicHistoryEvent.ReferenceTime)?.time
                    ?.takeIf { unambiguousReference(bytes, it) }
                if (reference == null) segment++
                continue
            }
            timed += Timed(parsed, reference?.plusSeconds(parsed.relativeOffsetSeconds.toLong())?.toEpochMilli(), segment)
        }
        val bySequence = timed.associateBy { unsignedSequence(it.parsed.sequenceNumber) }
        val programs = HashMap<Int, Program>()
        val out = ArrayList<ChartInsulinEvent>()
        for ((record, timeMs, clockSegment) in timed) {
            val seq = unsignedSequence(record.sequenceNumber)
            val next = bySequence[seq + 1]
            // P2 has no bolus ID. Only exact sequence adjacency is evidence of a matching pair.
            val adjacent = next?.takeIf { timeMs != null && it.timeMs != null && it.segment == clockSegment }?.parsed?.event
            when (val event = record.event) {
                is MedtronicHistoryEvent.BolusAmounts -> {
                    if (!event.delivered) {
                        programs.remove(event.bolusId)
                        if (timeMs != null) programs[event.bolusId] = Program(
                            timeMs, clockSegment, adjacent as? MedtronicHistoryEvent.BolusProgrammedDetail,
                        )
                        continue
                    }
                    // Consume once, so a reused bolus ID never inherits an old delivery's reason.
                    val program = programs.remove(event.bolusId)
                    if (timeMs == null) continue
                    val units = event.fastIu + event.extendedIu
                    if (!validAmount(event.fastIu) || !validAmount(event.extendedIu) || !validAmount(units) || units == 0.0) continue
                    val detail = (adjacent as? MedtronicHistoryEvent.BolusDeliveredDetail)?.activationType
                    // A bounded, preceding programmed record avoids reusing an ID from an old log.
                    val reason = program?.takeIf {
                        it.segment == clockSegment && timeMs - it.timeMs in 0..MAX_PROGRAM_AGE_MS
                    }?.detail
                    out += ChartInsulinEvent(eventId(sourceId, seq), timeMs, units, classify(detail, reason))
                }
                is MedtronicHistoryEvent.AutoBasalMicroBolus -> {
                    if (timeMs != null && validAmount(event.amountIu) && event.amountIu > 0.0) {
                        out += ChartInsulinEvent(eventId(sourceId, seq), timeMs, event.amountIu, InsulinEventKind.AUTO_BASAL)
                    }
                }
                else -> Unit
            }
        }
        return out
    }

    private fun classify(
        delivered: BolusActivationType?,
        reason: MedtronicHistoryEvent.BolusProgrammedDetail?,
    ): InsulinEventKind {
        val programmed = reason?.activationType
        val known = setOf(BolusActivationType.MANUAL, BolusActivationType.RECOMMENDED,
            BolusActivationType.MANUALLY_CHANGED_RECOMMENDED, BolusActivationType.COMMANDED)
        if (delivered in known && programmed in known &&
            (delivered == BolusActivationType.COMMANDED) != (programmed == BolusActivationType.COMMANDED)
        ) return InsulinEventKind.UNKNOWN_BOLUS
        val activation = delivered?.takeIf { it in known } ?: programmed?.takeIf { it in known }
        return when {
            activation == BolusActivationType.COMMANDED -> InsulinEventKind.AUTOMATIC_BOLUS
            reason?.isMeal == true && reason.isCorrection -> InsulinEventKind.MEAL_CORRECTION
            reason?.isMeal == true -> InsulinEventKind.MEAL_BOLUS
            reason?.isCorrection == true -> InsulinEventKind.CORRECTION
            activation == BolusActivationType.MANUAL -> InsulinEventKind.MANUAL_BOLUS
            else -> InsulinEventKind.UNKNOWN_BOLUS
        }
    }

    /** The existing parser interprets naive pump time in the phone zone. Reject DST gaps/overlaps. */
    private fun unambiguousReference(bytes: ByteArray, parsedTime: Instant): Boolean = try {
        val local = LocalDateTime.of(
            (bytes[9].toInt() and 0xff) or ((bytes[10].toInt() and 0xff) shl 8),
            bytes[11].toInt() and 0xff, bytes[12].toInt() and 0xff,
            bytes[13].toInt() and 0xff, bytes[14].toInt() and 0xff, bytes[15].toInt() and 0xff,
        )
        val zone = ZoneId.systemDefault()
        local.year in 2000..2099 && zone.rules.getValidOffsets(local).size == 1 &&
            local.atZone(zone).toInstant() == parsedTime
    } catch (_: java.time.DateTimeException) { false }

    // Match the existing read-only history safety ceiling; retain outliers in raw storage/CSV.
    private fun validAmount(units: Double) = units.isFinite() && units in 0.0..25.0
    private fun eventId(source: String, seq: Long) = "${source.length}:$source:$seq"
    internal fun unsignedSequence(seq: Int): Long = seq.toLong() and 0xffffffffL
    private const val MAX_PROGRAM_AGE_MS = 24 * 60 * 60 * 1000L
}
