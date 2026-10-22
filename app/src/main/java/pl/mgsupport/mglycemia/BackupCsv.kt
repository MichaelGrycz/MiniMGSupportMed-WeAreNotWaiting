package pl.mgsupport.mglycemia

import pl.mgsupport.mglycemia.core.domain.model.HistoryLogRecord
import java.io.IOException
import java.io.PushbackReader
import java.io.Reader
import java.io.Writer
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale

/** App alerts have independent stable IDs, never synthetic pump sequence numbers. */
data class AppAlertRow(val id: String, val timestampMs: Long, val glucoseMgDl: Int, val text: String)

internal sealed interface BackupRow {
    data class Reading(val id: String?, val value: ReadingRow) : BackupRow
    data class Event(val value: EventRow) : BackupRow
    data class History(val value: StoredHistoryRecord) : BackupRow
    data class Alert(val value: AppAlertRow) : BackupRow
}

internal data class BackupCsvInfo(val version: Int, val createdMs: Long?, val checksum: String?, val errors: Int)

/** Streaming codec. The first 16 columns and legacy reading/event shapes remain compatible. */
internal object BackupCsv {
    private val columns = listOf(
        "record_type", "timestamp_iso", "epoch_ms", "glucose_mgdl", "glucose_mmol", "trend", "roc_mgdl_min",
        "iob_u", "basal_u_h", "basal_automated", "reservoir_u", "battery_pct", "battery_charging",
        "sequence", "type_id", "text", "source_id", "pump_time_seconds", "raw_bytes_b64", "record_id",
        "format_version", "created_ms", "row_count", "sha256",
    )

    class Encoder(private val writer: Writer, createdMs: Long) {
        private val digest = MessageDigest.getInstance("SHA-256")
        private var count = 0L
        private var finished = false
        init {
            line(columns)
            line(fields("record_type" to "backup", "format_version" to "2", "created_ms" to createdMs.toString()))
        }
        fun write(row: BackupRow) {
            check(!finished)
            val values = when (row) {
                is BackupRow.Reading -> with(row.value) {
                    fields(
                        "record_type" to "reading", "record_id" to row.id.orEmpty(),
                        "timestamp_iso" to iso(tsMs), "epoch_ms" to tsMs.toString(),
                        "glucose_mgdl" to glucoseMgDl?.toString().orEmpty(),
                        "glucose_mmol" to (glucoseMgDl?.let { String.format(Locale.US, "%.2f", it / 18.0156) } ?: ""),
                        "trend" to trend.orEmpty(), "roc_mgdl_min" to roc?.toString().orEmpty(),
                        "iob_u" to iob?.toString().orEmpty(), "basal_u_h" to basal?.toString().orEmpty(),
                        "basal_automated" to flag(basalAuto), "reservoir_u" to reservoir?.toString().orEmpty(),
                        "battery_pct" to battery?.toString().orEmpty(), "battery_charging" to flag(batteryCharging),
                    )
                }
                is BackupRow.Event -> with(row.value) {
                    fields("record_type" to "event", "timestamp_iso" to iso(tsMs), "epoch_ms" to tsMs.toString(),
                        "sequence" to seq.toString(), "type_id" to typeId.toString(), "text" to text)
                }
                is BackupRow.History -> with(row.value) {
                    fields("record_type" to "history", "source_id" to sourceId,
                        "sequence" to record.sequenceNumber.toString(), "type_id" to record.eventTypeId.toString(),
                        "pump_time_seconds" to record.pumpTimeSeconds.toString(), "raw_bytes_b64" to record.rawBytesB64)
                }
                is BackupRow.Alert -> with(row.value) {
                    fields("record_type" to "app_alert", "record_id" to id, "timestamp_iso" to iso(timestampMs),
                        "epoch_ms" to timestampMs.toString(), "glucose_mgdl" to glucoseMgDl.toString(), "text" to text)
                }
            }
            line(values)
            count++
        }
        fun finish() {
            check(!finished)
            finished = true
            writer.write(csvLine(fields("record_type" to "end", "row_count" to count.toString(), "sha256" to digest.hex())))
            writer.flush()
        }
        private fun line(values: List<String>) {
            val line = csvLine(values)
            digest.update(line.toByteArray(Charsets.UTF_8))
            writer.write(line)
        }
    }

    /** Invalid modern backups throw: the caller must roll back any streamed inserts. */
    fun read(reader: Reader, consume: (BackupRow) -> Unit = {}): BackupCsvInfo {
        val csv = CsvReader(reader)
        val header = csv.next() ?: throw IOException("Empty CSV")
        val names = header.map { it.trim().trimStart('\uFEFF').lowercase(Locale.US) }
        if (names.distinct().size != names.size) throw IOException("Duplicate CSV columns")
        val index = names.withIndex().associate { it.value to it.index }
        val backup = "record_type" in index
        val legacyType = when {
            backup -> null
            "glucose_mgdl" in index && "epoch_ms" in index -> "reading"
            "sequence" in index && "type_id" in index && "epoch_ms" in index -> "event"
            else -> throw IOException("Unrecognized CSV header")
        }
        fun get(row: List<String>, name: String) = index[name]?.let { row.getOrNull(it) }.orEmpty()
        val modern = "format_version" in index
        val digest = MessageDigest.getInstance("SHA-256")
        if (modern) {
            if (names != columns) throw IOException("Unsupported backup columns")
            digest.update(csvLine(names).toByteArray(Charsets.UTF_8))
        }
        var created: Long? = null
        var count = 0L
        var errors = 0
        var checksum: String? = null
        var metadataSeen = false
        var ended = false
        while (true) {
            val row = csv.next() ?: break
            if (row.all { it.isBlank() }) continue
            if (ended) throw IOException("Data after backup footer")
            val type = legacyType ?: get(row, "record_type").trim().lowercase(Locale.US)
            if (modern && row.size != columns.size) throw IOException("Truncated backup row")
            if (modern && !metadataSeen) {
                if (type != "backup" || get(row, "format_version") != "2") throw IOException("Unsupported backup version")
                created = get(row, "created_ms").toLongOrNull() ?: throw IOException("Invalid backup date")
                metadataSeen = true
                digest.update(csvLine(row).toByteArray(Charsets.UTF_8))
                continue
            }
            if (modern && type == "end") {
                checksum = digest.hex()
                if (get(row, "row_count").toLongOrNull() != count || get(row, "sha256") != checksum) {
                    throw IOException("Incomplete or modified backup")
                }
                ended = true
                continue
            }
            val decoded = try {
                fun text(name: String) = get(row, name)
                fun number(name: String) = text(name).trim()
                fun long(name: String) = number(name).toLong()
                fun int(name: String) = number(name).toInt()
                fun optionalInt(name: String) = number(name).takeIf { it.isNotEmpty() }?.toInt()
                fun optionalDouble(name: String) = number(name).takeIf { it.isNotEmpty() }?.toDouble()?.also { require(it.isFinite()) }
                fun optionalFloat(name: String) = number(name).takeIf { it.isNotEmpty() }?.toFloat()?.also { require(it.isFinite()) }
                fun optionalFlag(name: String): Boolean? = when (number(name).lowercase(Locale.US)) {
                    "" -> null
                    "1", "true", "yes" -> true
                    "0", "false", "no" -> false
                    else -> throw IllegalArgumentException("Invalid boolean")
                }
                fun id(): String? = text("record_id").takeIf { it.isNotBlank() }.also { require(!modern || it != null) }
                when (type) {
                    "reading" -> BackupRow.Reading(id(), ReadingRow(
                        long("epoch_ms"), optionalInt("glucose_mgdl"), text("trend").takeIf { it.isNotEmpty() },
                        optionalDouble("roc_mgdl_min"), optionalFloat("iob_u"), optionalFloat("basal_u_h"),
                        optionalFlag("basal_automated"), optionalFloat("reservoir_u"), optionalInt("battery_pct"), optionalFlag("battery_charging"),
                    ))
                    "event" -> BackupRow.Event(EventRow(int("sequence"), long("epoch_ms"), optionalInt("type_id") ?: 0, text("text")))
                    "history" -> BackupRow.History(StoredHistoryRecord(
                        text("source_id").also { require(it.isNotBlank()) },
                        HistoryLogRecord(int("sequence"), text("raw_bytes_b64"), int("type_id"), long("pump_time_seconds")),
                    ))
                    "app_alert" -> BackupRow.Alert(AppAlertRow(requireNotNull(id()), long("epoch_ms"), int("glucose_mgdl"), text("text")))
                    else -> throw IllegalArgumentException("Unknown record type")
                }
            } catch (e: IllegalArgumentException) {
                if (modern) throw IOException("Invalid backup row ${count + 1}", e)
                errors++
                null
            }
            // Database/storage exceptions from the consumer are never disguised as row errors.
            if (decoded != null) consume(decoded)
            if (modern) digest.update(csvLine(row).toByteArray(Charsets.UTF_8))
            count++
        }
        if (modern && (!metadataSeen || !ended)) throw IOException("Missing backup footer")
        return BackupCsvInfo(if (modern) 2 else 1, created, checksum, errors)
    }

    private fun fields(vararg values: Pair<String, String>): List<String> {
        val map = values.toMap()
        return columns.map { map[it].orEmpty() }
    }
    private fun flag(value: Boolean?) = value?.let { if (it) "1" else "0" }.orEmpty()
    private fun iso(ms: Long) = Instant.ofEpochMilli(ms).toString()
    private fun csvLine(values: List<String>) = values.joinToString(",") { value ->
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"${value.replace("\"", "\"\"")}\"" else value
    } + "\n"
    private fun MessageDigest.hex() = digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }

    /** RFC-style quoting with bounded memory (one record), CRLF/BOM and multiline text support. */
    private class CsvReader(reader: Reader) {
        private val input = PushbackReader(reader.buffered(), 1)
        init { val first = input.read(); if (first != -1 && first != '\uFEFF'.code) input.unread(first) }
        fun next(): List<String>? {
            val row = ArrayList<String>()
            val field = StringBuilder()
            var quoted = false
            var closed = false
            var seen = false
            while (true) {
                val ch = input.read()
                if (ch == -1) {
                    if (quoted) throw IOException("Unclosed CSV quote")
                    if (!seen) return null
                    row += field.toString()
                    return row
                }
                seen = true
                val c = ch.toChar()
                if (quoted) {
                    if (c == '"') {
                        val next = input.read()
                        if (next == '"'.code) field.append('"') else {
                            quoted = false
                            closed = true
                            if (next != -1) input.unread(next)
                        }
                    } else field.append(c)
                } else when (c) {
                    ',' -> { row += field.toString(); field.setLength(0); closed = false }
                    '\n', '\r' -> {
                        if (c == '\r') { val next = input.read(); if (next != -1 && next != '\n'.code) input.unread(next) }
                        row += field.toString()
                        return row
                    }
                    '"' -> {
                        if (field.isNotEmpty() || closed) throw IOException("Unexpected CSV quote")
                        quoted = true
                    }
                    else -> {
                        if (closed) throw IOException("Data after CSV quote")
                        field.append(c)
                    }
                }
                if (field.length > 4 * 1024 * 1024 || row.size > 128) throw IOException("Oversized CSV record")
            }
        }
    }
}
