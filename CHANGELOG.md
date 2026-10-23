# Historia zmian

Pełna, zawsze aktualna wersja znajduje się na stronie pobierania:
https://mgsupport.pl/aplikacja/MGlikemia

## 1.0-beta52

- Aktualizacje: pobieranie pokazuje modalny pasek postępu z procentem i rozmiarem, widoczny niezależnie od zakładki, oraz stan „Instalowanie…”.
- Ustawienia: walidacja pól przy zapisie — błędne pole jest podświetlone i wskazane, zamiast tylko komunikatu Toast.
- Ustawienia: progi glikemii są pokazywane i zapisywane w wybranej jednostce (mg/dL albo mmol/L), z przeliczaniem.
- Ustawienia: przycisk „Zapisz” przeniesiony na dół karty, żeby obejmował także progi urządzenia i powiadomień; dodane nagłówki sekcji Glikemia / Urządzenie / Powiadomienia.
- Wykres: strefa prognozy ma pionową etykietę „teraz” i podpis „30 min”; nagłówek odporniejszy na wąskie ekrany.
- Lokalizacja: status sensora, zbiornik, bateria i cała karta pracy w tle (Doze) korzystają teraz z tłumaczeń zamiast polskich tekstów na sztywno.

## 1.0-beta51

- Aktualizacje: widoczny pasek postępu i procent pobierania, komunikat „Instalowanie…”.
- Wykres: prognoza 30 min przeniesiona do osobnej strefy po prawej, nie nachodzi już na linię cukru; ekstrapolacja ograniczona, żeby nie pokazywać absurdalnych skoków.
- Plan ulepszeń UI w docs/UI-PLAN.md.

## 1.0-beta50

- Alerty: pełna konfiguracja w Ustawieniach — brak łącza (30–45 min), odstęp powtarzania (1–60 min) i tryb cichy (bez dźwięku i wibracji).
- Diagnostyka: automatyczny upload zredagowanego logu na serwer po błędzie BLE (nie częściej niż raz na 10 min) oraz przycisk „Wyślij diagnostykę teraz”.
- Powiadomienie statusu pokazuje cukier w wybranej jednostce (mg/dL albo mmol/L).
- Wykres: baza (AUTO_BASAL) rysowana jako półprzezroczyste słupki na dole wykresu, wysokość proporcjonalna do podanej ilości.
- Ustawienia alarmów i diagnostyki przetłumaczone na wszystkie 4 języki.

## 1.0-beta49

- Nowy, widoczny Tryb aplikacji w Ustawieniach: „Główny telefon (z pompą)” albo „Telefon rodziny (odbiera odczyty)”. Aktywny tryb widać od razu jako plakietkę w nagłówku ekranu głównego.
- Tryb rodziny działa przez serwer synchronizacji: główny telefon wysyła odczyt, a telefon bliskiej osoby go pobiera i pokazuje na swoim pulpicie.
- Przełącznik udostępniania odczytów innym aplikacjom (xDrip+) oraz pole adresu serwera synchronizacji.
- Wykres: pasy bolus / automat / baza / ? przeniesione pod wykres, tuż przy osi czasu (zamiast wysoko nad nim).
- Wykres: wyraźna, przerywana linia prognozy glikemii na 30 minut z wartością przy prawej krawędzi.

## 1.0-beta48

- Natywny Tryb Rodziny / Opiekuna: udostępnianie odczytów na żywo między telefonami w pobliżu bez rozłączania pompy.
- Widżet na ekran główny: podgląd glikemii, trendu i IOB bezpośrednio na pulpicie.
- Pełna konfiguracja alarmów w ustawieniach: łatwa zmiana progów zbiornika, życia sensora i timeoutu braku łącza.
- Nowy adres strony pobierania: `mgsupport.pl/aplikacja/MGlikemia`.

## 1.0-beta47

- Oficjalna wersja z pełnym pakietem poprawek stabilności połączenia BLE, synchronizacją tożsamości po parowaniu oraz inżynieryjnym silnikiem statystyk klinicznych (CV i GMI).

## 1.0-beta46

- Stabilność połączenia: usunięto błąd, w którym start aplikacji i serwisu w tle uruchamiały kilka równoległych rozgłoszeń Bluetooth jednocześnie. Teraz rozgłaszanie jest jedno i stabilne.
- Stabilność parowania: 30-sekundowy timer ponownego łączenia nie przerywa już trwającego parowania ani odkrywania usług.
- Poprawka tożsamości: zapis nazwy urządzenia („Mobile NNNNNN”) po parowaniu jest teraz natychmiastowy (synchroniczny), dzięki czemu szybki restart aplikacji nie gubi nowo przypisanej tożsamości.

## 1.0-beta45

- Parowanie: log z Redmi Note 13 Pro pokazał, że telefon nie ma żadnego starego wpisu parowania (`bondedDevices count=0`), a samo parowanie przechodzi czysto. Poprzednia teoria o zaległym wpisie w telefonie była błędna.
- Diagnostyka: linie o parowaniu mają teraz godzinę i czas od połączenia, tak jak reszta logu. Wcześniej jako jedyne nie miały znacznika czasu, więc nie było widać, ile sekund mija między parowaniem a kolejnym krokiem pompy.
- Komunikat przy zerwanym połączeniu mówi teraz to, co naprawdę widać w logu: pompa łączy się i odchodzi, zanim zacznie wysyłać dane. Podpowiada sprawdzenie ekranu pompy.

## 1.0-beta44

- Parowanie: aplikacja nie próbuje już sama rozpoczynać parowania systemowego podczas łączenia z pompą. Ta próba i tak zawsze kończyła się niepowodzeniem, a mogła przeszkadzać. Parowanie prowadzi pompa.
- Parowanie: naprawiony odbiór informacji o stanie parowania od Androida. Wcześniej telefon ich nie przekazywał, dlatego w logu nie było ani jednej linii o stanie parowania. Teraz widać moment parowania i ewentualną przyczynę błędu.
- Diagnostyka: nowa linia `bondedDevices` pokazuje, jakie urządzenia telefon pamięta jako sparowane. Stary wpis pompy w telefonie to najczęstsza przyczyna sytuacji, w której pompa łączy się i zaraz się rozłącza.
- Diagnostyka: każdy odczyt i zapis pokazuje teraz stan parowania i krótki hash adresu. Nieudane odpowiedzi Bluetooth trafiają do logu zamiast być połykane.
- Diagnostyka: nowa linia `pairing request` pokazuje, czy Android wyświetla okno potwierdzenia parowania. Jeśli tak, trzeba je potwierdzić na telefonie.

## 1.0-beta43

- Parowanie BLE: próba `createBond()` jest teraz podejmowana zawsze po odczycie CCCD i zawsze zostawia wpis `createBond before=... started=... after=...`. W beta42 ten wpis nie powstał, więc nie było wiadomo, czy próba w ogóle się odbyła.
- Parowanie BLE: watchdog SAKE wydłużony z 30 do 60 s. Świeże parowanie na Realme trwało 44 s, bo pompa robi 22-sekundową pauzę przed odczytem tabeli DIS.
- Diagnostyka: potwierdzone na Realme, że po odparowaniu pompa czyta najpierw CCCD, czeka, potem czyta całą tabelę DIS (2A23, 2A29, 2A24, 2A25, 2A27, 2A26, 2A28, 2A50, 2A2A) i dopiero wtedy zapisuje CCCD.

## 1.0-beta42

- Diagnostyka: linia `bluetooth` ma teraz `localHash`, czyli krótki hash adresu BLE telefonu bez ujawniania MAC. Pozwala sprawdzić, czy adres jest stały między próbami. Stały adres oznacza, że pompa może wciąż mieć stare klucze.
- Parowanie BLE (eksperyment): gdy pompa odczyta CCCD na telefonie bez bondu, aplikacja sama rozpoczyna parowanie systemowe. Pompa jako central nigdy nie wysyłała żądania SMP, więc link zostawał nieszyfrowany i pompa odchodziła po 7 s.
- Uwaga: to zmienia wcześniejszą zasadę z AGENTS.md. Jeśli wywołanie `createBond()` w tym miejscu zerwie link, wrócimy do poprzedniego zachowania.

## 1.0-beta41

- Diagnostyka: log zaczyna się od wersji aplikacji, np. `app version=1.0-beta41 (42)`.
- Diagnostyka: każdy wpis BLE ma teraz godzinę z dokładnością do milisekund, np. `11:53:12.345 t=+2034ms ...`.
- Diagnostyka: nowa linia `clock local=... zone=...` z czytelną datą i godziną telefonu.
- Diagnostyka: nowa linia `phone` ze stanem ekranu, blokady, baterii, trybu dzwonka, DND, standby bucket i uptime.
- Diagnostyka: tablica GATT jest logowana raz na proces, żeby nie wypychać zdarzeń z bufora.
- Parowanie BLE: deskryptor CCCD wymaga szyfrowanego połączenia (zmiana z beta40). W logu widać to jako `gatt desc ... perms=0x22`.

## 1.0-beta40

- Parowanie BLE: Deskryptor CCCD wymaga teraz szyfrowanego połączenia. Na telefonie bez bondu (Galaxy S24) pompa czytała deskryptor, czekała 7 sekund i odchodziła bez zapisu. Wymóg szyfrowania zmusza ją do rozpoczęcia parowania SMP.
- Parowanie BLE: Na Realme 14 Pro+ link był już zaszyfrowany, więc ta zmiana nic tam nie zmienia.

## 1.0-beta39

- Powiadomienia: zamiast „IOB 1,00 jednostki insuliny” jest teraz „aktywna insulina 1,00 j.”, a na początku strzałka trendu.
- Powiadomienia: gdy nie ma połączenia, pokazują ostatni odczyt i jego wiek zamiast samego stanu. Łatwiej ocenić, czy dane są świeże.
- Powiadomienia: podczas rozgrzewania i aktualizacji sensora usunięto zbędne „Połączono”. Zostaje sam stan sensora i pozostały czas.

## 1.0-beta38

- Teksty: usunięto wszystkie długie i krótkie myślniki z interfejsu, dokumentacji i komentarzy. Zdania są krótsze, z normalną interpunkcją zamiast wtrąceń.
- Teksty: poprawiono kilka sformułowań w ekranie pomocy i FAQ, żeby brzmiały rzeczowo, bez marketingowego tonu.
- Komunikaty stanu: puste wartości pokazują `--` zamiast myślnika.

## 1.0-beta37

- Parowanie BLE: Wydłużono watchdog SAKE z 15 do 30 sekund. Na realnym sprzęcie pompa czyta CCCD dopiero po ~12 s i subskrybuje po ~19 s od połączenia, więc poprzedni limit mógł ubić poprawną sesję na wolniejszych telefonach.
- Potwierdzone działanie: pełny handshake SAKE, odczyt modelu `MMT-1886` i numeru seryjnego oraz synchronizacja historii na Realme 14 Pro+.

## 1.0-beta36

- Parowanie BLE: Gdy pompa zapisze CCCD (`2902`) dopiero po naszej pierwszej ramce SAKE, aplikacja wysyła ją ponownie. Wcześniej spóźniona subskrypcja była ignorowana i kończyło się to błędem `HANDSHAKE_TIMEOUT` (widoczne na Realme 14 Pro+).
- Komunikaty: Stan `AUTH_FAILED` nie jest już opisany jako „Parowanie odrzucone”. Przyczyna bywa po naszej stronie (timeout), więc etykieta brzmi teraz „Nie połączono”, a wpis w logu „Nie udało się dokończyć autoryzacji z pompą”.

## 1.0-beta35

- Parowanie BLE: Przywrócono zachowanie referencyjnej implementacji JavaPumpConnector. Odczyt deskryptora CCCD (`2902`) zwraca prawdziwy stan subskrypcji (`0x0000`), a nie sztuczne `0x0100`. Wymusza to na pompie zapis CCCD i zarejestrowanie subskrypcji w stosie Androida. Bez tego notyfikacje SAKE nie są wysyłane w ogóle.
- Parowanie BLE: Dodano diagnostykę `pump read CCCD but did not subscribe within 2s`, która jednoznacznie rozstrzyga, czy pompa w ogóle próbuje się zasubskrybować.
- Instalator i launcher pokazują teraz numer wersji w nazwie aplikacji (np. `MGlikemia 1.0-beta35`), żeby łatwo odróżnić buildy.

## 1.0-beta34

- Naprawa wydania: poprzedni plik beta33 został omyłkowo zbudowany przed podbiciem numeru wersji, więc zawierał beta32. Ta wersja jest zbudowana poprawnie (versionCode 35) i zawiera opóźnienie startu SAKE o 150 ms po odczycie CCCD.

## 1.0-beta33

- Parowanie BLE: Opóźniono start powiadomienia SAKE o 150 ms po odczycie deskryptora CCCD przez pompę. Daje to czas na poprawne zamknięcie i wysłanie pakietu potwierdzenia odczytu przez stos Bluetooth telefonu, eliminując race condition w trybie peryferyjnym na Samsungu i Xiaomi. Dodano także pełne logowanie wysyłanych ramek SAKE (`sake notify`).

## 1.0-beta32

- Aktualizacje: Poprawiono źródło pobierania pliku APK wewnątrz aplikacji. Zamiast pobierać plik z GitHub Releases (co na niektórych telefonach powodowało błąd pobierania przez przekierowania S3), aplikacja pobiera teraz gotowy plik bezpośrednio z szybkiego i pewnego serwera VPS (`mgsupport.pl`).

## 1.0-beta31

- Bateria / Doze: Dodano brakujące uprawnienie `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` w manifeście, dzięki czemu przyciski „Wyłącz optymalizację baterii” wreszcie poprawnie wywołują systemowe okno zwolnienia z oszczędzania energii na Samsungu i Xiaomi.

## 1.0-beta30

- Wykres: Przyciskach „Wcześniej” i „Później” nadano spójny wygląd (styl ciemnych chipów pasujący do reszty aplikacji, widoczny zarówno na pulpicie, jak i w trybie pełnoekranowym).
- Wykres: Poprawiono obsługę dotyku. Wykres reaguje na gesty od razu, bez wcześniejszego klikania w niego.
- Wykres: Przyciski „Wcześniej” / „Później” pozwalają teraz przewijać historię aż do końca dostępnych w bazie danych próbek (zamiast blokować się po kilku kliknięciach w ramach wybranego okna).

## 1.0-beta29

- Parowanie BLE: Naprawiono problem ze stosem pomp MiniMed. Część centrali (w tym na Xiaomi, Redmi i Samsungu) po połączeniu jedynie odczytuje stan deskryptora CCCD (`2902`) i oczekuje natychmiastowego startu SAKE, zamiast pisać do niego jawne żądanie subskrypcji. Teraz odczyt `01 00` przez pompę natychmiast uruchamia wysłanie ramki powitalnej SAKE.

## 1.0-beta28

- Pulpit jest teraz zwięzły: stan sensora i wiek odczytu mieszczą się w jednej linii,
  a kafelki zbiornika i baterii pokazują samą wartość i krótki dopisek.
- Pełne wyjaśnienie pracy w tle i oszczędzania baterii przeniesione z pulpitu do Ustawień.
- Ostrzeżenie o oszczędzaniu baterii na pulpicie pojawia się tylko wtedy, gdy system
  faktycznie ogranicza pracę aplikacji w tle.
- Nowy skrót w Ustawieniach podpowiada, jak włączyć własną ikonę powiadomienia zamiast
  logo aplikacji w pasku statusu Samsunga.
- Diagnostyka połączenia: każdy wpis ma teraz czas od nawiązania połączenia (`t=+… ms`),
  informację, do której charakterystyki należy deskryptor, pełną tablicę usług GATT
  oraz bajty rozgłaszania. Bez tego nie da się ustalić, dlaczego pompa rozłącza się
  natychmiast po odczycie deskryptora.

## 1.0-beta27

- Powiadomienie o pracy aplikacji pokazuje aktualny cukier w tytule, więc wartość jest czytelna
  od razu w belce i na ekranie blokady, bez rozwijania powiadomienia.
- Treść powiadomienia nie powtarza już tej samej wartości. Została w niej insulina na pokładzie,
  stan połączenia i wiek danych.
- Uwaga: w pasku statusu (obok baterii i Wi-Fi) telefony Samsung pokazują ikonę aplikacji,
  a nie ikonę powiadomienia. Cyfry w małej ikonie widać na urządzeniach z czystym Androidem.

## 1.0-beta26

- Diagnostyka: log techniczny zawiera teraz pełny opis telefonu i Bluetooth: model,
  producent, wersję Androida, cechy adaptera, uprawnienia, stan oszczędzania baterii
  i zużycie pamięci. Łatwiej ustalić, dlaczego konkretny telefon nie łączy się z pompą.
- Ustawienia systemowe: nowy skrót w aplikacji otwiera ekrany Bluetooth, listę aplikacji
  systemowych, uprawnienia, powiadomienia i oszczędzanie baterii: bez szukania ich w menu.
- Nowe powiadomienia: niski poziom insuliny w zbiorniku (próg do ustawienia), zbliżający się
  koniec ważności sensora oraz brak danych z pompy przez dłuższy czas.
- Czas życia sensora nie jest już z góry zakładany jako 7 dni. Limity dla znanych modeli
  (Guardian, Enlite, dłuższe noszenie) można dopasować, a komunikat pompy o wymianie sensora
  ma pierwszeństwo przed wyliczeniami.
- Powiadomienie o pracy aplikacji pokazuje aktualny cukier w miejscu małej ikony, a po
  rozwinięciu dodatkowo dużą, kolorową wartość ze strzałką trendu i wiekiem danych.
- Wykres: przewijanie i powiększanie osi czasu oraz przycisk „Ostatnie 24 h”.
- Pulpit: kafelki zbiornika, baterii pompy, stanu sensora i wieku danych, wraz z podpowiedzią,
  jak wyłączyć optymalizację baterii, która usypia odczyty w tle.
- Historia: synchronizacja wznawia się od ostatniego pobranego zdarzenia zamiast od początku,
  co skraca czas łączenia z pompą.
- Diagnostyka: możliwość wyeksportowania logów do pliku, z ukrytymi danymi wrażliwymi.

Pliki APK i logi nie są szyfrowane.

## 1.0-beta25

- Parowanie BLE: naprawiono synchronizację bufora deskryptora CCCD (`2902`) w serwerze GATT
  dla rygorystycznych stosów Bluetooth w telefonach Samsung (Galaxy S24) i Xiaomi.

## 1.0-beta24

- Parowanie BLE: poprawiono stabilność inicjalizacji subskrypcji SAKE (`2902`) dla
  telefonów Xiaomi (Redmi Note 13 Pro 5G) oraz Samsung (Galaxy S24).
- Wyeliminowano przedwczesne zrywanie połączenia GATT zaraz po odczycie deskryptora CCCD.

## 1.0-beta23

- Aktualizacje: aplikacja nie ufa już pojedynczej odpowiedzi „brak nowszej wersji”.
  Porównuje GitHub API, przekierowanie wydania i feed Atom, i wybiera najwyższą wersję.
- Dzięki temu nieświeży wskaźnik „latest” ani limit API nie ukrywają nowego wydania.

## 1.0-beta22

- Aplikacja odróżnia inicjalizację nowego sensora od zwykłej aktualizacji.
- Podczas dwóch godzin rozgrzewania baner i powiadomienie pokazują „Inicjalizacja sensora”
  wraz z przybliżonym pozostałym czasem (np. „1 godz. 45 min”).
- Po zakończeniu rozgrzewania lub przy braku danych o czasie aplikacja wraca do komunikatu
  „Aktualizacja sensora”.

## 1.0-beta21

- Historia zdarzeń pokazuje dokładną datę i godzinę z czasu pompy (`RRRR-MM-DD GG:MM:SS`)
  zamiast względnego `t+… s`.
- Przy pierwszym połączeniu aplikacja uzupełnia całą dostępną historię z pamięci pompy,
  więc poprawne daty dotyczą także wcześniejszych zdarzeń, a wykres otrzymuje rzeczywiste bolusy.
- Surowe rekordy historii są zapisywane lokalnie, co pozwala przeliczyć czasy po dostarczeniu
  rekordu referencyjnego.

## 1.0-beta20

- Aktualizacje: gdy GitHub API jest zablokowane limitem zapytań (HTTP 403), aplikacja
  czyta najnowszy tag z publicznego przekierowania GitHub Releases, które nie ma tego limitu.
- Automatyczne sprawdzanie przy otwarciu aplikacji co 15 minut zamiast raz na dobę.
- Komunikat o nieudanym sprawdzeniu pojawia się już tylko wtedy, gdy obie ścieżki zawiodą.

## 1.0-beta19

- Parowanie: PnP ID (`2A50`) ma teraz wymagane 7 bajtów zamiast 1. Pompa czytająca
  tę charakterystykę jako ostatnią odrzucała tabelę Device Information i zrywała
  połączenie tuż przed subskrypcją SAKE.
- Rozgłaszanie jest zatrzymywane natychmiast po połączeniu pompy, zgodnie
  z działającym JavaPumpConnector i PythonPumpConnector.

## 1.0-beta18

- Nazwa urządzenia w usłudze Device Information jest teraz zgodna z aktualnie
  rozgłaszaną nazwą po jej zmianie, więc pompa nie porównuje dwóch różnych wartości.

## 1.0-beta17

- Parowanie: każda instalacja ma unikalną nazwę `Mobile NNNNNN`, a „Usuń parowanie”
  zmienia ją na nową. Omija to stare wpisy w pompie i konflikty kluczy, które na
  Androidzie 16 powodowały zerwanie linku zaraz po odczycie `2902`.
- Parowanie: rozgłaszanie nie jest już zatrzymywane w trakcie bondingu, odpowiedzi
  GATT są wysyłane przed startem SAKE, a nieudane parowanie ma czytelny komunikat.
- Kopia zapasowa CSV w wybranym przez użytkownika folderze (np. Dokumenty/MGlikemia),
  przeżywa odinstalowanie i wymaga ponownego wskazania folderu po reinstalacji.
- Pełnoekranowy wykres oraz bolusy, korekty i automatyczne mikroboleusy odczytane
  z pompy, z rozróżnieniem dawek ręcznych i automatycznych.

## 1.0-beta13

- Aktualizacja techniczna, bez zmian w interfejsie.

## 1.0-beta12

- Nowa nazwa: MGlikemia po polsku, MGlycemia w pozostałych językach.
- Nowa instalacja. Historię przeniesiesz przez eksport i import pliku CSV.
- Pierwsze wydanie publiczne z automatycznymi aktualizacjami w aplikacji.

## 1.0-beta11

- Poprawka sprawdzania dostępnych aktualizacji.

## 1.0-beta10

- Aktualizacje prosto z aplikacji: sprawdzanie nowej wersji, powiadomienie,
  pobranie i instalacja bez wychodzenia z apki.
- Karta „Aktualizacje” w Ustawieniach z numerem wersji.

## 1.0-beta9

- Poprawka parowania z pompą na telefonach Samsung.
- Wyraźniejsza podpowiedź, kiedy potwierdzić powiązanie Bluetooth.

## 1.0-beta8

- Nowa nazwa aplikacji i nowa instalacja.
- Kod źródłowy publiczny, link w Ustawieniach.

## 1.0-beta7

- Karta „Licencja i źródła” w Ustawieniach.

## 1.0-beta6

- Przyciski na zakładce Pompa pełnej szerokości, tekst nie ucina się.

## 1.0-beta5

- Poprawki stabilności połączenia, szczególnie na Samsungach.
- Czytelniejsze komunikaty błędów parowania.

## 1.0-beta4

- Aktualizacja sensora widoczna w aplikacji, zamiast czerwonego błędu.
- Ostatni znany odczyt zostaje na ekranie.

## 1.0-beta3

- Powiadomienie otwiera aplikację po kliknięciu.
- Brak fałszywego błędu przy rozgrzewaniu sensora.

## 1.0-beta2

- Poprawki parowania z pompą.
- Automatyczny powrót do rozgłaszania po zerwaniu połączenia.

## 1.0-beta1

- Pierwsza wersja testowa: podgląd glikemii, IOB, bazy, baterii i zbiornika.
- Wykresy od godziny do roku, TIR, średnia i GMI.
- Eksport i import CSV, interfejs PL/EN/DE/ZH.
