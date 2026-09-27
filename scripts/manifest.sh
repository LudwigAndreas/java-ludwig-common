#!/usr/bin/env bash
#
# The module manifest: the one thing the code index cannot answer.
#
# Navigation in this repository goes through the `code-index` MCP server (see docs/code-index.md).
# That index knows files and symbols; it knows nothing about Maven - not module roles, not POM
# tier, not in-repo dependency direction, not which module owns a path. This script fills exactly
# that gap and nothing else. It deliberately does NOT build a second symbol index: a second index
# is a second thing to keep fresh and a second answer to the same question.
#
#   scripts/manifest.sh build          regenerate project-index.json + PROJECT_INDEX.md; idempotent
#   scripts/manifest.sh stale          non-zero if the manifest predates the current POMs
#   scripts/manifest.sh module <path>  which module owns a path, its dependencies both ways,
#                                      and the verification gate for changing it
#
# EXIT CODES - a contract, relied on by the SessionStart hook and by CI:
#   0  success, with results
#   1  ran correctly, no results  /  `stale`: the manifest IS stale
#   2  usage error (unknown subcommand, missing argument)
#   3  missing prerequisite (no python3)
#
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GENERATOR="$ROOT/scripts/project_index.py"

die() { printf 'manifest.sh: %s\n' "$*" >&2; exit "${2:-2}"; }

require_python() {
  command -v python3 >/dev/null 2>&1 || die "python3 is not on PATH" 3
  [ -f "$GENERATOR" ] || die "missing generator: scripts/project_index.py" 3
}

cmd_build() {
  require_python
  python3 "$GENERATOR" || die "manifest generation failed" 1
}

cmd_stale() {
  require_python
  python3 "$GENERATOR" --check
}

cmd_module() {
  local target="${1:-}"
  [ -n "$target" ] || die "usage: manifest.sh module <path>"
  require_python
  [ -f "$ROOT/project-index.json" ] || die "no manifest - run: scripts/manifest.sh build" 3

  python3 - "$ROOT" "$target" <<'PY'
import json, os, sys

root, target = sys.argv[1], sys.argv[2]
with open(os.path.join(root, "project-index.json"), encoding="utf-8") as handle:
    data = json.load(handle)

# Match on the longest module name that prefixes the path, so a path inside
# test-support-security is not attributed to test-support.
rel = os.path.relpath(os.path.abspath(target), root).replace(os.sep, "/")
owner = None
for module in data["modules"]:
    if rel == module["name"] or rel.startswith(module["name"] + "/"):
        if owner is None or len(module["name"]) > len(owner["name"]):
            owner = module
if owner is None:
    sys.stderr.write("no module owns %s\n" % rel)
    sys.exit(1)

m = owner
print("module      %s" % m["name"])
print("artifactId  %s" % m["artifactId"])
print("role        %s   packaging %s" % (m["role"], m["packaging"]))
print("parent POM  %s%s" % (m["parentPom"],
                            "   (imports ludwig-bom)" if m["importsLudwigBom"] else ""))
print("package     %s" % (m["packageRoot"] or "-"))
print("README      %s" % (m["readme"] or "-"))
print()
print("depends on (%d) - every scope, because Maven's reactor DAG ignores none of them:"
      % len(m["inRepoDependencies"]))
for d in m["inRepoDependencies"] or [{"artifactId": "(nothing in this repository)", "scope": ""}]:
    print("    %-44s %s" % (d["artifactId"], d["scope"]))
print()
print("depended on by (%d) - the verification gate runs each of these:"
      % len(m["inRepoDependents"]))
for d in m["inRepoDependents"] or [{"artifactId": "(nothing in this repository)", "scope": ""}]:
    print("    %-44s %s" % (d["artifactId"], d["scope"]))
print()
print("gate:")
print("    mvn -q validate")
if m["role"] in ("bom", "parent"):
    print("    mvn clean install        # %s: every module inherits or imports it, there is no"
          % m["name"])
    print("                             # narrower gate")
else:
    print("    mvn -pl %s -am verify" % m["name"])
    for d in m["inRepoDependents"]:
        print("    mvn -pl %s -am verify" % d["artifactId"])
PY
}

usage() { sed -n '3,22p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; }

case "${1:-}" in
  build)  shift; cmd_build "$@" ;;
  stale)  shift; cmd_stale "$@" ;;
  module) shift; cmd_module "$@" ;;
  ""|-h|--help|help) usage ;;
  *)      printf 'manifest.sh: unknown subcommand %s\n\n' "$1" >&2; usage >&2; exit 2 ;;
esac
