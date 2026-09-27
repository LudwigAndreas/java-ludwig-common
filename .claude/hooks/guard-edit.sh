#!/usr/bin/env bash
#
# PreToolUse on Edit|Write: refuse an edit to something that is not a source file.
#
# The flat path rules (target/, generated-sources/, .flattened-pom.xml, the OpenSpec archive) are
# ALSO expressed as `deny` permission rules in settings.json, which is the declarative and more
# reliable statement. This hook exists for the one rule permissions cannot express, because it is
# conditional rather than positional:
#
#   .mvn/maven.config carries -Drevision, the single source of the project version. Editing it is
#   correct during a release and wrong at every other time. So the hook allows it only when the
#   session has actually been talking about a release, which it reads from the transcript.
#
# The flat rules are repeated here anyway, because a hook that only fires on one exotic case is a
# hook nobody believes is running.
set -uo pipefail

INPUT="$(cat)"

decide() {  # decide <allow|deny> <reason>
  python3 -c '
import json, sys
print(json.dumps({"hookSpecificOutput": {"hookEventName": "PreToolUse",
                                          "permissionDecision": sys.argv[1],
                                          "permissionDecisionReason": sys.argv[2]}}))' "$1" "$2"
  exit 0
}

FILE="$(printf '%s' "$INPUT" | python3 -c '
import json,sys
try:
    d = json.load(sys.stdin)
except Exception:
    print(""); raise SystemExit
ti = d.get("tool_input") or {}
print(ti.get("file_path") or ti.get("path") or "")')"

[ -n "$FILE" ] || decide allow "no file path in the tool input; nothing to check"

case "$FILE" in
  */target/*|target/*)
    decide deny "$FILE is under target/ - build output. QueryDSL Q-types and MapStruct implementations are generated there; edit the source that generates them." ;;
  */generated-sources/*|generated-sources/*)
    decide deny "$FILE is under generated-sources/ - generated code is an output, not a source file." ;;
  *.flattened-pom.xml)
    decide deny "$FILE is written by flatten-maven-plugin and removed by \`mvn clean\`. Edit the module's pom.xml instead." ;;
  */openspec/changes/archive/*|openspec/changes/archive/*)
    decide deny "$FILE is in the OpenSpec archive - the historical record of completed changes. Create a new change instead." ;;
  */.mvn/maven.config|.mvn/maven.config)
    TRANSCRIPT="$(printf '%s' "$INPUT" | python3 -c '
import json,sys
try: print((json.load(sys.stdin).get("transcript_path") or ""))
except Exception: print("")')"
    if [ -n "$TRANSCRIPT" ] && [ -f "$TRANSCRIPT" ] \
       && grep -qiE 'releas(e|ing)|-Prelease|bump the version' "$TRANSCRIPT" 2>/dev/null; then
      decide allow ".mvn/maven.config edit allowed: this session is about a release."
    fi
    decide deny ".mvn/maven.config is the single source of the project version (-Drevision). Editing it is a release action, and nothing in this session mentions a release. If this IS a release, say so explicitly first." ;;
esac

decide allow "not a protected path"
