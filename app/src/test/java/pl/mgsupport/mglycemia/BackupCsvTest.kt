package pl.mgsupport.mglycemia

import org.junit.Assert.*
import org.junit.Test
import pl.mgsupport.mglycemia.core.domain.model.HistoryLogRecord
import java.io.IOException
import java.io.StringReader
import java.io.StringWriter

class BackupCsvTest {
    private val reading = ReadingRow(1700000000000, 123, "FLAT", -0.25, 1.5f, 0.75f, true, 75f, 90, false)

    @Test fun roundTripsEveryRecordAndSameTimestampSamplesWithoutLoss() {
        val rows = listOf(
            BackupRow.Reading("one", reading), BackupRow.Reading("two", reading.copy(glucoseMgDl = 123)),
            BackupRow.Reading("nulls", ReadingRow(7, null, null, null, null, null, null, null, null, null)),
            BackupRow.Event(EventRow(77, 42, 0x69, "  alarm, \"tekst\"\r\nżółć  ")),
            BackupRow.History(StoredHistoryRecord("pump,\nA", HistoryLogRecord(-1, "raw-is-preserved-even-if-malformed", 0xf00e, 123))),
            BackupRow.History(StoredHistoryRecord("pump-B", HistoryLogRecord(-1, "AQID", 0x69, 123))),
            BackupRow.Alert(AppAlertRow("alert-1", 99, 68, "niski\npoziom")),
        )
        val encoded = encode(rows)
        val decoded = ArrayList<BackupRow>()
        val info = BackupCsv.read(StringReader(encoded)) { decoded += it }
        assertEquals(rows, decoded)
        assertEquals(2, info.version)
        assertEquals(1234L, info.createdMs)
        assertEquals(0, info.errors)
        assertNotNull(info.checksum)
        assertEquals(rows, ArrayList<BackupRow>().also { out ->
            BackupCsv.read(StringReader("\uFEFF" + encoded)) { out += it }
        })
    }

    @Test fun readsLegacyReadingsEventsAndSingleFile() {
        val oldReading = "timestamp_iso,epoch_ms,glucose_mgdl,trend,basal_automated,battery_charging\r\n,123,99,FLAT,true,no\r\n"
        val rows = ArrayList<BackupRow>()
        assertEquals(1, BackupCsv.read(StringReader(oldReading)) { rows += it }.version)
        assertEquals(99, (rows.single() as BackupRow.Reading).value.glucoseMgDl)
        assertNull((rows.single() as BackupRow.Reading).id)
        val event = "sequence,type_id,timestamp_iso,epoch_ms,text\n1,105,,123,\"  quoted, \"\"alarm\"\"\nline  \""
        rows.clear()
        BackupCsv.read(StringReader(event)) { rows += it }
        assertEquals("  quoted, \"alarm\"\nline  ", (rows.single() as BackupRow.Event).value.text)
        rows.clear()
        BackupCsv.read(StringReader("record_type,epoch_ms,glucose_mgdl,sequence,type_id,text\nreading,123,110,,,\nevent,124,,2,105,alarm\n")) { rows += it }
        assertEquals(2, rows.size)
    }

    @Test fun preservesAllGlucoseValuesInsteadOfApplyingLossyImportFilter() {
        val rows = listOf(BackupRow.Reading("low", reading.copy(glucoseMgDl = 19)),
            BackupRow.Reading("high", reading.copy(glucoseMgDl = 501)))
        val result = ArrayList<BackupRow>()
        BackupCsv.read(StringReader(encode(rows))) { result += it }
        assertEquals(rows, result)
    }

    @Test fun rejectsTruncationAlterationTrailingRowsAndUnknownVersions() {
        val csv = encode(listOf(BackupRow.Reading("id", reading)))
        assertThrows(IOException::class.java) { BackupCsv.read(StringReader(csv.substringBeforeLast("end,"))) }
        assertThrows(IOException::class.java) { BackupCsv.read(StringReader(csv.replace("FLAT", "RISING"))) }
        assertThrows(IOException::class.java) { BackupCsv.read(StringReader(csv + "reading\n")) }
        assertThrows(IOException::class.java) { BackupCsv.read(StringReader(csv.replace(",2,1234,", ",3,1234,"))) }
        assertThrows(IOException::class.java) { BackupCsv.read(StringReader("sequence,type_id,epoch_ms,text\n1,2,3,\"broken")) }
    }

    @Test fun validEmptyBackupAndLegacyErrorsAreDistinguishable() {
        assertEquals(0, BackupCsv.read(StringReader(encode(emptyList()))).errors)
        assertThrows(IOException::class.java) { BackupCsv.read(StringReader("nonsense\n")) }
        assertThrows(IOException::class.java) { BackupCsv.read(StringReader("")) }
        assertEquals(1, BackupCsv.read(StringReader("epoch_ms,glucose_mgdl\ninvalid,100\n")).errors)
        assertEquals(1, BackupCsv.read(StringReader("epoch_ms,glucose_mgdl,iob_u\n123,100,NaN\n")).errors)
    }

    @Test fun consumerFailuresAreNotSwallowedAsBadCsvRows() {
        assertThrows(IllegalArgumentException::class.java) {
            BackupCsv.read(StringReader("epoch_ms,glucose_mgdl\n123,100\n")) { throw IllegalArgumentException("database failure") }
        }
    }

    private fun encode(rows: List<BackupRow>) = StringWriter().also { writer ->
        BackupCsv.Encoder(writer, 1234).apply { rows.forEach { write(it) }; finish() }
    }.toString()
}
