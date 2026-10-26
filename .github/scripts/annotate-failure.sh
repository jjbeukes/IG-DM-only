#!/usr/bin/env bash
# Turns the useful parts of failed build logs into GitHub annotations, so the reason for a red build
# shows on the run's summary page without digging through the full log.
#
# Usage: annotate-failure.sh <log>...
set -u

escape_data() {
  local s="$1"
  s="${s//'%'/'%25'}"
  s="${s//$'\r'/}"
  s="${s//$'\n'/'%0A'}"
  printf '%s' "$s"
}

escape_property() {
  local s
  s="$(escape_data "$1")"
  s="${s//':'/'%3A'}"
  s="${s//','/'%2C'}"
  printf '%s' "$s"
}

annotate() { # title, body
  [ -n "$2" ] || return 0
  echo "::error title=$(escape_property "$1")::$(escape_data "$2")"
}

for log in "$@"; do
  [ -f "$log" ] || continue

  annotate "Gradle: what went wrong ($log)" \
    "$(awk '/^\* What went wrong:/{f=1; next} /^\* Try:/{f=0} f' "$log" | head -n 25)"

  annotate "Kotlin compiler errors" "$(grep -E '^e: ' "$log" | head -n 30)"

  annotate "Resource / manifest / lint errors" \
    "$(grep -E 'AAPT|error: resource|Manifest merger|^ERROR:|Lint found|\.xml:[0-9]+: Error: ' "$log" | head -n 30)"

  # Test-case lines look like "UrlPolicyTest > blocksHome FAILED"; task lines ("> Task ... FAILED") don't count.
  annotate "Unit test failures" "$(grep -E -A 6 '^[A-Za-z0-9_.$]+ > .+ FAILED$' "$log" | head -n 60)"

  annotate "Script test failures" "$(grep -E -A 12 '^(not ok|✖)' "$log" | head -n 80)"
done
exit 0
