#!/usr/bin/env bash
# Open localtunnel tunnel and wait for URL
# Usage: open_tunnel.sh <port> <logfile>

set -euo pipefail

PORT="${1:-8097}"
LOGFILE="${2:-/tmp/tunnel.log}"

echo "=== Starting localtunnel for port $PORT ===" >&2

# Install localtunnel if needed
LT="/tmp/node_modules/.bin/lt"
if [ ! -x "$LT" ]; then
  echo "Installing localtunnel..." >&2
  npm install -g localtunnel >/tmp/lt-install.log 2>&1 || true
  LT=$(command -v lt || echo "/tmp/node_modules/.bin/lt")
  echo "localtunnel installed" >&2
fi

# Start tunnel
echo "Starting localtunnel..." >&2
nohup "$LT" --port "$PORT" --subdomain "ai-hub-${GITHUB_RUN_ID:-$$}" \
  > "$LOGFILE" 2>&1 &
LT_PID=$!
echo $LT_PID > /tmp/lt-$PORT.pid
echo "localtunnel started with PID $LT_PID" >&2

# Wait for URL - localtunnel outputs URL to stdout
for i in $(seq 1 60); do
  sleep 3
  if [ -f "$LOGFILE" ]; then
    echo "Checking log for URL (attempt $i/60)..." >&2
    # localtunnel outputs: "your url is: https://xxx.loca.lt"
    URL=$(grep -oE 'https://[a-z0-9-]+\.loca\.lt' "$LOGFILE" 2>/dev/null | head -1 || true)
    if [ -z "$URL" ]; then
      URL=$(grep -oE 'https://[a-z0-9-]+\.localtunnel\.me' "$LOGFILE" 2>/dev/null | head -1 || true)
    fi
    if [ -n "$URL" ]; then
      echo "Found URL: $URL" >&2
      # Verify tunnel works
      for j in $(seq 1 20); do
        if curl -sf -o /dev/null -m 15 "$URL/"; then
          echo "Tunnel verified: $URL" >&2
          echo "$URL"
          exit 0
        fi
        sleep 1
      done
      echo "URL found but tunnel not responding yet" >&2
    else
      echo "No URL found yet in log" >&2
      tail -10 "$LOGFILE" >&2
    fi
  else
    echo "Log file not found yet" >&2
  fi
done

echo "Timeout waiting for tunnel URL" >&2
echo "=== Full Log contents ===" >&2
cat "$LOGFILE" 2>/dev/null | head -100 >&2
exit 1