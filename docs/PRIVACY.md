# Prywatność i dane

## Co zostaje na telefonie

Domyślnie **wszystko** poza sprawdzaniem aktualizacji:

- odczyty glikemii i parametry pompy w bazie `minimed.db`
- zdarzenia z pompy
- ustawienia progów alertów, jednostki, języka, ISF
- informacja o sparowanej pompie
- kod rodziny (jeśli włączysz tryb rodziny)

Aplikacja **nie ma konta, domyślnego serwera ani analityki**. Nie zawiera reklam
ani identyfikatorów reklamowych.

## Ruch sieciowy

### Sprawdzanie aktualizacji

`UpdateChecker` pyta `api.github.com` o najnowsze wydanie. Zapytanie zawiera
wyłącznie nazwę i wersję aplikacji w nagłówku `User-Agent`. Nie są wysyłane dane
z bazy, lokalizacja ani identyfikatory urządzenia.

### Opcjonalna synchronizacja rodziny

Synchronizacja jest **wyłączona, dopóki sam nie podasz adresu serwera** w
Ustawieniach. Aplikacja nie zawiera żadnego domyślnego adresu.

Po skonfigurowaniu własnego serwera aplikacja wysyła:

- bieżący odczyt glikemii, trend, IOB, bazę, poziom zbiornika, baterię i model pompy,
- w razie błędu BLE — zredagowany raport diagnostyczny (bez adresów MAC, IP,
  e-maili i sekretów; patrz `LogExport.redact`).

Serwer wybiera użytkownik (własny VPS, Render, Fly.io itp.). **Operator serwera
może czytać przesłane dane** — wybieraj serwer, któremu ufasz. Kod rodziny jest
kluczem dostępu; traktuj go jak hasło i nie publikuj go.

### xDrip+ / inne aplikacje

Odczyty mogą być wysyłane jako **lokalny broadcast** Androida, wyłącznie w obrębie
telefonu. Nie opuszczają urządzenia.

## Uprawnienia

| Uprawnienie                                                  | Po co                                                  |
| ------------------------------------------------------------ | ------------------------------------------------------ |
| `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, `BLUETOOTH_ADVERTISE` | połączenie z pompą                                     |
| `ACCESS_FINE_LOCATION` (tylko do Androida 11)                | wymóg systemu przy BLE                                 |
| `POST_NOTIFICATIONS`                                         | alerty glikemii i status                               |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`  | praca przy zgaszonym ekranie                           |
| `RECEIVE_BOOT_COMPLETED`                                     | powrót po restarcie telefonu                           |
| `WAKE_LOCK`                                                  | utrzymanie odczytów                                    |
| `INTERNET`                                                   | aktualizacje i opcjonalna synchronizacja               |
| `REQUEST_INSTALL_PACKAGES`                                   | instalacja pobranej aktualizacji                       |
| `CAMERA`                                                     | skanowanie kodu QR rodziny (zdjęcia nie są zapisywane) |

## Eksport i kopie zapasowe

Pliki CSV tworzone są w katalogu aplikacji i udostępniane przez `FileProvider`.
Kopia folderowa używa SAF i folderu wybranego przez użytkownika. Użytkownik sam
decyduje, gdzie je zapisze lub wyśle. Aplikacja nic nie wysyła automatycznie.

CSV nie jest szyfrowany — informacja o tym jest w interfejsie.

## Serwer rodzinny (self-hosted)

Kod serwera jest w katalogu [`server/`](../server). Serwer:

- nasłuchuje tylko na `127.0.0.1` (za Nginx),
- ma rate limiting po stronie Nginx,
- ogranicza rozmiar raportu do 200 KB,
- trzyma maks. 120 odczytów na rodzinę, 30 logów i 20 MB danych łącznie,
- usuwa dane starsze niż 30 dni i rodziny bezczynne przez 30 dni.

To wystarcza dla jednej rodziny. **Nie uruchamiaj publicznego serwera dla obcych
osób**, jeśli nie masz na to zgody, miejsca i polityki prywatności.

## Kod źródłowy

Projekt jest na licencji GPL-3.0. Informacje o projektach, z których pochodzi kod,
znajdują się w pliku [NOTICE](../NOTICE).
