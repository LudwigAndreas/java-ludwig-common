#!/usr/bin/env bash
#
# The verification gate, as one command, so that "run the gate" cannot mean four different things.
#
#   scripts/gate.sh <module> [<module> ...]        gate for those modules
#   scripts/gate.sh --change <name> <module> ...   also validate that OpenSpec change
#   scripts/gate.sh --full                         the whole reactor (a BOM/parent change)
#   scripts/gate.sh --list <module>                print the commands without running them
#
# The dependents are read from project-index.json, not passed in, because the whole point of this
# script is that nobody has to remember them. `-am` builds a module's DEPENDENCIES; the dependents
# are the direction that catches a breaking change, and they are what gets skipped by hand.
#
# Modules are named to `mvn` as `:artifactId`, never as a directory. Module directories moved once
# already (build/, services/, sources/) and every path-based command in the repository broke; an
# artifactId is a published coordinate and does not move.
#
# EXIT CODES:
#   0  every command passed
#   1  a command failed (the first failure's output is on stdout and the command is named again at
#      the end, so a long log still tells you what to fix)
#   2  usage error
#   3  missing prerequisite
#
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT" || exit 3

die() { printf 'gate.sh: %s\n' "$*" >&2; exit "${2:-2}"; }

CHANGE=""
FULL=0
LIST=0
MODULES=()

while [ $# -gt 0 ]; do
  case "$1" in
    --change) shift; CHANGE="${1:-}"; [ -n "$CHANGE" ] || die "--change needs a name"; shift ;;
    --full)   FULL=1; shift ;;
    --list)   LIST=1; shift ;;
    -h|--help) sed -n '3,20p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
    -*)       die "unknown option $1" ;;
    *)        MODULES+=("$1"); shift ;;
  esac
done

[ "$FULL" = 1 ] || [ "${#MODULES[@]}" -gt 0 ] || die "name at least one module, or pass --full"

command -v mvn >/dev/null 2>&1 || die "mvn is not on PATH" 3

# ----------------------------------------------------------------------------------------------
# Assemble the command list.
# ----------------------------------------------------------------------------------------------

# The manifest is the source for dependents, so a stale manifest means a gate that misses one.
# Rebuilding is idempotent and takes under a second, so it is done rather than warned about.
if ! ./scripts/manifest.sh stale >/dev/null 2>&1; then
  printf 'gate.sh: manifest is stale; regenerating\n' >&2
  ./scripts/manifest.sh build >&2 || die "could not regenerate the manifest" 3
fi

# A module in the wrong directory for its role is a defect the compiler cannot see, so it is
# checked here where every change passes. Cheap: it reads the manifest that was just built.
./scripts/manifest.sh layout >/dev/null || {
  ./scripts/manifest.sh layout >&2
  die "repository layout check failed" 1
}

# Two file-only checks, before Maven, because they cost milliseconds and catch a class of defect
# the build cannot see:
#
#   check_image_pins     a container image referenced by tag alone. Not a Java fact and not a POM
#                        fact, so neither the three analysis tools nor the enforcer can see it.
#   check_api_baseline   an API baseline that is UNRESOLVABLE rather than absent. revapi treats
#                        both as the same warning; the distinction is the difference between "no
#                        comparison was possible" and "the comparison silently did nothing".
COMMANDS=("scripts/check_image_pins.sh" "scripts/check_api_baseline.sh" "mvn -q validate")

if [ "$FULL" = 1 ]; then
  COMMANDS+=("mvn clean install")
else
  # Expand each named module to itself plus its in-repo dependents, de-duplicated and in a stable
  # order: the named modules first, then dependents, so the most likely failure is reported first.
  # Command substitution, not process substitution: `mapfile < <(...)` reports mapfile's own exit
  # status, so an unknown module name would be diagnosed and then ignored.
  EXPANDED_RAW="$(python3 - "$ROOT" "${MODULES[@]}" <<'PY'
import json, os, sys
root, names = sys.argv[1], sys.argv[2:]
with open(os.path.join(root, "project-index.json"), encoding="utf-8") as h:
    data = json.load(h)
by_name = {m["name"]: m for m in data["modules"]}
unknown = [n for n in names if n not in by_name]
if unknown:
    sys.stderr.write("gate.sh: not a module in project-index.json: %s\n" % ", ".join(unknown))
    sys.exit(2)
# A bom or parent change has no narrower gate than the whole reactor.
if any(by_name[n]["role"] in ("bom", "parent") for n in names):
    print("__FULL__")
    sys.exit(0)
# Emit artifactIds, since that is what `mvn -pl :<id>` takes. A module's name and artifactId
# are equal throughout this repository, but they are different concepts and only one of them is
# what Maven selects on.
ordered = [by_name[n]["artifactId"] for n in names]
for n in names:
    for d in by_name[n]["inRepoDependents"]:
        if d["artifactId"] not in ordered:
            ordered.append(d["artifactId"])
for n in ordered:
    print(n)
PY
)" || exit $?
  mapfile -t EXPANDED <<<"$EXPANDED_RAW"

  if [ "${EXPANDED[0]:-}" = "__FULL__" ]; then
    printf 'gate.sh: a bom or parent module was named; every module inherits or imports it, so the\n'
    printf '         gate is the whole reactor.\n'
    COMMANDS+=("mvn clean install")
  else
    for m in "${EXPANDED[@]}"; do COMMANDS+=("mvn -pl :$m -am verify"); done
  fi
fi

# After a full reactor build, and only then, the aggregate coverage report exists - it is written
# by the last module. Check its CONTENT, not just that the build was green: an aggregator whose
# dependency list had been emptied, or one that ran before the modules it measures, both produce a
# green build and a report that means nothing. A narrow gate skips this because the report is not
# regenerated by one.
for c in "${COMMANDS[@]}"; do
  if [ "$c" = "mvn clean install" ]; then
    COMMANDS+=("python3 scripts/check_aggregate_report.py")
    break
  fi
done

[ -n "$CHANGE" ] && COMMANDS+=("openspec validate $CHANGE")

# ----------------------------------------------------------------------------------------------
# Run, or just print.
# ----------------------------------------------------------------------------------------------

if [ "$LIST" = 1 ]; then
  printf '%s\n' "${COMMANDS[@]}"
  exit 0
fi

printf '=== gate: %d commands ===\n' "${#COMMANDS[@]}"
printf '  %s\n' "${COMMANDS[@]}"
printf '\n'

FAILED=""
for cmd in "${COMMANDS[@]}"; do
  printf '=== %s\n' "$cmd"
  # shellcheck disable=SC2086
  if ! eval "$cmd"; then
    FAILED="$cmd"
    break
  fi
done

printf '\n'
if [ -n "$FAILED" ]; then
  printf '=== GATE FAILED at: %s\n' "$FAILED"
  printf 'Do not narrow the change to something that passes. Fix it, or report the failure.\n'
  exit 1
fi
printf '=== GATE PASSED (%d commands)\n' "${#COMMANDS[@]}"
