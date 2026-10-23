# Plan rozwoju interfejsu MGlikemia

Dokument porządkuje prace nad UI/UX aplikacji tylko-do-odczytu dla pomp MiniMed.
Punkty są ułożone według priorytetu: najpierw to, co blokuje zaufanie użytkownika
i utrudnia codzienne korzystanie, potem porządki i dopracowanie detali.

Zasada nadrzędna: żadna zmiana UI nie może prowadzić do wysyłania poleceń
terapeutycznych do pompy. Interfejs tylko pokazuje dane.

---

## 1. Widoczny postęp pobierania aktualizacji

**Problem:** przy pobieraniu APK użytkownik widzi tylko tekst „Pobieram aktualizację…",
bez informacji, czy transfer postępuje i ile zostało.

**Zakres:**

- pasek postępu (determinate, gdy serwer poda `Content-Length`; indeterminate, gdy nie),
- tekst „Pobieranie 42% (4,1 / 10,0 MB)”,
- stan „Instalowanie…” po zakończeniu pobierania,
- czytelny komunikat przy błędzie pobrania lub braku możliwości otwarcia instalatora,
- pobieranie poza wątkiem UI, aktualizacja widoku na wątku głównym.

**Kryterium akceptacji:** podczas pobierania wartość procentowa rośnie monotonicznie,
pasek znika po błędzie i po starcie instalatora, a teksty są przetłumaczone we wszystkich
obsługiwanych językach (`values`, `values-pl`, `values-de`, `values-zh`).

---

## 2. Prognoza glikemii na wykresie

**Problem:** linia prognozy 30-min rysowana była wewnątrz obszaru wykresu i nachodziła
na rzeczywistą linię cukru, przez co trudno było odróżnić dane od predykcji.

**Zakres:**

- wydzielona strefa przyszłości po prawej stronie (`FUTURE_GUTTER_DP`),
- delikatna linia graniczna teraźniejszość/przyszłość,
- przerywana linia od ostatniego odczytu do punktu prognozy,
- kropka, etykieta „→ wartość” i podpis „30 min”,
- rysowanie poza clipem obszaru wykresu, żeby nic nie było obcinane,
- ograniczenie ekstrapolacji w logice (`roc` przycięte do ±3 mg/dL/min, wynik 40–400 mg/dL).

**Kryterium akceptacji:** prognoza nigdy nie przecina linii historii, a przy skrajnych
skokach ROC wartość prognozy pozostaje w zakresie 40–400 mg/dL.

---

## 3. Porządek w ustawieniach

**Problem:** karta ustawień rośnie wraz z funkcjami; trudno znaleźć konkretną opcję.

**Zakres:**

- grupowanie w sekcje: Połączenie, Wyświetlanie, Powiadomienia, Dane i kopia zapasowa,
  Aktualizacje, Informacje,
- kolejność od najczęściej używanych do rzadkich,
- jednolity styl nagłówków sekcji i odstępów.

**Kryterium akceptacji:** każda opcja należy do dokładnie jednej sekcji, a lista mieści się
w jednym przewinięciu bez zbędnych pustych przestrzeni.

---

## 4. Hierarchia pulpitu

**Problem:** wszystkie kafelki mają podobną wagę wizualną, więc najważniejszy odczyt
i stan połączenia nie wyróżniają się wystarczająco.

**Zakres:**

- glikemia, trend i czas od odczytu jako element dominujący,
- stan połączenia i sensora jako wyraźna, ale drugorzędna plakietka,
- prognoza i statystyki jako kafle pomocnicze,
- akcje serwisowe (parowanie, synchronizacja) na końcu.

**Kryterium akceptacji:** w pierwszym ekranie bez przewijania widać glikemię, trend,
czas odczytu i stan połączenia.

---

## 5. Czytelność zdarzeń na wykresie

**Problem:** bolusy, baza automatyczna i alarmy nachodzą na siebie przy zagęszczeniu danych.

**Zakres:**

- stałe pasma (lane) dla bolusów, bazy automatycznej i alarmów,
- kolizje etykiet rozwiązywane przez grupowanie w jedną kolumnę z licznikiem,
- spójne kolory i legendy,
- brak klasyfikowania braku szczegółów jako „ręczny”.

**Kryterium akceptacji:** przy 24 godzinach danych każde zdarzenie jest klikalne,
a liczba nałożonych etykiet nie rośnie liniowo z liczbą zdarzeń.

---

## 6. Powiadomienia

**Problem:** powiadomienia o aktualizacji i alertach systemowych mają różne priorytety
i sposoby wyciszania.

**Zakres:**

- osobne kanały: aktualizacje (niski priorytet), alarmy pompy, utrata połączenia,
- jasne reguły „cichego trybu”,
- spójny tekst powiadomień i akcja prowadząca do właściwego ekranu.

**Kryterium akceptacji:** wyciszenie alarmów nie wycisza powiadomień o aktualizacjach
i odwrotnie; każde powiadomienie prowadzi do właściwej zakładki.

---

## 7. Tryb rodziny (companion)

**Problem:** tryb towarzyszący jest widoczny w ustawieniach, ale brakuje czytelnego
podsumowania, co dokładnie robi.

**Zakres:**

- opis różnic między trybem pompy a trybem rodziny,
- widoczny stan udostępniania odczytów i adresu serwera,
- ostrzeżenie, gdy konfiguracja jest niekompletna.

**Kryterium akceptacji:** użytkownik bez czytania dokumentacji wie, czy jego odczyty
są udostępniane i pod jakim adresem.

---

## 8. Dostępność i lokalizacja

**Problem:** część elementów ma teksty tylko w wybranych językach lub zbyt małe dotknięcia.

**Zakres:**

- minimalny rozmiar celu dotyku 48 dp,
- `contentDescription` dla wykresu i ikon,
- pełne tłumaczenia `values`, `values-pl`, `values-de`, `values-zh`,
- obsługa powiększonego rozmiaru czcionki bez ucinania tekstów.

**Kryterium akceptacji:** brak brakujących tłumaczeń w testach lint, a przy skali czcionki
130% żaden przycisk nie traci etykiety.

---

## 9. Spójność wizualna

**Problem:** kolory, promienie i odstępy są miejscami definiowane lokalnie w kodzie.

**Zakres:**

- jeden zestaw tokenów (kolory, promienie, odstępy) w `colors.xml`/`dimens.xml`,
- jednolite promienie kart i przycisków,
- spójne użycie typografii (nagłówek, wartość, podpis).

**Kryterium akceptacji:** żaden ekran nie wprowadza nowego koloru poza paletą projektu.

---

## 10. Testy i regresje

**Problem:** zmiany UI łatwo psują logikę czystą (formatowanie, geometria wykresu).

**Zakres:**

- testy JUnit dla logiki czystej: formatowanie postępu, geometria zdarzeń, parsery,
- testy instrumentowane dla kluczowych ścieżek (parowanie, import kopii),
- checklista przed wydaniem: testy jednostkowe + `assembleDebug`.

**Kryterium akceptacji:** każda nowa funkcja czysta ma test JUnit, a wydanie przechodzi
`testDebugUnitTest` i `assembleDebug` bez błędów.
