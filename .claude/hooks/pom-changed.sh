#!/usr/bin/env bash
#
# PostToolUse on Edit|Write: if a POM changed, the module manifest is now stale.
#
# The code index needs no equivalent hook - its file watcher follows edits on a 6s debounce. The
# manifest is not watched, and a drifted manifest is worse than no manifest, because an agent that
# trusts it stops checking. Hence the reminder, at the moment it becomes true.
set -uo pipefail
FILE="$(python3 -c '
import json,sys
try: d=json.load(sys.stdin)
except Exception: print(""); raise SystemExit
r=d.get("tool_response") or {}
ti=d.get("tool_input") or {}
print(r.get("filePath") or ti.get("file_path") or ti.get("path") or "")')"

case "$FILE" in
  */pom.xml|pom.xml)
    python3 -c '
import json
print(json.dumps({"hookSpecificOutput": {"hookEventName": "PostToolUse",
  "additionalContext": "A POM changed, so project-index.json and PROJECT_INDEX.md are now stale. Run `scripts/manifest.sh build` before using any module role, dependency list or gate command from them. `scripts/gate.sh` regenerates it for you, but a manifest read before that point is wrong."}}))' ;;
  *) exit 0 ;;
esac
