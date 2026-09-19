#!/usr/bin/env bash
# Open serveo.net SSH tunnel and wait for URL
# Usage: open_tunnel.sh <port> <logfile>

set -euo pipefail

PORT="${1:-8097}"
LOGFILE="${2:-/tmp/tunnel.log}"

echo "=== Starting serveo.net SSH tunnel for port $PORT ===" >&2

# Start tunnel using SSH to serveo.net
echo "Starting serveo.net tunnel..." >&2
nohup ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null \
  -R "80:localhost:$PORT" serveo.net \
  > "$LOGFILE" 2>&1 &
SSH_PID=$!
echo $SSH_PID > /tmp/serveo-$PORT.pid
echo "serveo.net tunnel started with PID $SSH_PID" >&2

# Wait for URL - serveo.net outputs URL to stdout
for i in $(seq 1 60); do
  sleep 3
  if [ -f "$LOGFILE" ]; then
    echo "Checking log for URL (attempt $i/60)..." >&2
    # serveo.net outputs: "Forwarding HTTP traffic from https://xxx.serveo.net"
    URL=$(grep -oE 'https://[a-z0-9-]+\.serveo\.net' "$LOGFILE" 2>/dev/null | head -1 || true)
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