#!/usr/bin/env bash
# Installs the post-commit hook into config-repo/.git/hooks.
# Git hooks are not tracked content, so they must be installed rather than cloned.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$HERE/../config-repo"
HOOK_DIR="$REPO_DIR/.git/hooks"

if [ ! -d "$REPO_DIR/.git" ]; then
  echo "ERROR: $REPO_DIR is not a git repository. Run: git -C '$REPO_DIR' init -b main" >&2
  exit 1
fi

mkdir -p "$HOOK_DIR"
cp "$HERE/post-commit" "$HOOK_DIR/post-commit"
chmod +x "$HOOK_DIR/post-commit"

echo "Installed post-commit hook -> $HOOK_DIR/post-commit"
echo "Commits to config-repo will now notify ${CONFIG_SERVER_MONITOR_URL:-http://localhost:8888/monitor}"
