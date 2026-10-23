# Architektura: Tryb Rodzina / Zdalny Podgląd (MGlikemia Follower)

## 1. Założenia i ograniczenia fizyczne

- **Pompa** jest centralem BLE i ma ograniczony zasięgiem Bluetooth (~kilka metrów).
- **Telefon dziecka** (trzymany przy pompie) musi odbierać pakiety BLE i działać jako **Uploader**.
- **Serwer na VPS** przyjmuje dane z telefonu dziecka i udostępnia je aplikacjom/przeglądarce rodzica.
- **Bezpieczeństwo i prywatność:** Dane medyczne (glikemia) wymagają szyfrowania i tokena autoryzacyjnego, żeby nikt postronny nie miał wglądu w wyniki.

---

## 2. Architektura systemu

```
[ Pompa MiniMed ]
       │ (BLE 2.4 GHz, krótki zasięg)
       ▼
[ Telefon Dziecka (MGlikemia Uploader) ]
       │ (HTTPS / POST /api/v1/reading)
       ▼
[ Serwer VPS (mgsupport.pl / Node.js / Python FastAPI + SQLite) ]
       │ (WebSocket / SSE / Polling)
       ▼
[ Telefon Rodzica / Przeglądarka (Tryb Rodzica / Follower) ]
```

---

## 3. Komponenty do wdrożenia

### A. Backend na VPS (Mini-serwerek)

- **Technologia:** Python (FastAPI) lub Node.js (Express) w kontenerze Docker na VPS (`VPS-MGSUPPORT`).
- **Baza danych:** SQLite na VPS (lekka, szybka, łatwa w backupie).
- **Endpointy API:**
  - `POST /api/v1/reading` (wymaga tokena API): przyjmuje JSON z `glucoseMgDl`, `trend`, `iob`, `reservoir`, `battery`, `timestamp`.
  - `GET /api/v1/latest` (dla rodzica): zwraca najnowszy stan i historię z ostatnich 24h.
  - `GET /` (Panel WWW / Web Follower): lekka strona HTML/JS z dużym wykresem glikemii i statusem pompy, dostępna pod adresem np. `https://mgsupport.pl/rodzina` zabezpieczona hasłem lub unikalnym tokenem w URL.

### B. Klient w aplikacji MGlikemia (Tryb Dziecka / Uploader)

- W ustawieniach aplikacji dodamy sekcję „Zdalny podgląd (VPS)”.
- Użytkownik wpisuje adres serwera (`https://mgsupport.pl`) oraz swój tajny token.
- Po każdym udanym odebraniu danych z pompy przez BLE, serwis w tle (`PumpForegroundService` lub `PumpController`) wysyła cichy request HTTP POST z aktualnym stanem.

### C. Tryb Rodzica (Follower View)

- Może być zrealizowany na dwa sposoby (do wyboru):
  1. **Web App (najprostsza i uniwersalna):** Otwierasz w przeglądarce na telefonie rodzica adres podany na VPS: działa na każdym telefonie (Android/iOS) bez instalowania osobnej apki.
  2. **Widok w aplikacji MGlikemia:** Przełączenie w ustawieniach w tryb „Odbiornik / Rodzic”, w którym aplikacja zamiast skanować BLE, odpytuje co 1 minutę serwer na VPS.

---

## 4. Bezpieczeństwo

- Żadnych otwartych portów bez HTTPS (Nginx na VPS z Let's Encrypt już obsługuje `mgsupport.pl`).
- Token autoryzacyjny (API Key) generowany losowo podczas parowania urządzenia z serwerem.
- Żadne polecenia terapeutyczne nie są obsługiwane przez serwer (zgodnie z zasadą _tylko do odczytu_).
