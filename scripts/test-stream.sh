#!/bin/sh
set -e

echo "=== 1. Requesting authenticated dev session ==="
AUTH_RESP=$(curl -s -c /tmp/cookies.txt -X POST http://localhost/api/auth/dev-session)
echo "$AUTH_RESP"

SESSION_ID=$(echo "$AUTH_RESP" | grep -o '"sessionId":"[^"]*' | cut -d'"' -f4)
echo "Active Session ID: $SESSION_ID"

echo "=== 2. Streaming Chat Response chunk by chunk (curl -N) ==="
echo '{"content":"How do I configure this?"}' > /tmp/payload.json

curl -N -i -s -X POST "http://localhost/api/chat/sessions/${SESSION_ID}/messages" \
  -b /tmp/cookies.txt \
  -H "Content-Type: application/json" \
  --data-binary "@/tmp/payload.json" | while IFS= read -r line; do
    echo "[$(date +'%H:%M:%S')] $line"
done
