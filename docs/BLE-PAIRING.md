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

| Usługa | UUID | Zawartość |
|---|---|---|
| Device Information | `00000900-0000-1000-0000-009132591325` | 2A29, 2A24, 2A25, 2A26, 2A27, 2A28, 2A23, 2A50, 2A2A |
| SAKE | `0000fe82-0000-1000-8000-00805f9b34fb` | charakterystyka `0000fe82-...-009132591325` (NOTIFY + WRITE) z CCCD `2902` |

Wartości Device Information są kopią tych z JavaPumpConnector. Puste ciągi
w polach firmware/software powodowały zrywanie połączenia.

## Stany połączenia

`DISCONNECTED`, `SCANNING`, `CONNECTING`, `AUTHENTICATING`, `CONNECTED`,
`AUTH_FAILED`, `RECONNECTING`.

## Błędy

| Błąd | Znaczenie |
|---|---|
| `PERIPHERAL_UNSUPPORTED` | telefon nie potrafi rozgłaszać się jako peripheral |
| `ADVERTISE_FAILED` | system odrzucił rozgłaszanie |
| `HANDSHAKE_TIMEOUT` | SAKE nie skończyło się w 30 s |
| `AUTH_FAILED` | pompa odrzuciła autoryzację |
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

## Znany problem: Galaxy S24, One UI 8.5

Objaw: pompa łączy się, czyta `2902`, po czym natychmiast zrywa. Na ekranie
telefonu pojawia się systemowe okno „Żądanie powiązania Bluetooth”.

Co wiadomo:

- na innych telefonach ta sama wersja działa
- `unpair: bond removed=false` oznacza, że aplikacja nie usunęła powiązania.
  Od Androida 14 `removeBond()` jest zablokowane dla zwykłych aplikacji
- powiązanie trzeba usunąć ręcznie: Ustawienia → Bluetooth → wpis pompy

Kolejność, która ma największą szansę zadziałać:

1. usunąć `Mobile 000001` z listy urządzeń mobilnych w pompie
2. usunąć wpis pompy w Ustawieniach Bluetooth telefonu
3. wyłączyć i włączyć Bluetooth
4. parować z apki, telefon odblokowany, apka na wierzchu
5. **kliknąć „Powiąż” w systemowym oknie**, nie „Anuluj”

## Diagnoza po logu

- `bond=bonding` i zaraz `link down`: problem w oknie powiązania lub blokada systemu
- `bond=none` i `link down` bez żadnego `read`: pompa odrzuca urządzenie przed
  przeglądaniem usług, wtedy sprawdzamy rozgłaszanie i tabelę GATT
