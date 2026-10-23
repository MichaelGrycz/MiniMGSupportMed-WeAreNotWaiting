# MGlycemia Cloud Companion Server

Darmowy, lekki serwer w Node.js (Express) do zbierania telemetrii z aplikacji Android **MGlycemia** oraz automatycznego zbierania logów diagnostycznych i udostępniania podglądu dla rodziny.

## Szybkie wdrażanie za darmo (1 kliknięcie)

1. **Render.com** (Zalecane, darmowy tier):
   - Kliknij [New +] -> [Web Service].
   - Połącz to repozytorium GitHub.
   - Root Directory: `server`
   - Build Command: `npm install`
   - Start Command: `npm start`
   - Kliknij **Create Web Service**. Otrzymasz adres URL (np. `https://mglycemia-server.onrender.com`).

2. **Użycie w aplikacji Android:**
   - Wklej otrzymany adres URL w ustawieniach aplikacji w sekcji *Serwer synchronizacji / Rodzina*.
   - Aplikacja automatycznie wysyła tam odczyty oraz w razie błędu BLE przesyła zredagowane logi diagnostyczne.
   - Rodzina może otworzyć ten adres w dowolnej przeglądarce, aby widzieć aktualny cukier w czasie rzeczywistym.
