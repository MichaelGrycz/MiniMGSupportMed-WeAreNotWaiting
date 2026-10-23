# AGENTS.md: MGlikemia / MGlycemia

Ten plik opisuje stałe zasady pracy w tym repozytorium. Czytaj go przed każdą zmianą.

## Czym jest projekt

Aplikacja Android **tylko do odczytu** dla pomp MiniMed 780G / 770G / 680G.
Telefon jest peryferium BLE, pompa jest centralem. Aplikacja nie może podawać insuliny,
zmieniać bazy ani wysyłać do pompy poleceń terapeutycznych.

- `applicationId`: `pl.mgsupport.mglycemia`
- gałąź robocza: `public/main`
- repozytorium: https://github.com/MichaelGrycz/MiniMGSupportMed-WeAreNotWaiting
- strona: https://mgsupport.pl/aplikacja/MGlikemia (stary adres /pobierz/minimgsupportmed przekierowuje 301)

Praktyczny przewodnik po warsztacie (build, lint, emulator, CI, wydanie, układ wykresu):
[`docs/WORKSPACE.md`](docs/WORKSPACE.md).

## Zasady nadrzędne

1. **Najpierw przeczytaj kod i logi, potem zmieniaj.** Nie zgaduj przyczyny.
2. **Nie osłabiaj testów ani nie ukrywaj błędów**, żeby build był zielony.
3. **Nie dodawaj zapisów terapeutycznych do pompy.** Tylko odczyt.
4. **Nie deklaruj, że problem sprzętowy jest naprawiony**, jeśli nie ma dowodu z logu lub testu.
5. **Nie usuwaj danych użytkownika** (bazy, CSV, starych APK) bez wyraźnej zgody.
6. **Nie zapisuj sekretów** (tokenów, haseł, kluczy) w repo, czacie, logach ani AGENTS.md.
7. Tłumacz się po polsku, konkretnie; ścieżki i identyfikatory w backtickach.
8. Zmiany trzymaj minimalne i zgodne ze stylem projektu.

## Build i testy (Windows, `sh`)

Systemowy Java to 1.8: **zawsze używaj JBR z Android Studio**:

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
"$JAVA_HOME/bin/java.exe" -Dorg.gradle.appname=gradlew \
  -classpath gradle/wrapper/gradle-wrapper.jar \
  org.gradle.wrapper.GradleWrapperMain :app:testDebugUnitTest :app:assembleDebug \
  --no-daemon --console=plain
```

APK: `app/build/outputs/apk/debug/app-debug.apk`.

Przed wydaniem zawsze uruchom testy jednostkowe i `assembleDebug`. Nową logikę czystą
(parsery, klasyfikację, formatowanie) pokrywaj testem JUnit.

## Wydanie: zawsze GitHub **i** strona

Każda nowa wersja musi trafić jednocześnie na GitHub Releases i na `mgsupport.pl`.
Nie wystarczy `git push`.

### Obowiązkowa kolejność

1. Podbij `versionCode` o 1 i ustaw `versionName` na kolejne `1.0-betaN` w `app/build.gradle.kts`.
2. Dopisz wpis użytkowy w `CHANGELOG.md` (bez żargonu typu GATT/CCCD).
3. Zbuduj i przetestuj.
4. Skopiuj APK jako `MGLycemia-<wersja>.apk`.
5. Wgraj APK na `VPS-MGSUPPORT:/var/www/mg-support-downloads/pobierz/`.
6. Zaktualizuj stronę:
   - `scp` plik `minimgsupportmed.astro` z VPS do `.page.astro`,
   - `python tools/update_page.py .page.astro "<wersja>" "<notatki>"`,
   - `scp` z powrotem,
   - na VPS: `sed` nazwy APK w `/etc/nginx/sites-available/mgsupport.pl`,
   - `npm run build` w `/var/www/mgsupport-astro`,
   - `nginx -t` i `systemctl reload nginx`.
7. `git add -A`, commit, `git push origin public/main`.
8. `git tag v<wersja>`, `git push origin v<wersja>`.
9. `gh release create v<wersja> app/build/outputs/apk/debug/app-debug.apk ...`.
10. Zweryfikuj stronę i plik APK przez `curl` (HTTP 200, właściwa wersja w tytule).

`tools/release.sh` robi kroki 1-9, ale **domyślnie usuwa stare APK z serwera**.
Nie uruchamiaj go, jeśli stare wersje mają zostać: wtedy wykonaj kroki ręcznie.

Repozytorium musi być **publiczne**, inaczej `UpdateChecker` nie widzi wydań.

## Aktualizacje w aplikacji

- Sprawdzanie przy otwarciu: maks. raz na 15 minut.
- Źródło podstawowe: GitHub API `/releases/latest`.
- Gdy API zwróci 403/rate limit: fallback na redirect
  `https://github.com/<owner>/<repo>/releases/latest` i tag z finalnego URL.
- Powiadomienie + okno z przyciskiem pobierania. Osobnego widgetu home-screen nie ma.
- Plik w release musi nazywać się `app-debug.apk` (fallback buduje URL do tej nazwy).
- Stare wersje (np. beta15) nie mają fallbacku: przy limicie API pokażą błąd;
  wtedy trzeba raz ręcznie zainstalować nowszą wersję.

## BLE i parowanie: nie regresować

- Serwisy: `0900` na bazie vendora, `FE82` na bazie SIG.
- Charakterystyka SAKE: `0000fe82-...-009132591325` (vendor), CCCD `2902`.
- **PnP ID `2A50` musi mieć dokładnie 7 bajtów.** Jeden bajt powodował zerwanie linku.
- Wartości Device Information muszą odpowiadać JavaPumpConnector:
  `Test Manufacturer`, `Test Model Number`, `Mobile NNNNNN`, `HW-1.0`,
  `14587043`, `2.9.0 f1093d1`, 8 zer System ID, 7 zer PnP ID.
- Nazwa urządzenia: unikalne `Mobile NNNNNN` na instalację (`PumpIdentity`).
  Przy „Usuń parowanie” i ponownym parowaniu nazwa się zmienia, żeby ominąć stary wpis w pompie.
- `createBond()` wywołujemy tylko po odczycie CCCD (`2902`) i tylko gdy `bond=none` (eksperyment w beta42).
  Wywołanie go od razu po połączeniu powodowało zerwanie linku.
- Rozgłaszanie zatrzymuj po połączeniu pompy.
- Odpowiedź ATT wysyłaj **przed** uruchomieniem SAKE.
- Resetuj `sakeCccdValue` przy każdym połączeniu.
- Loguj `bond state previous -> state reason=…`; nieudane parowanie ustawia `PAIRING_FAILED`.
- `removeBond()` jest zablokowane dla zwykłych aplikacji od Androida 14: użytkownik musi
  usunąć wpis pompy w Ustawieniach Bluetooth.
- Pamiętaj o stałej diagnostyce: `read 2a50 len=7`, `descriptor write 2902 len=2`.

## Historia i wykres

- Surowe rekordy historii zapisuj w `history_records` (`insertHistoryRecords`).
- Czasy liczy `HistoryTimeline` względem `NGP_REFERENCE_TIME` / `REFERENCE_TIME`.
- **Nigdy nie używaj czasu pobrania jako czasu zdarzenia.**
- Pierwsza synchronizacja może pobrać całą historię od seq 0 (backfill); potem kursor jest przyrostowy.
- `HistoryPresenter` pokazuje `RRRR-MM-DD GG:MM:SS`; przy braku kotwicy zostaje `t+… s`.
- Na wykresie liczą się tylko **dostarczone** bolusy (`BOLUS_DELIVERED_P1`), nie zaprogramowane.
- `AUTO_BASAL_DELIVERY` to automatyczna baza/mikrobolus, **nie** bolus korekcyjny.
- Nie klasyfikuj braku szczegółów jako „ręczny”.

## Sensor: inicjalizacja vs aktualizacja

- `CgmMeasurement.timeOffsetMinutes` to minuty od startu sesji.
- `0-119 min` → `SensorPhase.INITIALIZING`, pozostało `120 − offset`.
- `≥ 120 min` → `SensorPhase.UPDATING`.
- Pokazuj to w banerze, plakietce i powiadomieniu; format `1 godz. 45 min`.
- Gdy pompa nie poda offsetu: wracaj do „Aktualizacja sensora”, nie zgaduj.

## Kopia zapasowa

- SAF, folder wybierany przez użytkownika (np. `Dokumenty/MGlikemia`), CSV.
- Kopia ma przeżyć odinstalowanie, ale uprawnienie do folderu przepada po reinstalacji.
- Po reinstalacji użytkownik musi wskazać folder i jawnie przywrócić dane.
- Nie dodawaj szerokich uprawnień do pamięci ani ukrytych katalogów.
- CSV nie jest szyfrowany: informuj o tym w interfejsie.

## Git

- Nie commituj bez potrzeby, ale **wydanie zawsze wymaga commita, taga i pusha**.
- Nie zmieniaj nazw plików ani nie rób masowych refaktorów bez powodu.
- Przed commitem sprawdź `git status` i usuń pliki tymczasowe (`.page.astro`, skopiowane APK).
- Nie pushuj na `main` bez świadomej decyzji; bieżąca gałąź to `public/main`.
- `tools/release.sh` pushuje `public/main:main`: pamiętaj o tym przy wydaniu.

## Diagnostyka

- Użytkownik wysyła log z Ustawienia → Diagnostyka i logi techniczne.
- Najważniejsze linie: `bond=`, `bond state … reason=`, `descriptor read/write 2902`,
  `read 2a50 len=`, `GATT link down, status=`.
- Brak `descriptor write 2902` = pompa nie weszła w SAKE; patrz parowanie, PnP ID, MTU.
- Nie obiecuj zgodności z „każdą pompą” bez testów na danym modelu.

## Definition of done

- [ ] testy jednostkowe przechodzą,
- [ ] `assembleDebug` przechodzi,
- [ ] wersja i CHANGELOG podbite,
- [ ] GitHub Release z APK,
- [ ] strona `mgsupport.pl` zaktualizowana i sprawdzona,
- [ ] brak sekretów i plików tymczasowych w diffie,
- [ ] jasno opisane ograniczenia sprzętowe.
