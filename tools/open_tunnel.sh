#!/usr/bin/env bash
# Open Cloudflare tunnel and wait for URL
# Usage: open_tunnel.sh <port> <logfile>

set -euo pipefail

PORT="${1:-8097}"
LOGFILE="${2:-/tmp/tunnel.log}"

# Download cloudflared if needed
CF="/tmp/cloudflared"
if [ ! -x "$CF" ]; then
  curl -sL --retry 3 -o "$CF" \
    'https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-amd64'
  chmod +x "$CF"
fi

# Start tunnel
nohup "$CF" tunnel --url "http://localhost:$PORT" --no-autoupdate --loglevel info \
  > "$LOGFILE" 2>&1 &
echo $! > /tmp/cloudflared-$PORT.pid

# Wait for URL
for i in $(seq 1 40); do
  sleep 2
  if [ -f "$LOGFILE" ]; then
    URL=$(grep -oE 'https://[a-z0-9-]+\.trycloudflare\.com' "$LOGFILE" | head -1)
    if [ -n "$URL" ]; then
      # Verify tunnel works
      for j in $(seq 1 20); do
        if curl -sf -o /dev/null -m 10 "$URL/"; then
          echo "$URL"
          exit 0
        fi
        sleep 1
      done
    fi
  fi
done

exit 1