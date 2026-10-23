# Backlog i plan rozwoju (Roadmap) — MGlikemia (MGlycemia)

Niniejszy dokument rejestruje kolejne planowane usprawnienia i pomysły funkcjonalne dla aplikacji Android MGlikemia (tylko do odczytu dla pomp MiniMed 780G / 770G / 680G).

## 1. Wysoki priorytet (Kolejne kroki produktowe)

- [ ] **Ekran ustawień alarmów i progów (UI):**
  - Umożliwienie użytkownikowi w zakładce *Ustawienia* konfiguracji progu ostrzeżenia o niskim zbiorniku (domyślnie 20 j., zakres 5–50 j.).
  - Wybór i edycja maksymalnego czasu życia sensora (domyślnie 168 h / 7 dni, z możliwością zmiany dla sensorów *extended wear* lub innych modeli, np. 10–14 dni).
  - Konfiguracja czasu timeoutu braku łącza (30–45 min).

- [ ] **Udostępnianie kopii zapasowej (Android Share Sheet):**
  - Przycisk „Wyślij / Udostępnij kopię CSV” w zakładce kopii zapasowych, pozwalający przesłać plik przez e-mail, komunikatory lub zapisać w chmurze przy użyciu standardowego systemowego okna udostępniania Androida.

- [ ] **Zaawansowana diagnostyka połączenia (Ekran w aplikacji):**
  - Dedykowany podgląd stanu BLE w zakładce *Pompa*: czy serwer GATT jest otwarty, faza uwierzytelniania SAKE, stan oszczędzania baterii (Doze), oraz przycisk szybkiego generowania i wysyłania spakowanego raportu diagnostycznego.

## 2. Średni priorytet (Wygoda codziennego użytkowania)

- [ ] **Lokalny intent / integracja z xDrip+ (tylko do odczytu):**
  - Wysyłanie lokalnych broadcastów z odczytami glikemii, aby aplikacje zewnętrzne (np. xDrip+, zegarki Wear OS) mogły odbierać dane odczytane z pompy przez MGlikemię.

- [ ] **Wybór dźwięku alarmów i tryb cichy / wibracja:**
  - Konfiguracja powiadomień wysokiego priorytetu dla ciężkiej hipo/hiperglikemii: wybór własnego pliku dźwiękowego, narastająca głośność, czy powiadomienie ma dzwonić w trybie „Nie przeszkadzać”.

- [ ] **Statystyki dobowe i wskaźniki kliniczne (TIR, GMI, CV):**
  - Rozbudowa podsumowania statystyk w zakładce *Historia* o odsetek czasu w zakresie (TIR), wskaźnik GMI (Estimated HbA1c), współczynnik zmienności glikemiei (CV) oraz średnią glikemię z 7, 14 i 30 dni.

## 3. Pomysły eksperymentalne i długoterminowe

- [ ] **Widżet ekranu głównego (Home Screen Widget):**
  - Mały widget z aktualnym poziomem glikemii, strzałką trendu, IOB i wykresem miniaturowym, aktualizowany w tle przez usługę foreground service.


---
*Zasada nadrzędna projektu:* Aplikacja pozostaje **tylko do odczytu**. Żaden z powyższych punktów nie może modyfikować bazy, podawać insuliny ani wysyłać poleceń terapeutycznych do pompy.
