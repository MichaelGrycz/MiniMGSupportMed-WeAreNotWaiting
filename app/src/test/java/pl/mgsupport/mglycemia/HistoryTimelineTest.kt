package pl.mgsupport.mglycemia

import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import pl.mgsupport.mglycemia.core.domain.model.HistoryLogRecord
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.util.Base64
import java.util.TimeZone

class HistoryTimelineTest {
    private lateinit var previousZone: TimeZone
    private val epoch = Instant.parse("2026-01-10T12:00:00Z").toEpochMilli()
    @Before fun utc() { previousZone = TimeZone.getDefault(); TimeZone.setDefault(TimeZone.getTimeZone("UTC")) }
    @After fun resetZone() { TimeZone.setDefault(previousZone) }

    @Test fun splitBatchesEnrichStableDeliveredEventWithoutCountingProgrammed() {
        val first = listOf(ref(1), amounts(2, false, offset = 10), programmed(3, flags = 12), amounts(4, true, offset = 20))
        val before = project(first).single()
        assertEquals(InsulinEventKind.CORRECTION, before.kind)
        val complete = project(first + delivered(5, offset = 21))
        assertEquals(1, complete.size)
        assertEquals(before.id, complete.single().id)
        assertEquals(epoch + 20_000, complete.single().timestampMs)
        assertEquals(1.5, complete.single().units, 0.000001)
        assertTrue(project(first.take(3)).isEmpty())
    }

    @Test fun missingDetailIsUnknownAndExactAdjacencyIsRequired() {
        assertEquals(InsulinEventKind.UNKNOWN_BOLUS, project(listOf(ref(1), amounts(2, true))).single().kind)
        val records = listOf(ref(1), amounts(2, true), raw(3, 0x1234), delivered(4))
        assertEquals(InsulinEventKind.UNKNOWN_BOLUS, project(records).single().kind)
    }

    @Test fun classifiesManualCorrectionMealCombinedAutomaticAndAutoBasalSeparately() {
        val expectations = listOf(4 to InsulinEventKind.MANUAL_BOLUS, 12 to InsulinEventKind.CORRECTION,
            20 to InsulinEventKind.MEAL_BOLUS, 28 to InsulinEventKind.MEAL_CORRECTION)
        for ((flags, kind) in expectations) {
            assertEquals(kind, project(listOf(ref(1), amounts(2, false), programmed(3, flags), amounts(4, true), delivered(5))).single().kind)
        }
        assertEquals(InsulinEventKind.AUTOMATIC_BOLUS,
            project(listOf(ref(1), amounts(2, true), delivered(3, activation = 0x5a))).single().kind)
        assertEquals(InsulinEventKind.AUTO_BASAL,
            project(listOf(ref(1), raw(2, 0xf001, payload = byteArrayOf(1) + medFloat(125)))).single().kind)
        assertEquals(InsulinEventKind.UNKNOWN_BOLUS,
            project(listOf(ref(1), amounts(2, true), delivered(3, activation = 0x0f))).single().kind)
    }

    @Test fun noReferenceNeverUsesDownloadTimeAndLateBackfillResolvesIt() {
        assertTrue(project(listOf(amounts(2, true))).isEmpty())
        assertEquals(epoch + 10_000, project(listOf(amounts(2, true), ref(1))).single().timestampMs)
        val event = project(listOf(ref(1), amounts(2, true))).single()
        assertEquals(listOf(event), HistoryTimeline.project(listOf(stored(ref(1)), stored(amounts(2, true))), event.timestampMs, event.timestampMs))
    }

    @Test fun invalidTruncatedAndUnknownReferenceInvalidateOldAnchor() {
        val invalids = listOf(ref(3, month = 13), raw(3, 0xf00e), raw(3, 0x000f))
        for (invalid in invalids) {
            val events = project(listOf(ref(1), amounts(2, true), invalid, amounts(4, true), ref(5), amounts(6, true)))
            assertEquals(2, events.size)
            assertTrue(events.none { it.id.endsWith(":4") })
        }
    }

    @Test fun badBase64MismatchedHeaderAndGapsCannotReuseAnchor() {
        val malformed = raw(3, 0x1234).copy(rawBytesB64 = "not base64")
        assertEquals(1, project(listOf(ref(1), amounts(2, true), malformed, amounts(4, true))).size)
        assertTrue(project(listOf(ref(1), amounts(3, true))).isEmpty())
        assertTrue(project(listOf(ref(1), amounts(2, true).copy(eventTypeId = 0xf001))).isEmpty())
    }

    @Test fun sourcesAndUnsignedSequencesRemainIndependent() {
        val same = listOf(ref(1), amounts(2, true))
        val events = HistoryTimeline.project(same.map { stored(it, "pump-A") } + same.map { stored(it, "pump-B") }, 0, Long.MAX_VALUE)
        assertEquals(2, events.size)
        assertEquals(2, events.map { it.id }.distinct().size)
        assertEquals(1, project(listOf(ref(Int.MAX_VALUE), amounts(Int.MIN_VALUE, true))).size)
        assertEquals(1, project(same + same).size)
    }

    @Test fun deliveredIdReuseAndFutureProgramsDoNotLeakReasons() {
        val old = listOf(ref(1), amounts(2, false), programmed(3, 20), amounts(4, true), amounts(5, true))
        assertEquals(listOf(InsulinEventKind.MEAL_BOLUS, InsulinEventKind.UNKNOWN_BOLUS), project(old).map { it.kind })
        assertEquals(InsulinEventKind.UNKNOWN_BOLUS,
            project(listOf(ref(1), amounts(2, true), amounts(3, false), programmed(4, 20))).single().kind)
        val reset = listOf(ref(1), amounts(2, false), programmed(3, 20), ref(4, month = 13), ref(5), amounts(6, true))
        assertEquals(InsulinEventKind.UNKNOWN_BOLUS, project(reset).single().kind)
    }

    @Test fun dstGapAndOverlapAreUnresolvedInsteadOfGuessed() {
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Warsaw"))
        assertTrue(project(listOf(ref(1, 3, 29, 2, 30), amounts(2, true))).isEmpty())
        assertTrue(project(listOf(ref(1, 10, 25, 2, 30), amounts(2, true))).isEmpty())
    }

    @Test fun invalidAmountsStayOutOfChart() {
        for (amount in listOf(-1000, 0, 26_000)) {
            assertTrue(project(listOf(ref(1), amounts(2, true, milliunits = amount))).isEmpty())
        }
    }

    @Test fun resolveTimesReturnsAbsolutePumpTimeForHistoryRows() {
        val records = listOf(ref(1), amounts(2, true), raw(3, 0x1234))
        val times = HistoryTimeline.resolveTimes(records.map { stored(it) })
        assertEquals(epoch + 10_000, times[2])
        assertEquals(epoch + 10_000, times[3])
        assertNull(times[1])
        assertNull(HistoryTimeline.resolveTimes(listOf(stored(amounts(2, true))))[2])
    }

    private fun project(records: List<HistoryLogRecord>) = HistoryTimeline.project(records.map { stored(it) }, 0, Long.MAX_VALUE)
    private fun stored(record: HistoryLogRecord, source: String = "pump") = StoredHistoryRecord(source, record)
    private fun ref(seq: Int, month: Int = 1, day: Int = 10, hour: Int = 12, minute: Int = 0) = raw(seq, 0xf00e,
        payload = byteArrayOf(0, 0xea.toByte(), 7, month.toByte(), day.toByte(), hour.toByte(), minute.toByte(), 0))
    private fun medFloat(milliunits: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        .putInt((0xfd shl 24) or (milliunits and 0xffffff)).array()
    private fun amounts(seq: Int, delivered: Boolean, offset: Int = 10, milliunits: Int = 1500) = raw(seq,
        if (delivered) 0x0069 else 0x005a, offset,
        byteArrayOf(42, 0, 0) + medFloat(milliunits) + medFloat(0) + byteArrayOf(0, 0))
    private fun programmed(seq: Int, flags: Int) = raw(seq, 0x0066, 10, byteArrayOf(flags.toByte(), 0x33))
    private fun delivered(seq: Int, offset: Int = 10, activation: Int = 0x33) = raw(seq, 0x0096, offset,
        byteArrayOf(1, 0, 0, 0, 0, activation.toByte()))
    private fun raw(seq: Int, type: Int, offset: Int = 10, payload: ByteArray = byteArrayOf()): HistoryLogRecord {
        val bytes = ByteBuffer.allocate(8 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(type.toShort()).putInt(seq).putShort(offset.toShort()).put(payload).array()
        return HistoryLogRecord(seq, Base64.getEncoder().encodeToString(bytes), type, offset.toLong())
    }
}
