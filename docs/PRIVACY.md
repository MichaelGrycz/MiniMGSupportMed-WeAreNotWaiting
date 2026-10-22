# Prywatność i dane

## Co zostaje na telefonie

Wszystko poza sprawdzaniem aktualizacji:

- odczyty glikemii i parametry pompy w bazie `minimed.db`
- zdarzenia z pompy
- ustawienia progów alertów, jednostki, języka
- informacja o sparowanej pompie

Nie ma konta, serwera ani synchronizacji. Aplikacja nie zawiera analityki,
reklam ani identyfikatorów reklamowych.

## Jedyny ruch sieciowy

`UpdateChecker` wysyła zapytanie do `api.github.com` po najnowsze wydanie.
Zapytanie zawiera wyłącznie nazwę i wersję aplikacji w nagłówku User-Agent.
Nie są wysyłane żadne dane z bazy, lokalizacja ani identyfikatory urządzenia.

Sprawdzanie odbywa się raz na dobę przy starcie i na żądanie z Ustawień.

## Uprawnienia

| Uprawnienie | Po co |
|---|---|
| `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, `BLUETOOTH_ADVERTISE` | połączenie z pompą |
| `ACCESS_FINE_LOCATION` (tylko do Androida 11) | wymóg systemu przy BLE |
| `POST_NOTIFICATIONS` | alerty glikemii i status |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE` | praca przy zgaszonym ekranie |
| `RECEIVE_BOOT_COMPLETED` | powrót po restarcie telefonu |
| `WAKE_LOCK` | utrzymanie odczytów |
| `INTERNET` | sprawdzanie aktualizacji |
| `REQUEST_INSTALL_PACKAGES` | instalacja pobranej aktualizacji |

## Eksport

Pliki CSV tworzone są w katalogu aplikacji i udostępniane przez `FileProvider`.
Użytkownik sam decyduje, gdzie je wyśle. Aplikacja nic nie wysyła automatycznie.

## Kod źródłowy

Projekt jest na licencji GPL-3.0. Informacje o projektach, z których pochodzi kod,
znajdują się w pliku [NOTICE](../NOTICE).
