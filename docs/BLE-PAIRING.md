# Parowanie i diagnostyka BLE

## Rola urządzeń

Telefon jest **peripheralem** (serwerem GATT), pompa jest **centralem**. Pompa
skanuje, znajduje telefon i sama się z nim łączy. Aplikacja nie skanuje.

## Rozgłaszanie

- nazwa lokalna: `Mobile 000001`, przenoszona w danych producenta `0x01F9`
- UUID usługi: `0xFE82` przy pierwszym parowaniu, `0xFE81` przy ponownym łączeniu
- tryb: `ADVERTISE_MODE_BALANCED` (pierwsze parowanie) lub `LOW_LATENCY` (reconnect)
- połączenie: connectable, bez limitu czasu

## Tabela GATT serwera

| Usługa             | UUID                                   | Zawartość                                                                  |
| ------------------ | -------------------------------------- | -------------------------------------------------------------------------- |
| Device Information | `00000900-0000-1000-0000-009132591325` | 2A29, 2A24, 2A25, 2A26, 2A27, 2A28, 2A23, 2A50, 2A2A                       |
| SAKE               | `0000fe82-0000-1000-8000-00805f9b34fb` | charakterystyka `0000fe82-...-009132591325` (NOTIFY + WRITE) z CCCD `2902` |

Wartości Device Information są kopią tych z JavaPumpConnector. Puste ciągi
w polach firmware/software powodowały zrywanie połączenia. `2A50` (PnP ID)
musi mieć dokładnie 7 bajtów — jeden bajt powoduje odrzucenie tabeli przez pompę.

## Stany połączenia

`DISCONNECTED`, `SCANNING`, `CONNECTING`, `AUTHENTICATING`, `CONNECTED`,
`AUTH_FAILED`, `RECONNECTING`.

## Błędy

| Błąd                        | Znaczenie                                                         |
| --------------------------- | ----------------------------------------------------------------- |
| `PERIPHERAL_UNSUPPORTED`    | telefon nie potrafi rozgłaszać się jako peripheral                |
| `ADVERTISE_FAILED`          | system odrzucił rozgłaszanie                                      |
| `HANDSHAKE_TIMEOUT`         | SAKE nie skończyło się w 30 s                                     |
| `AUTH_FAILED`               | pompa odrzuciła autoryzację                                       |
| `BOUND_ELSEWHERE_SUSPECTED` | brak połączenia w oknie 60 s, prawdopodobnie stary wpis parowania |

## Zachowanie przy zerwaniu

- połączona pompa: natychmiastowy powrót do rozgłaszania w trybie reconnect
- pierwsze parowanie: do 5 prób ponownego rozgłaszania, 1,5 s odstępu
- rozgłaszanie wyłączane jest 900 ms po połączeniu, nie w tym samym zdarzeniu.
  Na stosie Samsunga natychmiastowe `stopAdvertising` potrafi ubić świeży link.

## Log diagnostyczny

W Ustawieniach: „Diagnostyka i logi techniczne”. Linie, które mają znaczenie:

```
adapter name=Galaxy S24, advertising as Mobile 000001
service added 0900 status=0
GATT link up, status=0, bond=none|bonding|bonded
read 2a29 len=18
descriptor read 2902
descriptor write 2902 len=2
GATT link down, status=0
GATT disconnect, status=0
```

- `bond=`: `none` brak powiązania, `bonding` pompa właśnie inicjuje parowanie,
  `bonded` urządzenia są już powiązane
- `status=0` przy rozłączeniu oznacza zamknięcie linku przez drugą stronę
- brak `read 2a29` przed rozłączeniem oznacza, że pompa nie doszła do odczytu
  danych urządzenia

## Tożsamość urządzenia

Aplikacja rozgłasza się jako `Mobile NNNNNN`, gdzie sześć cyfr jest losowe i trwałe
na daną instalację. Dzięki temu dwie osoby nie używają tej samej nazwy, a pompa
nie dopasowuje nowego telefonu do starego wpisu z innymi kluczami.

- pierwsza instalacja: losowa nazwa, np. `Mobile 483920`
- aktualizacja z wersji, która była już sparowana: zachowana nazwa `Mobile 000001`,
  żeby nie psuć działającego połączenia
- „Usuń parowanie”: aplikacja zmienia nazwę na nową, więc kolejne parowanie jest
  czyste i nie koliduje ze starym wpisem w pompie

Aktualną nazwę pokazuje log parowania oraz karta „Pompa”.

## Znany problem: Galaxy S24 / Realme / Android 16

Objaw: pompa łączy się, czyta `2902` i dane urządzenia, po czym natychmiast
zrywa (`GATT link down, status=0`). Często towarzyszy temu `bond=bonding`
i brak `descriptor write 2902`, czyli pompa nie dochodzi do subskrypcji SAKE.

Najczęstsza przyczyna to stary wpis parowania po jednej ze stron. Android 16
przy nieudanym uwierzytelnieniu bondingu zrywa link i zgłasza utratę bondingu;
`removeBond()` jest zablokowane dla zwykłych aplikacji od Androida 14.

Kolejność, która usuwa problem:

1. w pompie usuń **wszystkie** stare wpisy `Mobile …`
2. w telefonie: Ustawienia → Bluetooth → usuń wpis pompy („Zapomnij”)
3. w aplikacji: „Usuń parowanie” (zmieni nazwę urządzenia na nową)
4. wyłącz i włącz Bluetooth
5. „Paruj nową pompę”, na pompie wybierz nazwę pokazaną w aplikacji
6. **kliknij „Powiąż” w systemowym oknie**, nie „Anuluj”

## Diagnoza po logu

- `bond state bonding -> none reason=… (pairing failed)`: systemowe parowanie
  zostało odrzucone lub przekroczyło czas; potwierdź okno lub usuń wpis pompy
- `bond=bonding` i zaraz `link down`: problem w oknie powiązania lub blokada systemu
- `bond=none` i `link down` bez żadnego `read`: pompa odrzuca urządzenie przed
  przeglądaniem usług, wtedy sprawdzamy rozgłaszanie i tabelę GATT
- `GATT link up` → `descriptor read 2902` → brak `descriptor write 2902`:
  pompa nie włączyła subskrypcji SAKE; zwykle stary bonding lub brak potwierdzenia

## Diagnoza po logu

- `bond=bonding` i zaraz `link down`: problem w oknie powiązania lub blokada systemu
- `bond=none` i `link down` bez żadnego `read`: pompa odrzuca urządzenie przed
  przeglądaniem usług, wtedy sprawdzamy rozgłaszanie i tabelę GATT
