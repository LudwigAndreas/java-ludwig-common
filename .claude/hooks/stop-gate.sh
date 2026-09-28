#!/usr/bin/env bash
#
# Stop: if tracked files changed in this session and no verification ran, print the gate.
#
# This is the cheapest possible check for the most common failure: a change that looks finished
# because the edits are done. It does not run the build - a Stop hook that took eight minutes
# would be disabled within a day - it just states what has not been run, and names the command.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
INPUT="$(cat)"

# Tracked files only. The working tree here habitually carries untracked in-flight work, and
# warning about that on every stop is how a hook becomes noise.
CHANGED="$(git -C "$ROOT" diff --name-only HEAD 2>/dev/null | head -40)"
[ -n "$CHANGED" ] || exit 0

TRANSCRIPT="$(printf '%s' "$INPUT" | python3 -c '
import json,sys
try: print((json.load(sys.stdin).get("transcript_path") or ""))
except Exception: print("")')"

# A verify anywhere in the session counts. Being precise about WHICH module was verified would
# need the manifest and the diff, and a Stop hook is not the place to be clever.
if [ -n "$TRANSCRIPT" ] && [ -f "$TRANSCRIPT" ] \
   && grep -qE 'mvn[^"]*(-am )?verify|gate\.sh|clean install' "$TRANSCRIPT" 2>/dev/null; then
  exit 0
fi

# Map each changed file to the module that owns it, via the manifest's module paths. Modules live
# one level down (build/, services/, sources/), so the first path component is a group directory,
# not a module - and `.idea/workspace.xml` or a root `README.md` belong to no module at all. A gate
# command naming a non-module fails, which teaches the reader to ignore this hook.
MODULES="$(printf '%s\n' "$CHANGED" | python3 -c '
import json, os, sys
root = sys.argv[1]
try:
    mods = json.load(open(os.path.join(root, "project-index.json")))["modules"]
except Exception:
    mods = []
# Longest path first, so sources/test-support-security wins over sources/test-support.
paths = sorted(((m.get("path") or m["name"]), m["name"]) for m in mods)
paths.sort(key=lambda t: len(t[0]), reverse=True)
out = []
for line in sys.stdin:
    f = line.strip()
    if not f:
        continue
    for mp, name in paths:
        if f == mp or f.startswith(mp + "/"):
            if name not in out:
                out.append(name)
            break
print(" ".join(sorted(out)))' "$ROOT")"

# Nothing a gate could be run on - only root files or IDE noise changed.
[ -n "${MODULES// /}" ] || exit 0
python3 -c '
import json, sys
print(json.dumps({"systemMessage":
  "Tracked files changed and no verification ran in this session.\n"
  "The gate for what you touched:\n"
  "    scripts/gate.sh " + sys.argv[1].strip() + "\n"
  "(`--list` prints the commands without running them. `mvn test` alone runs no integration test.)"}))' "$MODULES"
