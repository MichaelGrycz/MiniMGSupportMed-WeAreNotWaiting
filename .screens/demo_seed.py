import math
import sqlite3
import time
import uuid

path = ".screens/minimed.db"
db = sqlite3.connect(path)
db.executescript(
    """
    CREATE TABLE readings (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      ts_ms INTEGER NOT NULL, glucose INTEGER, trend TEXT, roc REAL, iob REAL,
      basal REAL, basal_auto INTEGER, reservoir REAL, battery INTEGER,
      battery_charging INTEGER
    );
    CREATE INDEX idx_readings_ts ON readings(ts_ms);
    CREATE TABLE events (seq INTEGER PRIMARY KEY, ts_ms INTEGER NOT NULL, type_id INTEGER, text TEXT);
    CREATE INDEX idx_events_ts ON events(ts_ms);
    ALTER TABLE readings ADD COLUMN backup_id TEXT;
    CREATE UNIQUE INDEX idx_readings_backup_id ON readings(backup_id);
    CREATE TABLE history_records (
      source_id TEXT NOT NULL, seq INTEGER NOT NULL, type_id INTEGER NOT NULL,
      pump_time_seconds INTEGER NOT NULL, raw_bytes_b64 TEXT NOT NULL,
      PRIMARY KEY(source_id, seq)
    );
    CREATE TABLE app_alerts (id TEXT PRIMARY KEY NOT NULL, ts_ms INTEGER NOT NULL, glucose INTEGER NOT NULL, text TEXT NOT NULL);
    CREATE INDEX idx_app_alerts_ts ON app_alerts(ts_ms);
    CREATE TABLE history_cursors (source_id TEXT PRIMARY KEY NOT NULL, last_seq INTEGER NOT NULL, updated_ms INTEGER NOT NULL);
    PRAGMA user_version=3;
    """
)

now_ms = int(time.time() * 1000)
step_ms = 5 * 60 * 1000
count = 100
rows = []
for i in range(count, 0, -1):
    ts = now_ms - i * step_ms
    base = 118 + 12 * math.sin((count - i) / 7.0)
    if i <= 6:
        base = 150 + (6 - i) * 4
    glucose = int(round(base))
    trend = "SINGLE_UP" if i <= 6 else ("FLAT" if i % 2 == 0 else "FORTY_FIVE_UP")
    rows.append(
        (
            ts,
            glucose,
            trend,
            1.0 if i <= 6 else 0.0,
            1.8,
            0.8,
            1,
            42.0,
            82,
            0,
            uuid.uuid4().hex,
        )
    )

db.executemany(
    "INSERT INTO readings (ts_ms, glucose, trend, roc, iob, basal, basal_auto, reservoir, battery, battery_charging, backup_id) "
    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
    rows,
)
db.commit()
db.close()
print("seeded", len(rows), "readings")
