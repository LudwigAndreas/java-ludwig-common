#!/usr/bin/env bash
#
# SessionStart: hand the agent the two things it cannot discover by itself in time to matter -
# that the code index needs bootstrapping, and whether the module manifest has drifted.
#
# This hook CANNOT perform the bootstrap itself. A command hook runs a shell command, and
# `set_project_path` / `build_deep_index` are MCP tool calls that only the model can make. So the
# hook does the one thing it can: it puts the instruction in front of the model before its first
# query, which is the point at which forgetting is expensive.
#
# Fast by design. A slow SessionStart hook is a hook somebody switches off.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

STALE_NOTE=""
if ! "$ROOT/scripts/manifest.sh" stale >/dev/null 2>&1; then
  STALE_NOTE=$'\n\nWARNING: project-index.json is STALE - a POM has changed since it was generated. Run `scripts/manifest.sh build` before trusting any module role, dependency list or gate command from it.'
fi

CONTEXT="Bootstrap before your first code query, in this order:
  1. code-index MCP: set_project_path  ->  $ROOT
  2. code-index MCP: build_deep_index
Both, unconditionally. ~15s. Without step 2 the symbol tools fail with an error that reads like
the file does not exist. Then query through the index; PROJECT_INDEX.md and
\`scripts/manifest.sh module <path>\` answer the Maven questions it cannot. \`rg\` is the last rung
and needs a stated reason. See docs/code-index.md.${STALE_NOTE}"

python3 -c '
import json, sys
print(json.dumps({"hookSpecificOutput": {"hookEventName": "SessionStart",
                                          "additionalContext": sys.argv[1]}}))' "$CONTEXT"
