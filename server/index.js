const express = require("express");
const app = express();
const PORT = process.env.PORT || 3000;

app.use(express.json({ limit: "1mb" }));

/**
 * Izolowane "rodziny" (kody parowania). Każdy kod ma własny najnowszy odczyt i historię,
 * więc wiele rodzin może korzystać z jednego serwera bez mieszania danych.
 */
const families = new Map();
const diagnosticLogs = [];
const MAX_HISTORY = 200;
const MAX_LOGS = 50;

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
    families.set(code, { latest: null, history: [] });
  }
  return families.get(code);
}

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
  if (state.history.length > MAX_HISTORY) state.history.pop();

  console.log(`[Telemetry][${code}] Glucose: ${record.glucoseMgDl} mg/dL, Trend: ${record.trend}, IOB: ${record.iob}U`);
  res.json({ status: "ok", family: code, receivedAt: record.receivedAt });
});

// 2. Automatyczny upload logów diagnostycznych z aplikacji w razie błędu BLE
app.post("/api/logs", (req, res) => {
  const { deviceId, report } = req.body;
  if (!report) {
    return res.status(400).json({ error: "Missing report" });
  }
  const code = familyCode(req);
  const logEntry = {
    id: Date.now(),
    receivedAt: new Date().toISOString(),
    family: code,
    deviceId: deviceId || "anonymous",
    report,
  };
  diagnosticLogs.unshift(logEntry);
  if (diagnosticLogs.length > MAX_LOGS) diagnosticLogs.pop();

  console.log(`[Diagnostic Log][${code}] Received report from device: ${logEntry.deviceId}`);
  res.json({ status: "ok", family: code, logId: logEntry.id });
});

// 3. Pobranie najnowszego odczytu dla danej rodziny
app.get("/api/latest", (req, res) => {
  const code = familyCode(req);
  const state = families.get(code);
  res.json(state && state.latest ? { family: code, ...state.latest } : { family: code, status: "no_data" });
});

// 4. Prosty dashboard HTML dla rodziny (?family=123456)
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
  res.json({ status: "ok", families: families.size, uptimeSeconds: Math.round(process.uptime()) });
});

app.listen(PORT, () => {
  console.log(`[MGlycemia Server] Running on port ${PORT}`);
});
