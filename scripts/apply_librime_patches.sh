#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PATCH="$ROOT/scripts/patches/complete_syllables_first.patch"
RIME="$ROOT/third_party/librime"
if patch -d "$RIME" -p1 --dry-run --forward < "$PATCH" >/dev/null 2>&1; then
  patch -d "$RIME" -p1 --forward < "$PATCH"
elif patch -d "$RIME" -p1 --dry-run --reverse < "$PATCH" >/dev/null 2>&1; then
  echo "complete-syllable patch already applied"
else
  echo "pinned librime source does not match complete-syllable patch" >&2
  exit 2
fi
