# Wydawanie wersji

Cały proces to jedna komenda:

```bash
tools/release.sh 1.0-beta14 "Poprawka parowania na Samsungach." "Szybszy start aplikacji."
```

## Co robi skrypt

1. podbija `versionCode` o 1 i ustawia `versionName`
2. buduje APK przez JBR z Android Studio
3. kopiuje plik jako `MGLycemia-<wersja>.apk` i wysyła go na serwer
4. aktualizuje stronę `mgsupport.pl/aplikacja/MGlikemia`: numer wersji i alias pliku APK
5. przebudowuje Astro, restartuje usługę `mgsupport-astro` w pm2, sprawdza konfigurację nginx i przeładowuje go
6. zostawia poprzednie APK na serwerze (historia pobrań)
7. robi commit, tag `v<wersja>`, push gałęzi i taga
8. tworzy wydanie na GitHubie i wgrywa do niego APK

## Notatki do wydania

Trafiają na publiczną stronę, więc pisz je językiem użytkownika:

- dobrze: „Poprawka parowania na telefonach Samsung.”
- źle: „Refaktor UpdateChecker, dodany commit i tag w release.sh”

Skrypt ostrzega, gdy notatki zawierają słowa techniczne.

## Wymagania

- `JAVA_HOME` wskazujący na JBR Android Studio, domyślnie
  `/c/Program Files/Android/Android Studio/jbr`
- dostęp SSH do `VPS-MGSUPPORT` (alias w `~/.ssh/config`)
- zapisane poświadczenie GitHuba w menedżerze poświadczeń gita.
  Gdy go brak, wystarczy raz `gh auth login`

## Ręcznie, gdy skrypt zawiedzie

```bash
# build
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
"$JAVA_HOME/bin/java.exe" -Dorg.gradle.appname=gradlew \
  -classpath gradle/wrapper/gradle-wrapper.jar \
  org.gradle.wrapper.GradleWrapperMain :app:assembleDebug --no-daemon

# publikacja APK i strony
scp MGLycemia-<wersja>.apk VPS-MGSUPPORT:/var/www/mg-support-downloads/pobierz/
scp tools/site/MGlikemia.astro VPS-MGSUPPORT:/var/www/mgsupport-astro/src/pages/aplikacja/MGlikemia.astro
ssh VPS-MGSUPPORT "cd /var/www/mgsupport-astro && npm run build && pm2 restart mgsupport-astro && sudo nginx -t && sudo systemctl reload nginx"

# git
git tag v<wersja> && git push origin public/main:main --tags

# wydanie
python tools/github_release.py <owner/repo> v<wersja> <wersja> "<notatki>" MGLycemia-<wersja>.apk
```

## Licznik pobrań

Pobrania APK trafiają do dedykowanego logu `/var/log/nginx/mglycemia-downloads.log`.
Prywatne statystyki (widoczne tylko dla administratora, nie na stronie) odświeża cron co 15 min:

```bash
ssh VPS-MGSUPPORT "sudo /usr/local/bin/mglycemia-download-stats --print"
ssh VPS-MGSUPPORT "sudo cat /var/lib/mglycemia/download-stats.json"
```

## Wersjonowanie

`1.0-betaN` w trakcie testów. Numer rośnie monotonicznie, `versionCode` również.
`UpdateChecker` porównuje segmenty numeryczne, więc `1.0-beta10` jest nowsze niż
`1.0-beta9`.

## Aktualizacje w aplikacji

Aplikacja czyta najnowsze **wydanie** z GitHuba, nie sam tag. Wydanie musi mieć
załączony plik `.apk`, inaczej klient nie znajdzie adresu pobrania. Po publikacji
wydania użytkownicy starszych wersji dostaną powiadomienie i okno aktualizacji.

## Przejście do Google Play

Po publikacji w Sklepie samoinstalowanie APK przestaje być dobrym rozwiązaniem.
`UpdateChecker` należy wtedy zastąpić Play In-App Updates. Reszta kodu zostaje.
