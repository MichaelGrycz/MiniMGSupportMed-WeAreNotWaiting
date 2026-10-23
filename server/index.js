const express = require('express');
const app = express();
const PORT = process.env.PORT || 3000;

app.use(express.json({ limit: '1mb' }));

// In-memory store (ostatni odczyt, lista ostatnich 100 odczytów, ostatnie logi diagnostyczne)
let latestReading = null;
const readingsHistory = [];
const diagnosticLogs = [];

// 1. Zapis telemetrii z aplikacji Android
app.post('/api/telemetry', (req, res) => {
    const data = req.body;
    if (!data) {
        return res.status(400).json({ error: 'Missing body' });
    }
    const record = {
        receivedAt: new Date().toISOString(),
        glucoseMgDl: data.glucoseMgDl,
        trend: data.trend,
        timestampMs: data.timestampMs || Date.now(),
        iob: data.iob,
        basalRate: data.basalRate,
        reservoirUnits: data.reservoirUnits,
        batteryPercent: data.batteryPercent,
        pumpModel: data.pumpModel
    };
    latestReading = record;
    readingsHistory.unshift(record);
    if (readingsHistory.length > 200) readingsHistory.pop();

    console.log(`[Telemetry] Glucose: ${record.glucoseMgDl} mg/dL, Trend: ${record.trend}, IOB: ${record.iob}U`);
    res.json({ status: 'ok', receivedAt: record.receivedAt });
});

// 2. Automatyczny upload logów diagnostycznych z aplikacji w razie błędu BLE
app.post('/api/logs', (req, res) => {
    const { deviceId, report, timestamp } = req.body;
    if (!report) {
        return res.status(400).json({ error: 'Missing report' });
    }
    const logEntry = {
        id: Date.now(),
        receivedAt: new Date().toISOString(),
        deviceId: deviceId || 'anonymous',
        report
    };
    diagnosticLogs.unshift(logEntry);
    if (diagnosticLogs.length > 50) diagnosticLogs.pop();

    console.log(`[Diagnostic Log] Received report from device: ${logEntry.deviceId}`);
    res.json({ status: 'ok', logId: logEntry.id });
});

// 3. Pobranie najnowszego odczytu (dla widgetów / api zewnętrznych)
app.get('/api/latest', (req, res) => {
    res.json(latestReading || { status: 'no_data' });
});

// 4. Prosty, czytelny Dashboard HTML dla rodziny, mamy, taty, partnera w przeglądarce
app.get('/', (req, res) => {
    const r = latestReading;
    const glucose = r ? r.glucoseMgDl : '—';
    const trend = r ? (r.trend || '') : '';
    const time = r ? new Date(r.timestampMs).toLocaleTimeString() : 'Brak danych';
    const iob = r && r.iob !== undefined ? `${r.iob} j.` : '—';
    const reservoir = r && r.reservoirUnits !== undefined ? `${r.reservoirUnits} j.` : '—';
    const battery = r && r.batteryPercent !== undefined ? `${r.batteryPercent}%` : '—';

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
    </div>
    <div class="footer">Automatyczne odświeżanie co 30 sekund. Projekt GPL-3.0.</div>
</body>
</html>`);
});

app.listen(PORT, () => {
    console.log(`[MGlycemia Server] Running on port ${PORT}`);
});
