#!/usr/bin/env bash
# Prywatny licznik pobrań MGlikemia (widoczny wyłącznie dla administratora VPS).
# Liczy udane (HTTP 200) pobrania plików .apk z /pobierz/ na podstawie dedykowanego logu Nginx.
set -euo pipefail

LOG="/var/log/nginx/mglycemia-downloads.log"
OUT="/var/lib/mglycemia/download-stats.json"

apk_lines() {
  [ -f "$LOG" ] || return 0
  grep '\.apk' "$LOG" 2>/dev/null | grep '" 200 ' || true
}

total=$(apk_lines | wc -l)
today=$(apk_lines | grep -c "$(date +%d/%b/%Y)" || true)
unique=$(apk_lines | awk '{print $1}' | sort -u | wc -l)

mkdir -p "$(dirname "$OUT")"
printf '{"total":%s,"today":%s,"unique_ips":%s,"updated":"%s"}\n' \
  "$total" "$today" "$unique" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" > "$OUT"

if [ "${1:-}" = "--print" ]; then
  echo "Pobrania APK: total=$total today=$today unique_ips=$unique"
fi
