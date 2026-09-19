#!/usr/bin/env bash
# Open Cloudflare tunnel and wait for URL
# Usage: open_tunnel.sh <port> <logfile>

set -euo pipefail

PORT="${1:-8097}"
LOGFILE="${2:-/tmp/tunnel.log}"

echo "=== Starting tunnel for port $PORT ===" >&2

# Download cloudflared if needed - use specific version
CF="/tmp/cloudflared"
CF_VERSION="2024.9.1"
if [ ! -x "$CF" ]; then
  echo "Downloading cloudflared v$CF_VERSION..." >&2
  curl -sL --retry 3 -o "$CF" \
    "https://github.com/cloudflare/cloudflared/releases/download/${CF_VERSION}/cloudflared-linux-amd64"
  chmod +x "$CF"
  echo "cloudflared downloaded" >&2
fi

# Start tunnel
echo "Starting cloudflared tunnel..." >&2
nohup "$CF" tunnel --url "http://localhost:$PORT" --no-autoupdate --loglevel debug \
  > "$LOGFILE" 2>&1 &
CF_PID=$!
echo $CF_PID > /tmp/cloudflared-$PORT.pid
echo "cloudflared started with PID $CF_PID" >&2

# Wait for URL - cloudflared outputs URL to stderr/log
for i in $(seq 1 90); do
  sleep 2
  if [ -f "$LOGFILE" ]; then
    echo "Checking log for URL (attempt $i/90)..." >&2
    # Try multiple patterns for cloudflared URL
    URL=$(grep -oE 'https://[a-z0-9-]+\.trycloudflare\.com' "$LOGFILE" 2>/dev/null | head -1 || true)
    if [ -z "$URL" ]; then
      URL=$(grep -oE 'https://[a-z0-9-]+\.cloudflareaccess\.com' "$LOGFILE" 2>/dev/null | head -1 || true)
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
      # Debug: show last 10 lines of log
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