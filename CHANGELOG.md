# Historia zmian

Pełna, zawsze aktualna wersja znajduje się na stronie pobierania:
https://mgsupport.pl/pobierz/minimgsupportmed

## 1.0-beta32

- Aktualizacje: Poprawiono źródło pobierania pliku APK wewnątrz aplikacji. Zamiast pobierać plik z GitHub Releases (co na niektórych telefonach powodowało błąd pobierania przez przekierowania S3), aplikacja pobiera teraz gotowy plik bezpośrednio z szybkiego i pewnego serwera VPS (`mgsupport.pl`).

## 1.0-beta31

- Bateria / Doze: Dodano brakujące uprawnienie `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` w manifeście, dzięki czemu przyciski „Wyłącz optymalizację baterii” wreszcie poprawnie wywołują systemowe okno zwolnienia z oszczędzania energii na Samsungu i Xiaomi.

## 1.0-beta30

- Wykres: Przyciskach „Wcześniej” i „Później” nadano spójny wygląd (styl ciemnych chipów pasujący do reszty aplikacji, widoczny zarówno na pulpicie, jak i w trybie pełnoekranowym).
- Wykres: Poprawiono obsługę dotyku — wykres reaguje na gesty od razu, bez konieczności wcześniejszego klikania w niego.
- Wykres: Przyciski „Wcześniej” / „Później” pozwalają teraz przewijać historię aż do końca dostępnych w bazie danych próbek (zamiast blokować się po kilku kliknięciach w ramach wybranego okna).

## 1.0-beta29

- Parowanie BLE: Naprawiono kluczowy problem ze stosem pomp MiniMed — część centrali (w tym na Xiaomi/Redmi i Samsungu) po połączeniu jedynie odczytuje stan deskryptora CCCD (`2902`) i oczekuje natychmiastowego startu SAKE, zamiast pisać do niego jawne żądanie subskrypcji. Teraz odczyt `01 00` przez pompę natychmiast uruchamia wysłanie ramki powitalnej SAKE.

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
- Treść powiadomienia nie powtarza już tej samej wartości — została w niej insulina na pokładzie,
  stan połączenia i wiek danych.
- Uwaga: w pasku statusu (obok baterii i Wi-Fi) telefony Samsung pokazują ikonę aplikacji,
  a nie ikonę powiadomienia. Cyfry w małej ikonie widać na urządzeniach z czystym Androidem.

## 1.0-beta26

- Diagnostyka: log techniczny zawiera teraz pełny opis telefonu i Bluetooth — model,
  producent, wersję Androida, cechy adaptera, uprawnienia, stan oszczędzania baterii
  i zużycie pamięci. Łatwiej ustalić, dlaczego konkretny telefon nie łączy się z pompą.
- Ustawienia systemowe: nowy skrót w aplikacji otwiera ekrany Bluetooth, listę aplikacji
  systemowych, uprawnienia, powiadomienia i oszczędzanie baterii — bez szukania ich w menu.
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
