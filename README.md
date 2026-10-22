# MGSupport CGM

Niezależna aplikacja na Androida do podglądu glikemii z pomp Medtronic MiniMed
780G, 770G i 680G. Telefon jest urządzeniem peryferyjnym BLE, pompa łączy się do
niego i wysyła dane, które aplikacja odczytuje i zapisuje lokalnie.

Aplikacja jest **tylko do odczytu**. Nie wysyła do pompy żadnych poleceń i nie
zmienia jej ustawień. Nie jest wyrobem medycznym.

## Co potrafi

- aktualny cukier z sensora, trend i tempo zmian
- IOB, aktualna baza (także SmartGuard), bateria, poziom zbiornika
- historia w lokalnej bazie SQLite, wykresy od godziny do roku
- TIR, średnia, GMI, pasmo min/max
- progi alertów glikemii
- eksport i import CSV (backup i przenoszenie danych między telefonami)
- interfejs po polsku, angielsku, niemiecku i chińsku

## Wymagania

- Android 7.0 lub nowszy
- Bluetooth LE
- pompa MiniMed 780G, 770G albo 680G

## Budowanie

Projekt używa Gradle 9 i wymaga JDK 21. Na Windowsie najprościej wskazać JBR z
Android Studio:

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
"$JAVA_HOME/bin/java.exe" -Dorg.gradle.appname=gradlew \
  -classpath gradle/wrapper/gradle-wrapper.jar \
  org.gradle.wrapper.GradleWrapperMain :app:assembleDebug --no-daemon
```

APK trafia do `app/build/outputs/apk/debug/app-debug.apk`.

JavaSake jest dołączony do repozytorium jako zwykły katalog (nie submoduł), więc
build nie wymaga dostępu do zewnętrznych repozytoriów.

## Identyfikacja

- `applicationId`: `pl.mgsupport.cgm`
- kod: `pl.mgsupport.cgm` oraz `pl.mgsupport.cgm.core`

## Licencja

GNU GPL 3.0. Szczegóły i informacja o projektach, z których kod pochodzi, są w
pliku [NOTICE](NOTICE). MiniMed jest znakiem towarowym Medtronic; projekt nie jest
powiązany z Medtronic.
