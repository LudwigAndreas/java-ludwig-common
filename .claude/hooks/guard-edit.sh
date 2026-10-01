#!/usr/bin/env bash
#
# PreToolUse on Edit|Write: refuse an edit to something that is not a source file.
#
# Every rule here is ALSO a `deny` permission rule in settings.json, which is the declarative and
# more reliable statement. The hook repeats them so that a refusal arrives with a reason a reader
# can act on rather than as a bare "not permitted".
#
# .mvn/maven.config USED TO BE CONDITIONAL - allowed when the session transcript mentioned a
# release, denied otherwise - and it is now an unconditional deny. The reason the condition
# existed has gone: the version is computed by GitVersion from the git history and passed in as
# -Drevision, and a release is a tag. That file now holds nothing but the tag-less local fallback,
# which must stay a SNAPSHOT (an enforcer rule in the reactor root fails the build if it does
# not), so there is no longer any task for which editing it is the right move. A conditional deny
# that can be unlocked by saying the word "release" is also the weakest rule in this harness: the
# transcript is input, and input is not authorisation.
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
    decide deny ".mvn/maven.config is not edited, in any session, for any reason - including a release. A release is a tag: GitVersion computes the version from the git history and the pipeline passes it in as -Drevision, which beats this file. What is left here is the fallback for a checkout with no tags, it must stay a -SNAPSHOT, and an enforcer rule in the reactor root fails the build if it stops being one. If you are trying to publish a version, tag the commit." ;;
esac

decide allow "not a protected path"
