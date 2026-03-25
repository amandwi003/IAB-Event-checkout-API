#!/usr/bin/env bash
# Forwards your static ngrok domain to Spring Boot (see server.port / gradle.properties).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PORT="${SERVER_PORT:-8080}"
URL="${NGROK_URL:-https://staphylomatic-slimily-vivien.ngrok-free.dev}"
cd "$ROOT"
exec ngrok http "$PORT" --url "$URL"
