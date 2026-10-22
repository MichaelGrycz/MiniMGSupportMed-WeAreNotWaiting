package pl.mgsupport.cgm

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** One sampling row: glucose plus the pump metrics current at that moment. */
data class ReadingRow(
    val tsMs: Long,
    val glucoseMgDl: Int?,
    val trend: String?,
    val roc: Double?,
    val iob: Float?,
    val basal: Float?,
    val basalAuto: Boolean?,
    val reservoir: Float?,
    val battery: Int?,
    val batteryCharging: Boolean?,
)

/** One pump history event (already rendered to text by [HistoryPresenter]). */
data class EventRow(val seq: Int, val tsMs: Long, val typeId: Int, val text: String)

/** Exact glucose statistics for a window, computed over every stored sample (not downsampled). */
data class WindowStats(
    val count: Int,
    val belowCount: Int,
    val inRangeCount: Int,
    val aboveCount: Int,
    val avg: Double,
    val min: Int,
    val max: Int,
)

/** Result of importing a single-file CSV backup. */
data class ImportResult(
    val readingsImported: Int,
    val readingsSkipped: Int,
    val eventsImported: Int,
    val errors: Int,
)

/**
 * Local SQLite store for long-term readings and pump events, plus CSV export.
 *
 * Readings are written once per CGM sample (~5 min) so a year is ~100k rows — comfortably small.
 * CSV export produces `readings.csv` / `events.csv` in the app-specific external directory, which
 * needs no storage permission and can be shared via FileProvider.
 */
class ReadingStore(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE readings (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              ts_ms INTEGER NOT NULL,
              glucose INTEGER,
              trend TEXT,
              roc REAL,
              iob REAL,
              basal REAL,
              basal_auto INTEGER,
              reservoir REAL,
              battery INTEGER,
              battery_charging INTEGER
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_readings_ts ON readings(ts_ms)")
        db.execSQL(
            """
            CREATE TABLE events (
              seq INTEGER PRIMARY KEY,
              ts_ms INTEGER NOT NULL,
              type_id INTEGER,
              text TEXT
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_events_ts ON events(ts_ms)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1: initial schema.
    }

    fun insertReading(r: ReadingRow) {
        val db = writableDatabase
        // Avoid duplicate 15-second poll rows when the 5-minute CGM sensor value has not changed yet.
        val last =
            db.rawQuery(
                "SELECT id, ts_ms, glucose FROM readings ORDER BY ts_ms DESC LIMIT 1",
                null,
            ).use { c ->
                if (c.moveToFirst()) Triple(c.getLong(0), c.getLong(1), if (c.isNull(2)) null else c.getInt(2))
                else null
            }

        if (last != null) {
            val (lastId, lastTs, lastGlucose) = last
            if (lastTs == r.tsMs) return
            if (lastGlucose != null && lastGlucose == r.glucoseMgDl && (r.tsMs - lastTs) in 0L..260_000L) {
                // Same 5-min CGM reading: update pump telemetry on the latest row without duplicating the CGM point.
                val updateCv =
                    ContentValues().apply {
                        r.trend?.let { put("trend", it) }
                        r.iob?.let { put("iob", it) }
                        r.basal?.let { put("basal", it) }
                        r.basalAuto?.let { put("basal_auto", if (it) 1 else 0) }
                        r.reservoir?.let { put("reservoir", it) }
                        r.battery?.let { put("battery", it) }
                        r.batteryCharging?.let { put("battery_charging", if (it) 1 else 0) }
                    }
                if (updateCv.size() > 0) {
                    db.update("readings", updateCv, "id = ?", arrayOf(lastId.toString()))
                }
                return
            }
        }

        val cv =
            ContentValues().apply {
                put("ts_ms", r.tsMs)
                r.glucoseMgDl?.let { put("glucose", it) }
                r.trend?.let { put("trend", it) }
                r.roc?.let { put("roc", it) }
                r.iob?.let { put("iob", it) }
                r.basal?.let { put("basal", it) }
                r.basalAuto?.let { put("basal_auto", if (it) 1 else 0) }
                r.reservoir?.let { put("reservoir", it) }
                r.battery?.let { put("battery", it) }
                r.batteryCharging?.let { put("battery_charging", if (it) 1 else 0) }
            }
        db.insert("readings", null, cv)
    }

    fun insertEvents(rows: List<EventRow>) {
        if (rows.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (e in rows) {
                val cv =
                    ContentValues().apply {
                        put("seq", e.seq)
                        put("ts_ms", e.tsMs)
                        put("type_id", e.typeId)
                        put("text", e.text)
                    }
                db.insertWithOnConflict("events", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Glucose samples in [fromMs, toMs], oldest first, deduplicated to 5-min CGM ticks and downsampled. */
    fun queryGlucose(fromMs: Long, toMs: Long, maxPoints: Int = 1500): List<Pair<Long, Int>> {
        val raw = ArrayList<Pair<Long, Int>>()
        readableDatabase
            .rawQuery(
                "SELECT ts_ms, glucose FROM readings WHERE ts_ms BETWEEN ? AND ? AND glucose IS NOT NULL ORDER BY ts_ms ASC",
                arrayOf(fromMs.toString(), toMs.toString()),
            )
            .use { c ->
                while (c.moveToNext()) {
                    val ts = c.getLong(0)
                    val g = c.getInt(1)
                    // Collapse 15-second duplicate polls of the same 5-minute sensor packet
                    if (raw.isNotEmpty() && raw.last().second == g && (ts - raw.last().first) < 260_000L) {
                        continue
                    }
                    raw.add(ts to g)
                }
            }
        return downsample(raw, maxPoints)
    }

    /**
     * Aggregate glucose stats for a window straight from SQLite.
     *
     * Long windows are downsampled before charting, but averaging samples first would pull values
     * toward the mean and inflate TIR, so the numbers shown to the user are always computed here
     * over the raw rows.
     */
    fun statsFor(fromMs: Long, toMs: Long, low: Int, high: Int): WindowStats? {
        readableDatabase
            .rawQuery(
                "SELECT COUNT(*), " +
                    "SUM(CASE WHEN glucose < ? THEN 1 ELSE 0 END), " +
                    "SUM(CASE WHEN glucose BETWEEN ? AND ? THEN 1 ELSE 0 END), " +
                    "AVG(glucose), MIN(glucose), MAX(glucose) " +
                    "FROM readings WHERE ts_ms BETWEEN ? AND ? AND glucose IS NOT NULL",
                arrayOf(
                    low.toString(),
                    low.toString(),
                    high.toString(),
                    fromMs.toString(),
                    toMs.toString(),
                ),
            )
            .use { c ->
                if (!c.moveToFirst()) return null
                val count = c.getInt(0)
                if (count <= 0) return null
                val below = c.getInt(1)
                val inRange = c.getInt(2)
                return WindowStats(
                    count = count,
                    belowCount = below,
                    inRangeCount = inRange,
                    aboveCount = count - below - inRange,
                    avg = c.getDouble(3),
                    min = c.getInt(4),
                    max = c.getInt(5),
                )
            }
    }

    fun countReadings(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM readings", null).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    fun countEvents(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM events", null).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    fun latestReading(): ReadingRow? {
        readableDatabase
            .rawQuery(
                "SELECT ts_ms, glucose, trend, roc, iob, basal, basal_auto, reservoir, battery, battery_charging " +
                    "FROM readings WHERE glucose IS NOT NULL ORDER BY ts_ms DESC LIMIT 1",
                null,
            )
            .use { c ->
                if (!c.moveToFirst()) return null
                return ReadingRow(
                    tsMs = c.getLong(0),
                    glucoseMgDl = if (c.isNull(1)) null else c.getInt(1),
                    trend = if (c.isNull(2)) null else c.getString(2),
                    roc = if (c.isNull(3)) null else c.getDouble(3),
                    iob = if (c.isNull(4)) null else c.getFloat(4),
                    basal = if (c.isNull(5)) null else c.getFloat(5),
                    basalAuto = if (c.isNull(6)) null else c.getInt(6) != 0,
                    reservoir = if (c.isNull(7)) null else c.getFloat(7),
                    battery = if (c.isNull(8)) null else c.getInt(8),
                    batteryCharging = if (c.isNull(9)) null else c.getInt(9) != 0,
                )
            }
    }

    fun queryReadings(fromMs: Long, toMs: Long): List<ReadingRow> {
        val out = ArrayList<ReadingRow>()
        readableDatabase
            .rawQuery(
                "SELECT ts_ms, glucose, trend, roc, iob, basal, basal_auto, reservoir, battery, battery_charging " +
                    "FROM readings WHERE ts_ms BETWEEN ? AND ? ORDER BY ts_ms ASC",
                arrayOf(fromMs.toString(), toMs.toString()),
            )
            .use { c ->
                while (c.moveToNext()) {
                    out.add(
                        ReadingRow(
                            tsMs = c.getLong(0),
                            glucoseMgDl = if (c.isNull(1)) null else c.getInt(1),
                            trend = if (c.isNull(2)) null else c.getString(2),
                            roc = if (c.isNull(3)) null else c.getDouble(3),
                            iob = if (c.isNull(4)) null else c.getFloat(4),
                            basal = if (c.isNull(5)) null else c.getFloat(5),
                            basalAuto = if (c.isNull(6)) null else c.getInt(6) != 0,
                            reservoir = if (c.isNull(7)) null else c.getFloat(7),
                            battery = if (c.isNull(8)) null else c.getInt(8),
                            batteryCharging = if (c.isNull(9)) null else c.getInt(9) != 0,
                        )
                    )
                }
            }
        return out
    }

    fun recentEvents(limit: Int = 200): List<EventRow> {
        val out = ArrayList<EventRow>()
        readableDatabase
            .rawQuery(
                "SELECT seq, ts_ms, type_id, text FROM events ORDER BY seq DESC LIMIT ?",
                arrayOf(limit.toString()),
            )
            .use { c ->
                while (c.moveToNext()) {
                    out.add(EventRow(c.getInt(0), c.getLong(1), c.getInt(2), c.getString(3) ?: ""))
                }
            }
        return out
    }

    /**
     * Writes `readings.csv` and `events.csv` into [dir] and returns the files that were written.
     * Values are always exported in mg/dL; mmol/L is derivable with the 18.0156 factor.
     */
    fun exportCsv(dir: File): List<File> {
        if (!dir.exists()) dir.mkdirs()
        val iso = DateTimeFormatter.ISO_INSTANT.withZone(ZoneId.of("UTC"))

        val readingsFile = File(dir, "readings.csv")
        readingsFile.bufferedWriter().use { w ->
            w.write(
                "timestamp_iso,epoch_ms,glucose_mgdl,glucose_mmol,trend,roc_mgdl_min,iob_u,basal_u_h,basal_automated,reservoir_u,battery_pct,battery_charging\n"
            )
            readableDatabase
                .rawQuery(
                    "SELECT ts_ms, glucose, trend, roc, iob, basal, basal_auto, reservoir, battery, battery_charging " +
                        "FROM readings ORDER BY ts_ms ASC",
                    null,
                )
                .use { c ->
                    while (c.moveToNext()) {
                        val ts = c.getLong(0)
                        val glucose = if (c.isNull(1)) null else c.getInt(1)
                        val mmol =
                            glucose?.let {
                                String.format(java.util.Locale.US, "%.2f", it / 18.0156)
                            } ?: ""
                        w.write(
                            listOf(
                                    iso.format(Instant.ofEpochMilli(ts)),
                                    ts.toString(),
                                    glucose?.toString() ?: "",
                                    mmol,
                                    c.getString(2) ?: "",
                                    if (c.isNull(3)) "" else c.getDouble(3).toString(),
                                    if (c.isNull(4)) "" else c.getFloat(4).toString(),
                                    if (c.isNull(5)) "" else c.getFloat(5).toString(),
                                    if (c.isNull(6)) "" else c.getInt(6).toString(),
                                    if (c.isNull(7)) "" else c.getFloat(7).toString(),
                                    if (c.isNull(8)) "" else c.getInt(8).toString(),
                                    if (c.isNull(9)) "" else c.getInt(9).toString(),
                                )
                                .joinToString(",")
                        )
                        w.write("\n")
                    }
                }
        }

        val eventsFile = File(dir, "events.csv")
        eventsFile.bufferedWriter().use { w ->
            w.write("sequence,type_id,timestamp_iso,epoch_ms,text\n")
            readableDatabase
                .rawQuery("SELECT seq, type_id, ts_ms, text FROM events ORDER BY seq ASC", null)
                .use { c ->
                    while (c.moveToNext()) {
                        val text = (c.getString(3) ?: "").replace("\"", "\"\"")
                        w.write(
                            "${c.getInt(0)},${c.getInt(1)},${iso.format(Instant.ofEpochMilli(c.getLong(2)))},${c.getLong(2)},\"$text\"\n"
                        )
                    }
                }
        }
        return listOf(readingsFile, eventsFile)
    }

    /**
     * Writes a single-file backup containing both readings and pump events.
     * The format is one CSV with a `record_type` discriminator and stable column names.
     */
    fun exportBackupCsv(dir: File): File {
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, "minimed_backup.csv")
        val iso = DateTimeFormatter.ISO_INSTANT.withZone(ZoneId.of("UTC"))
        file.bufferedWriter(Charsets.UTF_8).use { w ->
            w.write(
                "record_type,timestamp_iso,epoch_ms,glucose_mgdl,glucose_mmol,trend,roc_mgdl_min," +
                    "iob_u,basal_u_h,basal_automated,reservoir_u,battery_pct,battery_charging," +
                    "sequence,type_id,text\n"
            )
            readableDatabase
                .rawQuery(
                    "SELECT ts_ms, glucose, trend, roc, iob, basal, basal_auto, reservoir, battery, battery_charging " +
                        "FROM readings ORDER BY ts_ms ASC",
                    null,
                )
                .use { c ->
                    while (c.moveToNext()) {
                        val ts = c.getLong(0)
                        val glucose = if (c.isNull(1)) null else c.getInt(1)
                        w.write(
                            listOf(
                                    "reading",
                                    iso.format(Instant.ofEpochMilli(ts)),
                                    ts.toString(),
                                    glucose?.toString() ?: "",
                                    glucose?.let { String.format(java.util.Locale.US, "%.2f", it / 18.0156) } ?: "",
                                    c.getString(2) ?: "",
                                    if (c.isNull(3)) "" else c.getDouble(3).toString(),
                                    if (c.isNull(4)) "" else c.getFloat(4).toString(),
                                    if (c.isNull(5)) "" else c.getFloat(5).toString(),
                                    if (c.isNull(6)) "" else c.getInt(6).toString(),
                                    if (c.isNull(7)) "" else c.getFloat(7).toString(),
                                    if (c.isNull(8)) "" else c.getInt(8).toString(),
                                    if (c.isNull(9)) "" else c.getInt(9).toString(),
                                    "",
                                    "",
                                    "",
                                )
                                .joinToString(",", transform = ::csvEscape)
                        )
                        w.write("\n")
                    }
                }
            readableDatabase
                .rawQuery("SELECT seq, type_id, ts_ms, text FROM events ORDER BY seq ASC", null)
                .use { c ->
                    while (c.moveToNext()) {
                        val ts = c.getLong(2)
                        w.write(
                            listOf(
                                    "event",
                                    iso.format(Instant.ofEpochMilli(ts)),
                                    ts.toString(),
                                    "", "", "", "", "", "", "", "", "", "",
                                    c.getInt(0).toString(),
                                    c.getInt(1).toString(),
                                    c.getString(3) ?: "",
                                )
                                .joinToString(",", transform = ::csvEscape)
                        )
                        w.write("\n")
                    }
                }
        }
        return file
    }

    /**
     * Imports [input] as a single-file CSV backup and merges it into the local database.
     * Existing reading timestamps are skipped, events with the same sequence are replaced.
     */
    fun importBackupCsv(input: InputStream): ImportResult {
        val rows = parseCsv(input.bufferedReader(Charsets.UTF_8))
        if (rows.size < 2) return ImportResult(0, 0, 0, 0)
        val header =
            rows.first().map {
                it.trim().trimStart('\uFEFF').lowercase(java.util.Locale.US)
            }
        val columns = header.withIndex().associate { it.value to it.index }
        fun value(row: List<String>, name: String): String =
            columns[name]?.let { row.getOrNull(it)?.trim() ?: "" } ?: ""

        var readingsImported = 0
        var readingsSkipped = 0
        var eventsImported = 0
        var errors = 0
        val db = writableDatabase
        val existingTimestamps = HashSet<Long>()
        db.rawQuery("SELECT ts_ms FROM readings", null).use { c ->
            while (c.moveToNext()) existingTimestamps.add(c.getLong(0))
        }
        fun importReadingRow(row: List<String>) {
            val ts =
                value(row, "epoch_ms").toLongOrNull()
                    ?: run {
                        errors++
                        return
                    }
            val glucoseText = value(row, "glucose_mgdl")
            val glucose = glucoseText.toIntOrNull()
            if (glucoseText.isNotEmpty() && (glucose == null || glucose !in 20..500)) {
                errors++
                return
            }
            if (!existingTimestamps.add(ts)) {
                readingsSkipped++
                return
            }
            val cv =
                ContentValues().apply {
                    put("ts_ms", ts)
                    glucose?.let { put("glucose", it) }
                    value(row, "trend").takeIf { it.isNotEmpty() }?.let { put("trend", it) }
                    value(row, "roc_mgdl_min").toDoubleOrNull()?.let { put("roc", it) }
                    value(row, "iob_u").toFloatOrNull()?.let { put("iob", it) }
                    value(row, "basal_u_h").toFloatOrNull()?.let { put("basal", it) }
                    value(row, "basal_automated").toBooleanFlag()?.let {
                        put("basal_auto", if (it) 1 else 0)
                    }
                    value(row, "reservoir_u").toFloatOrNull()?.let { put("reservoir", it) }
                    value(row, "battery_pct").toIntOrNull()?.let { put("battery", it) }
                    value(row, "battery_charging").toBooleanFlag()?.let {
                        put("battery_charging", if (it) 1 else 0)
                    }
                }
            db.insert("readings", null, cv)
            readingsImported++
        }

        fun importEventRow(row: List<String>) {
            val seq = value(row, "sequence").toIntOrNull()
            val ts = value(row, "epoch_ms").toLongOrNull()
            if (seq == null || ts == null) {
                errors++
                return
            }
            val cv =
                ContentValues().apply {
                    put("seq", seq)
                    put("ts_ms", ts)
                    put("type_id", value(row, "type_id").toIntOrNull() ?: 0)
                    put("text", value(row, "text"))
                }
            db.insertWithOnConflict("events", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            eventsImported++
        }

        val hasBackup = "record_type" in columns
        val hasReadings = "glucose_mgdl" in columns && "epoch_ms" in columns
        val hasEvents = "sequence" in columns && "type_id" in columns && "epoch_ms" in columns

        db.beginTransaction()
        try {
            for (row in rows.drop(1)) {
                if (row.all { it.isBlank() }) continue
                when {
                    hasBackup ->
                        when (value(row, "record_type").lowercase(java.util.Locale.US)) {
                            "reading" -> importReadingRow(row)
                            "event" -> importEventRow(row)
                            else -> errors++
                        }
                    hasReadings -> importReadingRow(row)
                    hasEvents -> importEventRow(row)
                    else -> errors++
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return ImportResult(readingsImported, readingsSkipped, eventsImported, errors)
    }

    private fun csvEscape(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }

    private fun String.toBooleanFlag(): Boolean? =
        when (lowercase(java.util.Locale.US)) {
            "1", "true", "yes" -> true
            "0", "false", "no" -> false
            else -> null
        }

    private fun parseCsv(reader: BufferedReader): List<List<String>> {
        val rows = ArrayList<List<String>>()
        val row = ArrayList<String>()
        val field = StringBuilder()
        var inQuotes = false
        var ch = reader.read()
        while (ch != -1) {
            val c = ch.toChar()
            if (inQuotes) {
                if (c == '"') {
                    val next = reader.read()
                    if (next == '"'.code) {
                        field.append('"')
                        ch = reader.read()
                        continue
                    }
                    inQuotes = false
                    ch = next
                    continue
                }
                field.append(c)
                ch = reader.read()
            } else {
                when (c) {
                    '"' -> {
                        inQuotes = true
                        ch = reader.read()
                    }
                    ',' -> {
                        row.add(field.toString())
                        field.setLength(0)
                        ch = reader.read()
                    }
                    '\n' -> {
                        row.add(field.toString())
                        field.setLength(0)
                        rows.add(row.toList())
                        row.clear()
                        ch = reader.read()
                    }
                    '\r' -> ch = reader.read()
                    else -> {
                        field.append(c)
                        ch = reader.read()
                    }
                }
            }
        }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row.add(field.toString())
            rows.add(row.toList())
        }
        return rows
    }

    private fun downsample(points: List<Pair<Long, Int>>, maxPoints: Int): List<Pair<Long, Int>> {
        if (points.size <= maxPoints || maxPoints <= 1) return points
        val out = ArrayList<Pair<Long, Int>>(maxPoints)
        // Fixed-width proportional buckets: indexing by float steps used to collapse to one sample
        // per bucket whenever size / maxPoints < 2, so nothing was actually reduced.
        for (k in 0 until maxPoints) {
            val start = (k.toLong() * points.size / maxPoints).toInt()
            val end = ((k + 1).toLong() * points.size / maxPoints).toInt().coerceAtLeast(start + 1)
            var sum = 0L
            var count = 0
            for (j in start until minOf(end, points.size)) {
                sum += points[j].second
                count++
            }
            if (count > 0) out.add(points[start].first to (sum / count).toInt())
        }
        return out
    }

    private companion object {
        const val DB_NAME = "minimed.db"
        const val DB_VERSION = 1
    }
}
