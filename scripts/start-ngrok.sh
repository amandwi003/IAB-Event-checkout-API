#!/usr/bin/env bash
# Forwards your static ngrok domain to Spring Boot (see server.port / gradle.properties).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PORT="${SERVER_PORT:-8080}"
URL="${NGROK_URL:-https://iab-event-checkout-api-production.up.railway.app}"
cd "$ROOT"
exec ngrok http "$PORT" --url "$URL"
