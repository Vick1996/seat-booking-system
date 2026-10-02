#!/usr/bin/env sh
# One-command on-sale stampede:  ./burst.sh <BASE_URL>      (needs only a JDK 17+)
# Tunables via env: BURST_REQUESTS BURST_USERS BURST_SEATS BURST_HOT BURST_CONCURRENCY BURST_ADMIN_KEY
set -eu
BASE_URL="${1:-${BASE_URL:-http://localhost:8080}}"
exec java "$(dirname "$0")/burst/Burst.java" "$BASE_URL"
