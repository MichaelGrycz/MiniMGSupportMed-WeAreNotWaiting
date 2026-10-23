const express = require("express");
const fs = require("fs");
const path = require("path");
const app = express();
const PORT = process.env.PORT || 3000;
const DATA_DIR = process.env.DATA_DIR || "/var/lib/mglycemia";
const FAMILIES_FILE = path.join(DATA_DIR, "families.json");
const LOGS_FILE = path.join(DATA_DIR, "logs.json");

app.use(express.json({ limit: "1mb" }));

// Twarde limity, żeby dane nie rosły bez końca.
const MAX_HISTORY = 120; // ostatnie odczyty na rodzinę
const MAX_FAMILIES = 500; // maksymalna liczba rodzin na serwerze
const MAX_LOGS = 30; // ostatnie raporty diagnostyczne
const MAX_LOG_CHARS = 200_000; // maks. długość pojedynczego raportu
const HISTORY_TTL_MS = 48 * 3600 * 1000; // historia starsza niż 48 h jest usuwana
const FAMILY_IDLE_MS = 30 * 24 * 3600 * 1000; // rodzina bez ruchu 30 dni jest usuwana
const LOG_MAX_AGE_MS = 30 * 24 * 3600 * 1000; // logi starsze niż 30 dni są usuwane
const PERSIST_DEBOUNCE_MS = 5_000; // zapis na dysk najwyżej co 5 s

/** Izolowane "rodziny" (kody parowania). Każdy kod ma własny odczyt i historię. */
let families = new Map();
let diagnosticLogs = [];
let persistTimer = null;

function loadJson(file, fallback) {
  try {
    return JSON.parse(fs.readFileSync(file, "utf8"));
  } catch (_) {
    return fallback;
  }
}

function persist(file, value) {
  try {
    fs.mkdirSync(DATA_DIR, { recursive: true });
    const tmp = `${file}.tmp`;
    fs.writeFileSync(tmp, JSON.stringify(value), "utf8");
    fs.renameSync(tmp, file);
  } catch (error) {
    console.error(`[MGlycemia Server] Cannot persist ${file}:`, error.message);
  }
}

function persistFamiliesNow() {
  if (persistTimer) {
    clearTimeout(persistTimer);
    persistTimer = null;
  }
  persist(FAMILIES_FILE, Object.fromEntries(families));
}

function schedulePersistFamilies() {
  if (persistTimer) return;
  persistTimer = setTimeout(() => {
    persistTimer = null;
    persist(FAMILIES_FILE, Object.fromEntries(families));
  }, PERSIST_DEBOUNCE_MS);
}

function load() {
  const storedFamilies = loadJson(FAMILIES_FILE, {});
  families = new Map(Object.entries(storedFamilies));
  diagnosticLogs = loadJson(LOGS_FILE, []);
  prune();
}

function normalizeFamily(value) {
  const raw = String(value || "")
    .trim()
    .toLowerCase()
    .replace(/[^a-z0-9]/g, "");
  return raw.length >= 4 && raw.length <= 16 ? raw : "default";
}

function familyCode(req) {
  return normalizeFamily(req.query.family || (req.body && req.body.familyId));
}

function family(code) {
  if (!families.has(code)) {
    families.set(code, { latest: null, history: [], updatedAt: Date.now() });
  }
  return families.get(code);
}

function prune() {
  const now = Date.now();
  for (const [code, state] of families) {
    if (!state || typeof state !== "object") {
      families.delete(code);
      continue;
    }
    if (!Array.isArray(state.history)) state.history = [];
    // Migracja starych danych: brak updatedAt nie może usuwać istniejącej rodziny.
    if (!Number.isFinite(Number(state.updatedAt)) || Number(state.updatedAt) <= 0) {
      state.updatedAt = Number((state.latest && state.latest.timestampMs) || Date.now());
    }
    state.history = state.history
      .filter((entry) => now - Number(entry.timestampMs || 0) < HISTORY_TTL_MS)
      .slice(0, MAX_HISTORY);
    if (now - Number(state.updatedAt || 0) > FAMILY_IDLE_MS) {
      families.delete(code);
    }
  }
  if (families.size > MAX_FAMILIES) {
    const oldest = [...families.entries()]
      .sort((a, b) => Number(a[1].updatedAt || 0) - Number(b[1].updatedAt || 0))
      .slice(0, families.size - MAX_FAMILIES);
    for (const [code] of oldest) families.delete(code);
  }
  diagnosticLogs = diagnosticLogs
    .filter((entry) => now - Date.parse(entry.receivedAt || 0) < LOG_MAX_AGE_MS)
    .slice(0, MAX_LOGS);
}

function fileBytes(file) {
  try {
    return fs.statSync(file).size;
  } catch (_) {
    return 0;
  }
}

load();

// 1. Zapis telemetrii z aplikacji Android
app.post("/api/telemetry", (req, res) => {
  const data = req.body;
  if (!data) {
    return res.status(400).json({ error: "Missing body" });
  }
  const code = familyCode(req);
  const state = family(code);
  const record = {
    receivedAt: new Date().toISOString(),
    glucoseMgDl: data.glucoseMgDl,
    trend: data.trend,
    timestampMs: data.timestampMs || Date.now(),
    iob: data.iob,
    basalRate: data.basalRate,
    reservoirUnits: data.reservoirUnits,
    batteryPercent: data.batteryPercent,
    pumpModel: data.pumpModel,
  };
  state.latest = record;
  state.history.unshift(record);
  state.updatedAt = Date.now();
  prune();
  schedulePersistFamilies();

  console.log(`[Telemetry][${code}] Glucose: ${record.glucoseMgDl} mg/dL, Trend: ${record.trend}, IOB: ${record.iob}U`);
  res.json({ status: "ok", family: code, receivedAt: record.receivedAt });
});

// 2. Automatyczny upload logów diagnostycznych (przycinany i limitowany)
app.post("/api/logs", (req, res) => {
  const { deviceId, report } = req.body;
  if (!report) {
    return res.status(400).json({ error: "Missing report" });
  }
  const code = familyCode(req);
  const trimmed = String(report).slice(0, MAX_LOG_CHARS);
  const logEntry = {
    id: `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
    receivedAt: new Date().toISOString(),
    family: code,
    deviceId: deviceId || "anonymous",
    report: trimmed,
  };
  diagnosticLogs.unshift(logEntry);
  prune();
  persist(LOGS_FILE, diagnosticLogs);

  console.log(`[Diagnostic Log][${code}] Received report from device: ${logEntry.deviceId}`);
  res.json({ status: "ok", family: code, logId: logEntry.id, truncated: trimmed.length < String(report).length });
});

// 3. Pobranie najnowszego odczytu dla danej rodziny
app.get("/api/latest", (req, res) => {
  const code = familyCode(req);
  const state = families.get(code);
  res.json(state && state.latest ? { family: code, ...state.latest } : { family: code, status: "no_data" });
});

// 4. Prosty dashboard HTML dla rodziny (?family=...)
app.get("/", (req, res) => {
  const code = familyCode(req);
  const state = families.get(code);
  const r = state ? state.latest : null;
  const glucose = r ? r.glucoseMgDl : "—";
  const trend = r ? r.trend || "" : "";
  const time = r ? new Date(r.timestampMs).toLocaleTimeString() : "Brak danych";
  const iob = r && r.iob !== undefined ? `${r.iob} j.` : "—";
  const reservoir = r && r.reservoirUnits !== undefined ? `${r.reservoirUnits} j.` : "—";
  const battery = r && r.batteryPercent !== undefined ? `${r.batteryPercent}%` : "—";

  res.send(`<!DOCTYPE html>
<html lang="pl">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>MGlycemia - Podgląd Glikemii</title>
    <meta http-equiv="refresh" content="30">
    <style>
        body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; background: #0b0f17; color: #f8fafc; text-align: center; padding: 40px 20px; margin: 0; }
        .card { background: #121926; border: 1px solid #233044; border-radius: 20px; max-width: 400px; margin: 0 auto; padding: 30px; box-shadow: 0 10px 25px rgba(0,0,0,0.5); }
        .glucose { font-size: 72px; font-weight: bold; color: #22d3ee; margin: 10px 0; }
        .trend { font-size: 36px; color: #38bdf8; }
        .time { color: #94a3b8; font-size: 14px; margin-bottom: 20px; }
        .stats { display: flex; justify-content: space-around; background: #0f1520; border-radius: 12px; padding: 15px; margin-top: 20px; font-size: 15px; }
        .stat-val { font-weight: bold; color: #f8fafc; }
        .footer { margin-top: 30px; color: #64748b; font-size: 12px; }
        .code { margin-top: 10px; color: #64748b; font-size: 12px; }
    </style>
</head>
<body>
    <div class="card">
        <h2>MGlycemia · Podgląd Rodziny</h2>
        <div class="time">Ostatni pomiar: ${time}</div>
        <div class="glucose">${glucose} <span class="trend">${trend}</span></div>
        <div style="color: #94a3b8; font-size: 14px;">mg/dL</div>

        <div class="stats">
            <div>IOB<br><span class="stat-val">${iob}</span></div>
            <div>Zbiornik<br><span class="stat-val">${reservoir}</span></div>
            <div>Bateria<br><span class="stat-val">${battery}</span></div>
        </div>
        <div class="code">Kod rodziny: ${code}</div>
    </div>
    <div class="footer">Automatyczne odświeżanie co 30 sekund. Projekt GPL-3.0.</div>
</body>
</html>`);
});

app.get("/api/health", (_req, res) => {
  res.json({
    status: "ok",
    families: families.size,
    logs: diagnosticLogs.length,
    familiesBytes: fileBytes(FAMILIES_FILE),
    logsBytes: fileBytes(LOGS_FILE),
    uptimeSeconds: Math.round(process.uptime()),
  });
});

function shutdown(signal) {
  persistFamiliesNow();
  persist(LOGS_FILE, diagnosticLogs);
  console.log(`[MGlycemia Server] ${signal}: data flushed`);
  process.exit(0);
}

process.on("SIGTERM", () => shutdown("SIGTERM"));
process.on("SIGINT", () => shutdown("SIGINT"));

app.listen(PORT, () => {
  console.log(`[MGlycemia Server] Running on port ${PORT}, data in ${DATA_DIR}`);
});
