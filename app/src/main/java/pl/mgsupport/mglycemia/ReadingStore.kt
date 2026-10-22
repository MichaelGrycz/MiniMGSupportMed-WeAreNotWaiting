package pl.mgsupport.mglycemia

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import pl.mgsupport.mglycemia.core.domain.model.HistoryLogRecord
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
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
    val historyRecordsImported: Int = 0,
    val appAlertsImported: Int = 0,
)

/**
 * Local SQLite store for long-term readings and pump events, plus CSV export.
 *
 * Readings are written once per CGM sample (~5 min) so a year is ~100k rows — comfortably small.
 * CSV export produces `readings.csv` / `events.csv` in the app-specific external directory, which
 * needs no storage permission and can be shared via FileProvider.
 */
class ReadingStore(context: Context, databaseName: String = DB_NAME) :
    SQLiteOpenHelper(context.applicationContext, databaseName, null, DB_VERSION) {

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
        addHistorySchema(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // SQLiteOpenHelper wraps this additive migration in a transaction. No legacy row is dropped.
        if (oldVersion < 2) addHistorySchema(db)
    }

    private fun addHistorySchema(db: SQLiteDatabase) {
        // Stable identity preserves even legacy samples sharing a timestamp on backup round-trips.
        db.execSQL("ALTER TABLE readings ADD COLUMN backup_id TEXT")
        db.execSQL("UPDATE readings SET backup_id = lower(hex(randomblob(16))) WHERE backup_id IS NULL")
        db.execSQL("CREATE UNIQUE INDEX idx_readings_backup_id ON readings(backup_id)")
        db.execSQL(
            """
            CREATE TABLE history_records (
              source_id TEXT NOT NULL,
              seq INTEGER NOT NULL,
              type_id INTEGER NOT NULL,
              pump_time_seconds INTEGER NOT NULL,
              raw_bytes_b64 TEXT NOT NULL,
              PRIMARY KEY(source_id, seq)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE app_alerts (
              id TEXT PRIMARY KEY NOT NULL,
              ts_ms INTEGER NOT NULL,
              glucose INTEGER NOT NULL,
              text TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_app_alerts_ts ON app_alerts(ts_ms)")
    }

    /** Returns only after the entire raw batch commits; callers may advance their cursor afterwards. */
    fun insertHistoryRecords(sourceId: String, records: List<HistoryLogRecord>) {
        require(sourceId.isNotBlank()) { "A stable pump/log source ID is required" }
        if (records.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (record in records) insertHistoryRow(db, StoredHistoryRecord(sourceId, record))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Maximum persisted sequence, compared as the wire's unsigned 32-bit value. Not a gap tracker. */
    fun latestHistorySequence(sourceId: String): Int? = readableDatabase.rawQuery(
        "SELECT seq FROM history_records WHERE source_id = ? ORDER BY (seq & 4294967295) DESC LIMIT 1",
        arrayOf(sourceId),
    ).use { if (it.moveToFirst()) it.getInt(0) else null }

    fun queryInsulinEvents(fromMs: Long, toMs: Long): List<ChartInsulinEvent> {
        // Include anchors and P1/P2 outside the requested window and across earlier download batches.
        val records = ArrayList<StoredHistoryRecord>()
        readableDatabase.rawQuery(
            "SELECT source_id, seq, type_id, pump_time_seconds, raw_bytes_b64 FROM history_records", null,
        ).use { c ->
            while (c.moveToNext()) records += StoredHistoryRecord(
                c.getString(0), HistoryLogRecord(c.getInt(1), c.getString(4), c.getInt(2), c.getLong(3)),
            )
        }
        return HistoryTimeline.project(records, fromMs, toMs)
    }

    fun insertAppAlert(timestampMs: Long, glucoseMgDl: Int, text: String) {
        insertAlertRow(writableDatabase, AppAlertRow(UUID.randomUUID().toString(), timestampMs, glucoseMgDl, text))
    }

    fun queryAppAlerts(fromMs: Long, toMs: Long): List<AppAlertRow> = readableDatabase.rawQuery(
        "SELECT id, ts_ms, glucose, text FROM app_alerts WHERE ts_ms BETWEEN ? AND ? ORDER BY ts_ms, id",
        arrayOf(fromMs.toString(), toMs.toString()),
    ).use { c ->
        buildList { while (c.moveToNext()) add(AppAlertRow(c.getString(0), c.getLong(1), c.getInt(2), c.getString(3))) }
    }

    private fun insertHistoryRow(db: SQLiteDatabase, stored: StoredHistoryRecord): Boolean {
        val r = stored.record
        val cv = ContentValues().apply {
            put("source_id", stored.sourceId); put("seq", r.sequenceNumber); put("type_id", r.eventTypeId)
            put("pump_time_seconds", r.pumpTimeSeconds); put("raw_bytes_b64", r.rawBytesB64)
        }
        val inserted = db.insertWithOnConflict("history_records", null, cv, SQLiteDatabase.CONFLICT_IGNORE) != -1L
        if (!inserted) {
            db.rawQuery(
                "SELECT type_id, pump_time_seconds, raw_bytes_b64 FROM history_records WHERE source_id = ? AND seq = ?",
                arrayOf(stored.sourceId, r.sequenceNumber.toString()),
            ).use { c ->
                check(c.moveToFirst() && c.getInt(0) == r.eventTypeId && c.getLong(1) == r.pumpTimeSeconds && c.getString(2) == r.rawBytesB64) {
                    "Conflicting raw history identity; use a new source ID after a pump log reset"
                }
            }
        }
        return inserted
    }

    private fun insertAlertRow(db: SQLiteDatabase, row: AppAlertRow): Boolean {
        val cv = ContentValues().apply {
            put("id", row.id); put("ts_ms", row.timestampMs); put("glucose", row.glucoseMgDl); put("text", row.text)
        }
        val inserted = db.insertWithOnConflict("app_alerts", null, cv, SQLiteDatabase.CONFLICT_IGNORE) != -1L
        if (!inserted) db.rawQuery("SELECT ts_ms, glucose, text FROM app_alerts WHERE id = ?", arrayOf(row.id)).use { c ->
            check(c.moveToFirst() && c.getLong(0) == row.timestampMs && c.getInt(1) == row.glucoseMgDl && c.getString(2) == row.text) {
                "Conflicting app alert identity"
            }
        }
        return inserted
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
                put("backup_id", UUID.randomUUID().toString())
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
        db.insertOrThrow("readings", null, cv)
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

    /** Single-file v2 backup: all samples, legacy events, raw history, sources and app alerts. */
    fun exportBackupCsv(dir: File): File {
        check(dir.isDirectory || dir.mkdirs()) { "Cannot create backup directory" }
        return File(dir, "minimed_backup.csv").also { file ->
            file.outputStream().use { exportBackupCsv(it) }
        }
    }

    /** Streams a consistent snapshot. The caller owns and must successfully close [output]. */
    fun exportBackupCsv(output: OutputStream, createdMs: Long = System.currentTimeMillis()) {
        val db = writableDatabase
        db.beginTransactionNonExclusive()
        try {
            val csv = BackupCsv.Encoder(output.bufferedWriter(Charsets.UTF_8), createdMs)
            db.rawQuery(
                "SELECT ts_ms, glucose, trend, roc, iob, basal, basal_auto, reservoir, battery, battery_charging, backup_id " +
                    "FROM readings ORDER BY ts_ms, id", null,
            ).use { c ->
                while (c.moveToNext()) csv.write(BackupRow.Reading(c.getString(10), readingFromCursor(c)))
            }
            db.rawQuery("SELECT seq, ts_ms, type_id, text FROM events ORDER BY seq", null).use { c ->
                while (c.moveToNext()) csv.write(BackupRow.Event(EventRow(c.getInt(0), c.getLong(1), c.getInt(2), c.getString(3).orEmpty())))
            }
            db.rawQuery(
                "SELECT source_id, seq, type_id, pump_time_seconds, raw_bytes_b64 FROM history_records ORDER BY source_id, (seq & 4294967295)", null,
            ).use { c ->
                while (c.moveToNext()) csv.write(BackupRow.History(StoredHistoryRecord(
                    c.getString(0), HistoryLogRecord(c.getInt(1), c.getString(4), c.getInt(2), c.getLong(3)),
                )))
            }
            db.rawQuery("SELECT id, ts_ms, glucose, text FROM app_alerts ORDER BY ts_ms, id", null).use { c ->
                while (c.moveToNext()) csv.write(BackupRow.Alert(AppAlertRow(c.getString(0), c.getLong(1), c.getInt(2), c.getString(3))))
            }
            csv.finish()
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Merges legacy readings.csv/events.csv/v1 backups and v2. V2 reading IDs preserve multiple
     * samples at the same timestamp; legacy files keep timestamp deduplication. No CGM poll collapse
     * occurs during import. Truncated/corrupt v2 files roll back the ENTIRE import, including cursors.
     * Streams remain owned by the caller. Invalid legacy rows are counted in [ImportResult.errors].
     */
    fun importBackupCsv(input: InputStream): ImportResult {
        var readingsImported = 0
        var readingsSkipped = 0
        var eventsImported = 0
        var historyImported = 0
        var alertsImported = 0
        val db = writableDatabase
        db.beginTransaction()
        try {
            val info = BackupCsv.read(input.reader(Charsets.UTF_8)) { row ->
                when (row) {
                    is BackupRow.Reading -> {
                        val exists = if (row.id != null) db.rawQuery(
                            "SELECT 1 FROM readings WHERE backup_id = ? LIMIT 1", arrayOf(row.id),
                        ).use { it.moveToFirst() } else db.rawQuery(
                            "SELECT 1 FROM readings WHERE ts_ms = ? LIMIT 1", arrayOf(row.value.tsMs.toString()),
                        ).use { it.moveToFirst() }
                        if (exists) readingsSkipped++ else {
                            val cv = readingValues(row.value).apply { put("backup_id", row.id ?: UUID.randomUUID().toString()) }
                            db.insertOrThrow("readings", null, cv)
                            readingsImported++
                        }
                    }
                    is BackupRow.Event -> {
                        val cv = ContentValues().apply {
                            put("seq", row.value.seq); put("ts_ms", row.value.tsMs)
                            put("type_id", row.value.typeId); put("text", row.value.text)
                        }
                        check(db.insertWithOnConflict("events", null, cv, SQLiteDatabase.CONFLICT_REPLACE) != -1L)
                        eventsImported++
                    }
                    is BackupRow.History -> if (insertHistoryRow(db, row.value)) historyImported++
                    is BackupRow.Alert -> if (insertAlertRow(db, row.value)) alertsImported++
                }
            }
            db.setTransactionSuccessful()
            return ImportResult(readingsImported, readingsSkipped, eventsImported, info.errors, historyImported, alertsImported)
        } finally {
            db.endTransaction()
        }
    }

    private fun readingFromCursor(c: android.database.Cursor) = ReadingRow(
        c.getLong(0), if (c.isNull(1)) null else c.getInt(1), c.getString(2),
        if (c.isNull(3)) null else c.getDouble(3), if (c.isNull(4)) null else c.getFloat(4),
        if (c.isNull(5)) null else c.getFloat(5), if (c.isNull(6)) null else c.getInt(6) != 0,
        if (c.isNull(7)) null else c.getFloat(7), if (c.isNull(8)) null else c.getInt(8),
        if (c.isNull(9)) null else c.getInt(9) != 0,
    )

    private fun readingValues(r: ReadingRow) = ContentValues().apply {
        put("ts_ms", r.tsMs); put("glucose", r.glucoseMgDl); put("trend", r.trend); put("roc", r.roc)
        put("iob", r.iob); put("basal", r.basal); put("basal_auto", r.basalAuto?.let { if (it) 1 else 0 })
        put("reservoir", r.reservoir); put("battery", r.battery)
        put("battery_charging", r.batteryCharging?.let { if (it) 1 else 0 })
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
        const val DB_VERSION = 2
    }
}
