# Historia zmian

Pełna, zawsze aktualna wersja znajduje się na stronie pobierania:
https://mgsupport.pl/pobierz/minimgsupportmed

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
