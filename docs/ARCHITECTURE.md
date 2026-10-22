# Architektura

Aplikacja to jeden moduł Android (`app`) plus dołączona biblioteka SAKE
(`JavaSake`, katalog w repo, nie submoduł).

- `applicationId`: `pl.mgsupport.mglycemia`
- kod aplikacji: `pl.mgsupport.mglycemia`
- rdzeń BLE i model domenowy: `pl.mgsupport.mglycemia.core`
- `minSdk` 24, `targetSdk` 36

## Warstwy

```
UI (PumpDashboardActivity, GlucoseChartView)
        │  stan przez StateFlow
PumpController  ──  ReadingStore (SQLite)
        │                 │
        │                 └── eksport / import CSV
        │
MedtronicBleConnectionManager (core)
        │
AndroidMedtronicPeripheral  ──  GATT server + advertising
        │
SakeHandshakeDriver  ──  JavaSake
        │
MedtronicReadGateway  ──  czytniki CGM, IDD, baterii, historii
```

## Komponenty

| Plik | Rola |
|---|---|
| `PumpApp` | tworzy jeden `PumpController` dla całej aplikacji |
| `PumpDashboardActivity` | jedyna aktywność, cztery zakładki: Pulpit, Historia, Pompa, Ustawienia |
| `PumpController` | stan UI, pętla odpytywania pompy, alerty, CSV, aktualizacje |
| `PumpForegroundService` | trzyma sesję BLE przy zgaszonym ekranie, powiadomienie statusowe |
| `ReadingStore` | SQLite: odczyty i zdarzenia, zapytania do wykresu, statystyki, CSV |
| `GlucoseChartView` | własny widok wykresu, zoom, przesuwanie, tryb gęsty dla długich zakresów |
| `AlertManager` | progi glikemii, powiadomienia, kanały |
| `UpdateChecker` | wydania na GitHubie, pobranie APK, instalator |
| `CompatChecker` | czy telefon potrafi rozgłaszać się jako peripheral |
| `PrefsCredentialStore` | zapis parowania w SharedPreferences |

Rdzeń w `.core`:

| Plik | Rola |
|---|---|
| `MedtronicBleConnectionManager` | maszyna stanów połączenia, ponawianie, wykrywanie błędów |
| `AndroidMedtronicPeripheral` | GATT server, rozgłaszanie, deskryptory, diagnostyka |
| `SakeHandshakeDriver` | sekwencja ramek SAKE na charakterystyce |
| `MedtronicReadGateway` | wysoko poziomowe odczyty z pompy |
| `ble/read/*` | parsowanie CGM, IDD, baterii, historii |
| `ble/protocol/MedtronicProtocol` | UUID, identyfikatory, kontrakt rozgłaszania |

## Topologia BLE

Telefon jest **peripheralem**, pompa jest **centralem**. To odwrotność typowej
sytuacji z aplikacjami do sensorów. Telefon rozgłasza się jako `Mobile 000001`,
pompa łączy się do niego i odczytuje dane. Szczegóły w [BLE-PAIRING.md](BLE-PAIRING.md).

## Baza danych

Plik `minimed.db`, dwie tabele:

```
readings(id, ts_ms, glucose, trend, roc, iob, basal, basal_auto, reservoir, battery, battery_charging)
events(seq, ts_ms, type_id, text)
```

Indeksy na `ts_ms` w obu tabelach. Odczyty zapisywane raz na próbkę sensora
(około 5 minut), więc rok to około 100 tysięcy wierszy.

### Wykres a statystyki

`queryGlucose()` deduplikuje próbki i ogranicza wynik do 1500 punktów, żeby
rysowanie było szybkie na długich zakresach. Z tego powodu **statystyki nie są
liczone z punktów wykresu**: TIR, średnia, min, max i GMI idą osobnym zapytaniem
`statsFor()` po surowych wierszach. Wcześniej liczone z wygładzonych punktów
conowały zawyżony TIR (100% zamiast 92%) na zakresie rocznym.

## Aktualizacje

`UpdateChecker` pyta `api.github.com` o najnowsze wydanie repozytorium, porównuje
wersje numerycznie (`1.0-beta10` > `1.0-beta9`), pokazuje okno i powiadomienie,
pobiera APK do `cacheDir/updates` i przekazuje go instalatorowi przez
`FileProvider`.

Sprawdzanie odbywa się raz na dobę przy starcie oraz ręcznie z Ustawień.

## CSV

`ReadingStore.exportBackupCsv()` tworzy jeden plik z nagłówkiem:

```
record_type,timestamp_iso,epoch_ms,glucose_mgdl,glucose_mmol,trend,roc_mgdl_min,
iob_u,basal_u_h,basal_automated,reservoir_u,battery_pct,battery_charging,sequence,type_id,text
```

`importBackupCsv()` przyjmuje ten plik oraz starsze `readings.csv` / `events.csv`.
Odczyty są deduplikowane po `epoch_ms`, zdarzenia nadpisywane po `seq`, wartości
glukozy spoza 20..500 odrzucane.
