# Jak pracować w tym warsztacie

Praktyczny przewodnik po repozytorium: środowisko, build, testy, emulator, wykres,
CI i wydanie. Zasady nadrzędne są w [`AGENTS.md`](../AGENTS.md), architektura w
[`docs/ARCHITECTURE.md`](ARCHITECTURE.md), a pełne wydanie w [`docs/RELEASE.md`](RELEASE.md).

## 1. Środowisko (Windows + Git Bash)

Systemowy Java to 1.8 i **nie zadziała**. Zawsze używaj JBR z Android Studio:

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
```

Build + testy jednostkowe:

```bash
"$JAVA_HOME/bin/java.exe" -Dorg.gradle.appname=gradlew \
  -classpath gradle/wrapper/gradle-wrapper.jar \
  org.gradle.wrapper.GradleWrapperMain :app:testDebugUnitTest :app:assembleDebug \
  --no-daemon --console=plain
```

Lint (musi być **0 błędów**, nie dodawaj baseline ani nie wyłączaj reguł):

```bash
"$JAVA_HOME/bin/java.exe" -Dorg.gradle.appname=gradlew \
  -classpath gradle/wrapper/gradle-wrapper.jar \
  org.gradle.wrapper.GradleWrapperMain :app:lintDebug \
  --no-daemon --console=plain
```

APK: `app/build/outputs/apk/debug/app-debug.apk`.

`local.properties` na Windows musi mieć zescapowany dwukropek, inaczej lint zgłosi
`PropertyEscape`:

```properties
sdk.dir=C\:/Users/<user>/AppData/Local/Android/Sdk
```

## 2. Mapa kodu

| Obszar | Plik / katalog |
|---|---|
| Ekran główny, ustawienia, historia | `PumpDashboardActivity.kt` |
| Pełnoekranowy wykres + wspólny `ChartUi` | `FullscreenChartActivity.kt` |
| Stan aplikacji, pętle odczytu, SQLite | `PumpController.kt`, `ReadingStore.kt` |
| Wykres i geometria | `GlucoseChartView.kt` (`GlucoseChartGeometry`, `GlucoseCurveSmoother`, `InsulinChartGeometry`) |
| Prognoza 30 min | `core/domain/analytics/GlucoseForecast.kt` |
| BLE: GATT server, advertising, reconnect | `core/ble/connection/` |
| BLE: odczyty CGM/IDD/historii | `core/ble/read/` (m.in. `MedtronicReadGateway.kt`) |
| Baza: `minimed.db`, `DB_VERSION = 3`, tabela `readings` | `ReadingStore.kt` |

## 3. Wykres — jak jest zbudowany

- **Oś Y** liczona z całego wybranego okna (nie z widocznego fragmentu), więc przewijanie
  i zoom nie skaczą. Baseline 40–300 mg/dL, podziałki okrągłe (50, 100, 150…).
- **Krzywa** to monotoniczny spline (Fritsch–Carlson) — gładki, ale bez overshootu.
  Przerwa > 16 min przerywa linię (brak danych nie jest dorysowywany).
- **Okna gęste** (7D–1R): wstęga min/max + wygładzona linia średniej (nie „kod kreskowy”).
- **Viewport** jest względem najnowszego odczytu, ale z absolutną kotwicą
  (`panAnchorMs`), żeby nowe odczyty nie przesuwały historii.
- **Dociąganie historii**: gdy użytkownik dojdzie do krawędzi wczytanych danych,
  wykres woła `onOlderDataNeeded` / `onNewerDataNeeded`; `PumpController.loadOlderChartData()`
  i `loadNewerChartData()` ładują okno z zapasem i ustawiają `chartPinnedEndMs`.
  `releaseChartPinningIfAtNow()` zwalnia przypięcie dopiero na żywej krawędzi.
- **Prognoza** jest **domyślnie wyłączona** (`PREF_SHOW_PREDICTION = false`).
  Po włączeniu zajmuje na osi dokładnie 30 min wybranego zakresu; przy bardzo długich
  oknach znika jako nieczytelny pasek (wartość zostaje w kafelku).
- **Statystyki** (TIR, ROC, delta, prognoza) liczą się z SQL i bieżących odczytów,
  **nigdy** z przewiniętego bufora wykresu.

## 4. Czego łatwo nie zauważyć

- **Tylko odczyt.** Żadnych zapisów terapeutycznych do pompy.
- **Desugaring**: `java.time` działa na `minSdk 24` wyłącznie dzięki
  `isCoreLibraryDesugaringEnabled = true` i `desugar_jdk_libs` — nie usuwaj tego.
- **Lint**: nowe stringi muszą mieć tłumaczenia `pl`, `de`, `zh`; formaty `%d`/`%s`
  muszą się zgadzać z argumentami. CI nie ma baseline.
- **Statystyki TIR** zawsze z surowych danych SQL, nie z downsamplowanych punktów.
- **Biblioteka SAKE** (`JavaSake/`) jest w repo, nie jest submodułem.
- **`tools/release.sh`** nadpisuje stronę plikiem `tools/site/MGlikemia.astro` i szuka
  wersji w cudzysłowach pojedynczych, a obecna strona używa podwójnych i `SiteLayout`.
  Dlatego wydania robimy **ręcznie** według `AGENTS.md` (kroki 1–10), a nie blind na skrypt.

## 5. Emulator

- AVD: `Pixel_API_34`, `Pixel_API_36` (google_apis_playstore).
- Uwaga: `emulator-5554` potrafi być `unauthorized`; sprawdzaj `adb devices` i używaj
  właściwego portu (często `-port 5556`).
- Start bez okna:

```bash
powershell -NoProfile -Command "Start-Process -FilePath 'C:\Users\<user>\AppData\Local\Android\Sdk\emulator\emulator.exe' -ArgumentList '-avd','Pixel_API_34','-port','5556','-no-window','-no-audio','-no-boot-anim','-gpu','swiftshader_indirect','-no-snapshot' -WindowStyle Hidden"
```

- W Git Bash zawsze `MSYS_NO_PATHCONV=1` przy ścieżkach `/sdcard/...`, np.:

```bash
MSYS_NO_PATHCONV=1 adb -s emulator-5556 shell screencap -p /sdcard/x.png
MSYS_NO_PATHCONV=1 adb -s emulator-5556 pull /sdcard/x.png shot.png
```

- Ekran startowy: `am start -n pl.mgsupport.mglycemia/.PumpDashboardActivity`.
- Dialog „Bluetooth keeps stopping” to ograniczenie emulatora — zamknij go i testuj UI.
- Dane demo: zainstaluj aplikację, uruchom raz (powstanie `databases/minimed.db`),
  pobierz bazę przez `run-as`, uzupełnij `readings` lokalnym `sqlite3`, wgraj z powrotem
  (`adb push` + `run-as cp`), usuń `-wal`/`-shm`, zrestartuj aplikację.
- Zrzuty do weryfikacji wizualnej: 1H / 6H / 24H / 7D / 30D / 1R + `Earlier`/`Later`.

## 6. CI

`.github/workflows/build.yml` uruchamia na `ubuntu-latest` (JDK 21):

1. `:app:assembleDebug`
2. `:app:lintDebug`
3. `:app:testDebugUnitTest`

- SDK Androida jest preinstalowany — **nie dodawaj** `android-actions/setup-android`.
- Trigger: `push` na `main` + `pull_request`.
- Czerwony build prawie zawsze oznacza lint: napraw przyczynę, nie dodawaj baseline.

## 7. Wydanie (skrót)

Pełna procedura: `AGENTS.md` (kroki 1–10). Skrót:

1. `versionCode` +1, `versionName = "1.0-betaN"` w `app/build.gradle.kts`.
2. Wpis w `CHANGELOG.md` językiem użytkownika (bez żargonu).
3. `testDebugUnitTest` + `lintDebug` + `assembleDebug`.
4. `git add -A`, commit, `git push origin public/main:main`.
5. `git tag v1.0-betaN`, `git push origin v1.0-betaN`.
6. `gh release create v1.0-betaN app/build/outputs/apk/debug/app-debug.apk …`.
7. `scp` APK jako `MGLycemia-1.0-betaN.apk` do `/var/www/mg-support-downloads/pobierz/`.
8. Na VPS: `sed` aliasu APK w `/etc/nginx/sites-available/mgsupport.pl`,
   `sed` wersji w `/var/www/mgsupport-astro/src/pages/aplikacja/MGlikemia.astro`
   (uwaga: **podwójne cudzysłowy**), `nginx -t`, `systemctl reload nginx`,
   `npm run build` w `/var/www/mgsupport-astro`, `pm2 restart mgsupport-astro`.
9. Weryfikacja: strona pokazuje nową wersję, APK zwraca 200 i ten sam rozmiar,
   stary adres `/pobierz/minimgsupportmed` → 301.

Nie usuwaj starych APK z serwera bez zgody użytkownika.

## 8. Diagnostyka

- Log użytkownika: Ustawienia → Diagnostyka i logi techniczne.
- Kluczowe linie BLE: `bond=`, `bond state … reason=`, `descriptor read/write 2902`,
  `read 2a50 len=`, `GATT link down, status=`.
- Decyzje o doczytywaniu historii wykresu logują się w logcat pod tagiem
  `MGlycemiaChart` (`older requested`, `newer requested`).
- Brak `descriptor write 2902` = pompa nie weszła w SAKE; patrz parowanie, PnP ID, MTU.
- Po zerwaniu linku: najpierw sprawdź, czy to pompa zamknęła sesję (`status=0`),
  a nie aplikacja (`Rozłączam…`).

## 9. Testy

- Nowa logika czysta (parsery, geometria, prognoza, formatowanie) → test JUnit w
  `app/src/test/java/pl/mgsupport/mglycemia/`.
- Nie osłabiaj testów, żeby build był zielony.
- Zmiany UI weryfikuj zrzutami z emulatora; nie deklaruj „naprawione” bez zrzutu lub logu.

## 10. Zanim zakończysz pracę

- [ ] `testDebugUnitTest`, `lintDebug`, `assembleDebug` — zielone.
- [ ] Brak plików tymczasowych (`.tmp_*`, `.screens/`, skopiowane APK) w `git status`.
- [ ] Brak sekretów w diffie.
- [ ] Jeśli była wersja: GitHub Release + APK na VPS + strona zaktualizowana i sprawdzona.
- [ ] Ograniczenia sprzętowe opisane wprost (np. co wymaga retestu z prawdziwą pompą).
