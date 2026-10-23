package pl.mgsupport.mglycemia

import org.junit.Assert.*
import org.junit.Test
import pl.mgsupport.mglycemia.core.domain.model.HistoryLogRecord
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

/**
 * Czysta (JVM) polityka kursora backfillu: pełny backfill od seq 0 raz, potem przyrostowo,
 * bez cofania, bez czasu pobrania jako czasu zdarzenia (czas liczy HistoryTimeline).
 */
class HistoryCursorDedupTest {

    @Test
    fun firstFetchStartsAtZero() {
        assertEquals(0, HistoryCursorPolicy.nextFetchStart(null))
    }

    @Test
    fun incrementalFetchStartsAtCursor() {
        assertEquals(1234, HistoryCursorPolicy.nextFetchStart(1234))
    }

    @Test
    fun advanceMovesForwardOnly() {
        assertEquals(10, HistoryCursorPolicy.advance(null, listOf(7, 10, 9)))
        assertEquals(20, HistoryCursorPolicy.advance(10, listOf(11, 20)))
        // Nakładające się ponowne pobranie nie cofa kursora.
        assertEquals(20, HistoryCursorPolicy.advance(20, listOf(11, 20)))
        assertEquals(20, HistoryCursorPolicy.advance(20, listOf(5, 9)))
        // Puste pobranie zostawia kursor w miejscu.
        assertEquals(20, HistoryCursorPolicy.advance(20, emptyList()))
        assertNull(HistoryCursorPolicy.advance(null, emptyList()))
    }

    @Test
    fun unsignedWrapAroundComparesAsWireValues() {
        // Sekwencje są u32 na przewodzie, Int w modelu — -1 to 0xFFFFFFFF, czyli „dalej” niż 5.
        assertEquals(-1, HistoryCursorPolicy.maxOf(listOf(5, -1)))
        assertEquals(-1, HistoryCursorPolicy.advance(5, listOf(-1)))
        // Po przepełnieniu licznika kursor celowo nie cofa się do niskich wartości. Pełny wrap
        // to 2^32 rekordów (tysiące lat przy realnym tempie zapisu), a cofnięcie kursora oznaczałoby
        // ponowny pełny backfill. Reset licznika pompy wymaga nowego source_id, nie cichego cofnięcia.
        assertEquals(-1, HistoryCursorPolicy.advance(-1, listOf(5)))
    }

    @Test
    fun duplicateBatchIsIdempotentBySequence() {
        val batch = listOf(raw(1), raw(2), raw(3))
        val doubled = batch + batch
        // Dedup po seq: drugie pobranie tego samego zakresu nic nie zmienia.
        assertEquals(listOf(1, 2, 3), doubled.distinctBy { it.sequenceNumber }.map { it.sequenceNumber })
        // Kursor po re-insercie nie cofa się.
        assertEquals(3, HistoryCursorPolicy.advance(3, doubled.map { it.sequenceNumber }))
    }

    @Test
    fun presenterNeverDuplicatesRefetchedRange() {
        val presenter = HistoryPresenter()
        val batch = listOf(raw(1), raw(2), raw(3))
        val lines = presenter.describeAll(batch + batch, max = 30)
        assertEquals(3, lines.size)
        assertEquals(3, lines.distinct().size)
    }

    private fun raw(seq: Int): HistoryLogRecord {
        val bytes = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(0x1234.toShort()).putInt(seq).putShort(10.toShort()).array()
        return HistoryLogRecord(seq, Base64.getEncoder().encodeToString(bytes), 0x1234, 10L)
    }
}
