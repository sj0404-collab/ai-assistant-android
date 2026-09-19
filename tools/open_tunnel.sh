#!/usr/bin/env bash
# Open ngrok tunnel and wait for URL
# Usage: open_tunnel.sh <port> <logfile>

set -euo pipefail

PORT="${1:-8097}"
LOGFILE="${2:-/tmp/tunnel.log}"

echo "=== Starting ngrok tunnel for port $PORT ===" >&2

# Download ngrok if needed
NGROK="/tmp/ngrok"
if [ ! -x "$NGROK" ]; then
  echo "Downloading ngrok..." >&2
  curl -sL --retry 3 -o /tmp/ngrok.zip \
    'https://bin.equinox.io/c/bNyj1mQVY4c/ngrok-v3-stable-linux-amd64.zip'
  unzip -o /tmp/ngrok.zip -d /tmp/
  chmod +x "$NGROK"
  echo "ngrok downloaded" >&2
fi

# Start tunnel
echo "Starting ngrok tunnel..." >&2
nohup "$NGROK" http "$PORT" --log=stdout --log-level=debug \
  > "$LOGFILE" 2>&1 &
NGROK_PID=$!
echo $NGROK_PID > /tmp/ngrok-$PORT.pid
echo "ngrok started with PID $NGROK_PID" >&2

# Wait for URL - ngrok outputs URL to stdout/log
for i in $(seq 1 60); do
  sleep 2
  if [ -f "$LOGFILE" ]; then
    echo "Checking log for URL (attempt $i/60)..." >&2
    # ngrok v3 outputs URL in format: "https://xxx.ngrok-free.app"
    URL=$(grep -oE 'https://[a-z0-9-]+\.ngrok-free\.app' "$LOGFILE" 2>/dev/null | head -1 || true)
    if [ -z "$URL" ]; then
      URL=$(grep -oE 'https://[a-z0-9-]+\.ngrok\.io' "$LOGFILE" 2>/dev/null | head -1 || true)
    fi
    if [ -n "$URL" ]; then
      echo "Found URL: $URL" >&2
      # Verify tunnel works
      for j in $(seq 1 30); do
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