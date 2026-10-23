# MGlycemia — serwer rodzinny (self-hosted)

Lekki serwer Node.js (Express) do synchronizacji odczytów między telefonami jednej
rodziny. **Aplikacja nie ma domyślnego serwera** — synchronizacja działa tylko
wtedy, gdy sam uruchomisz serwer i podasz jego adres w Ustawieniach.

## Zasady bezpieczeństwa

- Uruchamiaj serwer **tylko dla swojej rodziny**. Nie udostępniaj go publicznie
  obcym osobom.
- Serwer nasłuchuje na `127.0.0.1`; wystaw go przez Nginx z HTTPS i rate limitingiem.
- Kod rodziny jest kluczem dostępu. Traktuj go jak hasło; nie publikuj go.
- Operator serwera może czytać przesłane dane. Wybierz serwer, któremu ufasz.

## Limity danych

- maks. 120 odczytów na rodzinę,
- historia starsza niż 48 h jest usuwana,
- maks. 500 rodzin, 30 logów, raport do 200 KB,
- twardy budżet dyskowy 20 MB,
- dane bezczynne przez 30 dni są usuwane,
- zapis na dysk z debounce 5 s i flush przy SIGTERM.

## Szybkie wdrożenie

### Render / Fly.io / Railway

1. Utwórz nową usługę z tego repozytorium, katalog `server`.
2. Build: `npm install`; Start: `npm start`.
3. Ustaw `DATA_DIR` na katalog trwałego dysku (np. `/data`).
4. Skopiuj adres HTTPS i wpisz go w aplikacji w polu „Sync server address”.

### Własny VPS

```bash
npm install --omit=dev
DATA_DIR=/var/lib/mglycemia node index.js
```

Zalecany Nginx (rate limit + proxy):

```nginx
limit_req_zone $binary_remote_addr zone=mglycemia_api:10m rate=30r/m;

location ^~ /mglycemia-api/ {
    limit_req zone=mglycemia_api burst=20 nodelay;
    limit_req_status 429;
    client_max_body_size 2m;
    proxy_pass http://127.0.0.1:3000/;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
}
```

## API

- `POST /api/telemetry?family=<kod>` — odczyt z głównego telefonu,
- `GET /api/latest?family=<kod>` — najnowszy odczyt dla telefonu rodziny,
- `POST /api/logs?family=<kod>` — zredagowany raport diagnostyczny,
- `GET /api/health` — status i rozmiary plików,
- `GET /?family=<kod>` — prosty podgląd w przeglądarce.

## Zmienne środowiskowe

| Zmienna    | Domyślnie            | Znaczenie                                     |
| ---------- | -------------------- | --------------------------------------------- |
| `PORT`     | `3000`               | port nasłuchu (tylko `127.0.0.1`)             |
| `DATA_DIR` | `/var/lib/mglycemia` | katalog danych (`families.json`, `logs.json`) |
