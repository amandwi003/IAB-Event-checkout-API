#!/usr/bin/env bash
# Frees port 8080 and stops ngrok "http" tunnels (fixes PortInUse + ERR_NGROK_334).
set -u
PIDS=$(lsof -nP -iTCP:8080 -sTCP:LISTEN -t 2>/dev/null || true)
if [ -n "${PIDS}" ]; then
  echo "${PIDS}" | xargs kill 2>/dev/null || true
  echo "Stopped process(es) listening on 8080."
else
  echo "Nothing listening on 8080."
fi
if pkill -f '[n]grok http' 2>/dev/null; then
  echo "Stopped ngrok http tunnel(s)."
else
  echo "No ngrok http process found."
fi
sleep 1
echo "Done."
echo "  Terminal 1: ./gradlew bootRun"
echo "  Terminal 2: ./gradlew tunnel   (or: scripts/start-ngrok.sh)"
echo "Do NOT use \"ngrok http 80\" unless something listens on port 80."
