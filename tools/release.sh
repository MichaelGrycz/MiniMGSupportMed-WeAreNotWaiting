#!/usr/bin/env bash
# Publishes a new MGlikemia / MGlycemia release in one go.
#
# Usage:
#   tools/release.sh 1.0-beta13 "Pierwsza zmiana" "Druga zmiana"
#
# Steps: bump version, build, upload the APK and the updated page to mgsupport.pl,
# rebuild the site, commit, tag, push and create the GitHub release with the APK.
set -euo pipefail

VERSION="${1:-}"
if [ -z "$VERSION" ]; then
  echo "usage: tools/release.sh <version> [notes...]"
  exit 1
fi
shift || true
NOTES="${*:-Aktualizacja techniczna, bez zmian w interfejsie.}"

# The notes land in the public changelog on mgsupport.pl, so they have to read
# like a user-facing change list, not like a commit message.
case "$NOTES" in
  *commit*|*skrypt*|*build*|*repo*|*tag*|*GATT*|*CCCD*|*release.sh*|*submodu*)
    echo "UWAGA: notatki wyglądają technicznie, a trafią na publiczną stronę."
    printf '  %s
' "$NOTES"
    echo "  Napisz je językiem użytkownika, np. \"Poprawka parowania na Samsungach.\""
    ;;
esac

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

	SSH_HOST="VPS-MGSUPPORT"
	REMOTE_APK_DIR="/var/www/mg-support-downloads/pobierz"
	REMOTE_PAGE="/var/www/mgsupport-astro/src/pages/aplikacja/MGlikemia.astro"
	REMOTE_SITE="/var/www/mgsupport-astro"
	NGINX_CONF="/etc/nginx/sites-available/mgsupport.pl"
	REPO="MichaelGrycz/MiniMGSupportMed-WeAreNotWaiting"
	ASSET="MGLycemia-$VERSION.apk"
	JAVA_HOME="${JAVA_HOME:-/c/Program Files/Android/Android Studio/jbr}"

echo "==> version $VERSION, asset $ASSET"

CODE=$(( $(grep -oP 'versionCode = \K[0-9]+' app/build.gradle.kts) + 1 ))
sed -i "s/versionCode = [0-9]*/versionCode = $CODE/" app/build.gradle.kts
sed -i "s/versionName = \"[^\"]*\"/versionName = \"$VERSION\"/" app/build.gradle.kts
echo "==> versionCode $CODE"

echo "==> build"
"$JAVA_HOME/bin/java.exe" -Dorg.gradle.appname=gradlew \
  -classpath gradle/wrapper/gradle-wrapper.jar \
  org.gradle.wrapper.GradleWrapperMain :app:assembleDebug \
  --no-daemon --console=plain | tail -3

cp app/build/outputs/apk/debug/app-debug.apk "$ASSET"

echo "==> upload apk"
scp -q -o BatchMode=yes -o ConnectTimeout=15 "$ASSET" "$SSH_HOST:$REMOTE_APK_DIR/"

echo "==> update download page"
scp -q -o BatchMode=yes -o ConnectTimeout=15 tools/site/MGlikemia.astro "$SSH_HOST:$REMOTE_PAGE"
ssh -o BatchMode=yes -o ConnectTimeout=15 "$SSH_HOST" "
  mkdir -p /var/www/mgsupport-astro/src/pages/pobierz
  echo 'import { useEffect } from \"react\"; export default function() { typeof window !== \"undefined\" && window.location.replace(\"/aplikacja/MGlikemia\"); return null; }' > /var/www/mgsupport-astro/src/pages/pobierz/minimgsupportmed.astro
"

echo "==> rebuild site"
ssh -o BatchMode=yes -o ConnectTimeout=15 "$SSH_HOST" "
  set -e
  sudo sed -i 's#MGLycemia-[0-9][^ ]*\.apk#$ASSET#g' $NGINX_CONF
  cd $REMOTE_SITE
  export PATH=/home/debian/.nvm/versions/node/v22.23.2/bin:\$PATH
  npm run build >/dev/null 2>&1
  sudo /usr/sbin/nginx -t >/dev/null
  sudo systemctl reload nginx
  ls $REMOTE_APK_DIR/MGLycemia-*.apk | grep -v '$ASSET' | xargs -r rm -f
"

echo "==> git"
git add -A
if git diff --cached --quiet; then
  echo "    nothing to commit"
else
  GIT_EDITOR=true git commit -q -m "Release $VERSION" -m "$NOTES"
fi
git tag "v$VERSION" 2>/dev/null || echo "    tag v$VERSION already exists"
git push -q origin public/main:main
git push -q origin "v$VERSION"

echo "==> github release"
python tools/github_release.py "$REPO" "v$VERSION" "$VERSION" "$NOTES" "$ASSET"

echo "==> done"
	echo "    page:    https://mgsupport.pl/aplikacja/MGlikemia"
	echo "    release: https://github.com/$REPO/releases/tag/v$VERSION"
