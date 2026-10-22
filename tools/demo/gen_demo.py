import csv
import math
import random
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo

random.seed(42)
tz = ZoneInfo("Europe/Warsaw")
end = datetime.now(tz).replace(second=0, microsecond=0)
start = end - timedelta(days=365)
step = timedelta(minutes=15)


def meal(hour, center, amplitude, width=0.9):
    return amplitude * math.exp(-(((hour - center) / width) ** 2))


rows = []
prev = None
t = start
reservoir = 240.0
# Real CGM noise is autocorrelated: a slow wander plus a tiny per-sample jitter. Independent
# gaussian noise per sample produces a sawtooth that no real sensor outputs.
noise = 0.0
while t <= end:
    h = t.hour + t.minute / 60
    day = (t - start).days
    base = 116 + 18 * math.sin(2 * math.pi * (h - 6) / 24)
    noise = 0.9 * noise + random.gauss(0, 2.6)
    glucose = (
        base
        + meal(h, 7.5, 55, 1.25)
        + meal(h, 13.0, 58, 1.45)
        + meal(h, 18.5, 62, 1.45)
        + meal(h, 21.0, 26, 1.1)
    )
    glucose += noise + random.gauss(0, 1.2)
    if day % 13 == 0 and 2 <= h <= 3.5:
        glucose = min(glucose, random.uniform(55, 68))
    if day % 11 == 0 and 19 <= h <= 20.5:
        glucose = max(glucose, random.uniform(210, 280))
    glucose = int(max(45, min(300, round(glucose))))

    if prev is None:
        trend = "FLAT"
        roc = 0.0
    else:
        rate = (glucose - prev) / 15.0
        roc = round(rate, 2)
        if abs(rate) < 0.5:
            trend = "FLAT"
        elif rate >= 3:
            trend = "DOUBLE_UP"
        elif rate >= 2:
            trend = "SINGLE_UP"
        elif rate >= 0.7:
            trend = "FORTY_FIVE_UP"
        elif rate <= -3:
            trend = "DOUBLE_DOWN"
        elif rate <= -2:
            trend = "SINGLE_DOWN"
        elif rate <= -0.7:
            trend = "FORTY_FIVE_DOWN"
        else:
            trend = "FLAT"

    iob = (
        0.7 + meal(h, 8.5, 1.8, 1.2) + meal(h, 14.0, 2.0, 1.2) + meal(h, 19.5, 2.2, 1.3)
    )
    iob = round(min(5.0, iob + random.uniform(-0.1, 0.1)), 2)
    basal = round(
        0.85
        + 0.15 * math.sin(2 * math.pi * (h - 3) / 24)
        + random.uniform(-0.03, 0.03),
        2,
    )
    reservoir -= 0.18
    if reservoir < 15:
        reservoir = 250.0
    battery = 88 if day % 7 != 0 else 75

    epoch_ms = int(t.timestamp() * 1000)
    iso = t.astimezone(ZoneInfo("UTC")).strftime("%Y-%m-%dT%H:%M:%SZ")
    mmol = f"{glucose / 18.0156:.2f}"
    rows.append(
        [
            "reading",
            iso,
            str(epoch_ms),
            str(glucose),
            mmol,
            trend,
            f"{roc:.2f}",
            f"{iob:.2f}",
            f"{basal:.2f}",
            "1",
            f"{reservoir:.0f}",
            str(battery),
            "0",
            "",
            "",
            "",
        ]
    )
    prev = glucose
    t += step

with open("demo_year_backup.csv", "w", newline="", encoding="utf-8") as f:
    w = csv.writer(f)
    w.writerow(
        [
            "record_type",
            "timestamp_iso",
            "epoch_ms",
            "glucose_mgdl",
            "glucose_mmol",
            "trend",
            "roc_mgdl_min",
            "iob_u",
            "basal_u_h",
            "basal_automated",
            "reservoir_u",
            "battery_pct",
            "battery_charging",
            "sequence",
            "type_id",
            "text",
        ]
    )
    w.writerows(rows)

values = [int(r[3]) for r in rows]
n = len(values)
tir = sum(1 for x in values if 70 <= x <= 180) * 100 / n
below = sum(1 for x in values if x < 70) * 100 / n
above = 100 - tir - below
avg = sum(values) / n
gmi = 3.31 + 0.02392 * avg
print(
    f"rows={n} avg={avg:.0f} TIR={tir:.1f}% below={below:.1f}% "
    f"above={above:.1f}% GMI={gmi:.1f}% min={min(values)} max={max(values)}"
)
