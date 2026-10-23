# Pozycjonowanie i komunikacja

Ten dokument ustala, **jak mówić o projekcie**, żeby nie składać obietnic medycznych
i nie wchodzić w rolę wytwórcy wyrobu medycznego. Nie jest poradą prawną — przy
dystrybucji publicznej (Google Play, sklep, firma) skonsultuj to z osobą od regulacji MDR.

## Status projektu (jedno zdanie)

Eksperymentalny, niekomercyjny projekt open source (GPL-3.0), który **pokazuje dane,
jakie pompa już wysyła**. Nie jest certyfikowanym wyrobem medycznym, nie zastępuje pompy,
sensora, zaleceń lekarza ani decyzji terapeutycznych i nie służy do ustalania dawek insuliny.

## Dlaczego to ważne

W UE o kwalifikacji jako wyrób medyczny decyduje **przeznaczenie (intended purpose)**,
a nie to, czy aplikacja wysyła polecenia do pompy (MDR 2017/745, art. 2 pkt 1;
MDCG 2019-11; Rule 11 w Załączniku VIII). Aplikacja, która pokazuje glikemię, trendy
i progi alertów, **może** zostać uznana za oprogramowanie o przeznaczeniu medycznym —
nawet jeśli jest tylko do odczytu.

Disclaimer nie zmienia przeznaczenia. Co więcej: obietnice w rodzaju „monitoruje cukier”
albo „ostrzega przed hipoglikemią” **przeczą** zdaniu „to nie jest wyrób medyczny”
i osłabiają obronę. Dlatego język musi być spójny w aplikacji, na stronie, w README
i w postach.

## Mów tak / nie mów tak

| Mów | Nie mów |
|---|---|
| „Pokazuje odczyty, które pompa już wysyła” | „Monitoruje cukier” / „monitorowanie glikemii” |
| „Podgląd danych z pompy” | „Zastępuje CGM” / „zastępuje sensor” |
| „Próg alertu to informacja, nie decyzja” | „Ostrzega przed hipoglikemią” / „chroni przed spadkiem” |
| „Eksperymentalny, niecertyfikowany projekt” | „Wyrób medyczny” / „certyfikowana aplikacja” |
| „Nie służy do ustalania dawek insuliny” | „Pomoże dobrać dawkę” / „wskazuje, ile podać” |
| „Używasz na własną odpowiedzialność” | „Bezpieczna” / „niezawodna” |
| „Dane zostają w telefonie” | „Twoje zdrowie pod kontrolą” |

Dodatkowo: nie obiecuj, że alert zadziała. Napisz wprost, że **może nie zadziałać**
(np. przy braku połączenia, uśpieniu telefonu, braku uprawnień) i nie można na nim
polegać jak na funkcji bezpieczeństwa.

## Gotowy post (wersja po korekcie)

> Zrobiłem apkę, która uruchamia się też na telefonach odrzucanych przez MiniMed Mobile.
>
> MGlikemia czyta przez Bluetooth to, co MiniMed 780G, 770G albo 680G i tak wysyła.
> Wymagania: Android 7.0 lub nowszy i Bluetooth LE. Nie ma listy wspieranych modeli ani
> komunikatu „urządzenie niekompatybilne”. Starszy Samsung, trzyletni Xiaomi, cokolwiek
> z BLE, ma szansę wystartować.
>
> Co widać: ostatni odczyt cukru z sensora, trend i tempo zmian. Obok IOB, aktualna baza
> z informacją, czy pracuje SmartGuard, poziom baterii i ile zostało w zbiorniku. Pod spodem
> wykres od godziny do roku, TIR, średnia i GMI. Historia zdarzeń z pompy też się zapisuje.
>
> Są progi alertów — to informacja, nie decyzja terapeutyczna. Alert może nie zadziałać,
> więc nie można na nim polegać jak na funkcji bezpieczeństwa.
>
> Jest eksport i import CSV. Interfejs po polsku, angielsku, niemiecku i chińsku.
>
> Dane zostają w telefonie. Nie ma konta ani centralnego serwera; opcjonalny tryb rodziny
> wymaga własnego serwera. Apka tylko czyta, nie wysyła nic do pompy i nie zmienia w niej
> ustawień.
>
> WAŻNE: to eksperymentalny, niekomercyjny projekt. Nie jest certyfikowanym wyrobem
> medycznym i nie zastępuje pompy, sensora ani zaleceń lekarza. Nie służy do ustalania
> dawek insuliny. Używacie na własną odpowiedzialność.
>
> Na razie wysyłam APK na priv. Napisz, jaką masz pompę, model telefonu i wersję Androida.
> Instalujesz poza sklepem, więc Android pokaże ostrzeżenie Play Protect.
>
> Kod jest publiczny (GPL-3.0). O Google Play pomyślę po testach — najpierw musi to przejść
> przez ludzi, którzy naprawdę noszą pompę.
>
> Szukam testerów z 780G, 770G i 680G. Najbardziej takich, którzy napiszą „wysypało się
> o 14:32, po zmianie sensora”, a nie „nie działa”. Dorzućcie zrzut ekranu, model telefonu
> i wersję Androida.
>
> Screeny są na danych demonstracyjnych, nie na czyichś wynikach.
>
> Pytania w komentarzu albo na priv.
>
> #WeAreNotWaiting #cukrzyca #diabetes #Medtronic780G #DIY

## Odpowiedź na komentarz o wyrobie medycznym

> Dzięki, zgadzam się — decyduje przeznaczenie i funkcje, nie brak zapisu do pompy.
> Na razie to niekomercyjny projekt testowy, bez obietnic terapeutycznych i bez doradzania
> dawek. Zanim wejdę do sklepu, chcę to poukładać regulacyjnie — chętnie pogadam na priv.

Nie dyskutuj publicznie o kwalifikacji prawnej. Przyznaj rację co do zasady, przenieś
rozmowę na priv.

## Checklista przed Google Play / publiczną dystrybucją

- [ ] Decyzja o pozycjonowaniu: nadal projekt eksperymentalny czy produkt komercyjny.
- [ ] Konsultacja MDR (URPL / jednostka notyfikowana / doradca): czy to SaMD i jaka klasa.
- [ ] Jeśli tak: QMS (ISO 13485), ocena kliniczna, risk management (ISO 14971),
      PMS/PMCF, UDI/EUDAMED, PRRC, oznaczenie CE.
- [ ] Polityka Google Play dla aplikacji zdrowotnych + formularz Data safety.
- [ ] Polityka prywatności pod publicznym URL-em (jest: `docs/PRIVACY.md`).
- [ ] Spójny język: aplikacja, strona, README, sklep, posty — bez obietnic medycznych.
- [ ] Jasna informacja, że alert może nie zadziałać i nie jest funkcją bezpieczeństwa.
- [ ] Brak płatności, reklam i darowizn „na rozwój”, dopóki status prawny jest nieuregulowany.

## Czego nie robić

- Nie pisz „to nie jest wyrób medyczny” jednym zdaniem i nie obiecuj jednocześnie
  monitorowania i ochrony przed hipoglikemią.
- Nie dodawaj funkcji doradzających dawki insuliny — to zmienia przeznaczenie i klasę ryzyka.
- Nie publikuj w sklepie „po cichu”, licząc, że nikt nie zauważy.
- Nie usuwaj disclaimerów, żeby UI wyglądał „czyściej”.
