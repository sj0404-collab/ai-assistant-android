#!/usr/bin/env bash
# Publish session info to GitHub repo as JSON file
# Usage: publish_session.sh key=value ...

set -euo pipefail

# Parse args
declare -A KV
for arg in "$@"; do
  k="${arg%%=*}"
  v="${arg#*=}"
  KV["$k"]="$v"
done

SLOT="${KV[slot]:-hub}"
STATE="${KV[state]:-running}"
KIND="${KV[kind]:-AI-Hub}"
OS="${KV[os]:-linux}"
URL="${KV[url]:-}"
LABEL="${KV[label]:-}"
TOOLS="${KV[tools]:-}"
AUTH="${KV[auth]:-gate}"
FILE="${KV[file]:-session-$SLOT.json}"
JSON_FILE="${KV[json]:-}"

REPO="${GITHUB_REPOSITORY:-}"
BRANCH="session-state"

if [ -z "$REPO" ]; then
  echo "GITHUB_REPOSITORY not set" >&2
  exit 1
fi

# Build JSON
if [ -n "$JSON_FILE" ] && [ -f "$JSON_FILE" ]; then
  CONTENT=$(cat "$JSON_FILE")
else
  TIMESTAMP=$(date -u +"%Y-%m-%dT%H:%M:%SZ")
  CONTENT=$(python3 -c "
import json, sys
d = {
  'slot': '$SLOT',
  'state': '$STATE',
  'kind': '$KIND',
  'os': '$OS',
  'url': '$URL',
  'label': '$LABEL',
  'tools': '$TOOLS',
  'auth': '$AUTH',
  'updated': '$TIMESTAMP'
}
print(json.dumps(d, ensure_ascii=False))
")
fi

# Create/update file on session-state branch
TMPDIR=$(mktemp -d)
cd "$TMPDIR"

git init -q
git config user.name "github-actions[bot]"
git config user.email "github-actions[bot]@users.noreply.github.com"
git remote add origin "https://x-access-token:${GH_TOKEN}@github.com/$REPO.git"

# Try to fetch session-state branch
if git fetch origin "$BRANCH" --depth=1 2>/dev/null; then
  git checkout "$BRANCH" 2>/dev/null || git checkout -b "$BRANCH"
else
  git checkout -b "$BRANCH"
fi

echo "$CONTENT" > "$FILE"
git add "$FILE"
git commit -m "Update $FILE" -q
git push origin "$BRANCH" --force -q

echo "Published $FILE to $BRANCH"