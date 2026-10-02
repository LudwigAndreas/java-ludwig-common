#!/usr/bin/env bash
#
# Tells an ABSENT API baseline apart from an UNRESOLVABLE one, which revapi cannot.
#
#   scripts/check_api_baseline.sh            classify every published library module
#   scripts/check_api_baseline.sh --list     print what would be checked, and check nothing
#
# WHY THIS EXISTS AT ALL
#
# The api-evolution capability draws a line revapi does not:
#
#   - a module with NO previous release has no baseline, and that is fine. The build passes and
#     says so, naming the module - "a skip that reads as a passing check is the failure mode this
#     wording exists to prevent".
#   - a module WITH a previous release that cannot be fetched - the repository is unreachable, or
#     the local repository has no copy and the build is offline - is a FAILURE. Otherwise a
#     network blip silently downgrades the compatibility gate to nothing, on exactly the build
#     that most needs it.
#
# revapi-maven-plugin 0.15.1 cannot make that distinction. Both arrive at the same place - a
# `Failed to resolve old artifacts` warning - and the only switch, failOnUnresolvedArtifacts,
# fails on both or on neither. Verified against the plugin's own descriptor and against a real
# run: with nothing published, every module logs
#
#   [WARNING] Failed to resolve old artifacts: Failed to find a version of artifact
#   'ru.ludwigandreas:jira-client:jar:RELEASE' ... The versions found were: [].
#
# and there is no configuration that says "an empty list is fine, a failed connection is not".
#
# HOW THE DISTINCTION IS MADE WITHOUT ASKING THE NETWORK TO BE HONEST
#
# Git tags are this repository's record of what has been released - a release IS a tag, per the
# release-versioning capability. So:
#
#   no v* tag at all        -> nothing has ever been released, every baseline is legitimately
#                              absent, and there is nothing that could be unresolvable. Pass.
#   a v<X.Y.Z> tag exists   -> for each library module that already existed at that tag, the
#                              artifact <groupId>:<artifactId>:<X.Y.Z> MUST be resolvable. If it
#                              is neither in the local repository nor fetchable from the releases
#                              repository, that is the unresolvable case and this fails.
#
# A module added after the newest tag is absent-by-construction and is reported as such.
#
# The escape hatch for a local loop is `-Drevapi.skip=true` on the COMMAND LINE, and it is
# deliberately not implemented here, in any POM, or in the Jenkinsfile: a hatch that lives in a
# file is a hatch that is left open.
#
# EXIT CODES:
#   0  every module is either compared or legitimately uncomparable, and said which
#   1  a baseline exists and could not be resolved
#   3  missing prerequisite

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT" || exit 3

die() { printf 'check_api_baseline.sh: %s\n' "$*" >&2; exit "${2:-3}"; }

LIST=0
case "${1:-}" in
  --list) LIST=1 ;;
  -h|--help) sed -n '3,6p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
  "") ;;
  *) die "unknown option $1" ;;
esac

command -v git >/dev/null 2>&1 || die "git is not on PATH"
command -v python3 >/dev/null 2>&1 || die "python3 is not on PATH"
[ -f project-index.json ] || die "project-index.json is missing; run scripts/manifest.sh build"

# A jar module parented by the reactor root is exactly the gated set: the revapi binding is
# declared in that root, and the two services are parented by ludwig-service-parent, so they
# never inherit it. Printed before anything else so that --list works with no tags present.
MODULES="$(python3 -c '
import json
with open("project-index.json", encoding="utf-8") as handle:
    data = json.load(handle)
for module in data["modules"]:
    if module.get("packaging") == "jar" and module.get("parentPom") == "common":
        print("%s\t%s" % (module["artifactId"], module["path"]))
')" || die "could not read project-index.json"

if [ "$LIST" = 1 ]; then
  printf '%s\n' "$MODULES"
  exit 0
fi

# The newest release tag, by version order rather than by creation date: a tag pushed late for an
# older branch must not become "the baseline".
BASELINE_TAG="$(git tag -l 'v[0-9]*' --sort=-v:refname | head -n 1)"

if [ -z "$BASELINE_TAG" ]; then
  printf 'check_api_baseline.sh: no v* tag in this repository.\n'
  printf '  Nothing has been released, so every baseline is ABSENT and no comparison is\n'
  printf '  possible. Read that as the absence of a compatibility check rather than as a\n'
  printf '  passing one; it ends the moment the first release is tagged.\n'
  exit 0
fi

BASELINE_VERSION="${BASELINE_TAG#v}"

# Both read from the root POM rather than hard-coded, so a rename or a repository move does not
# leave this script quietly checking coordinates nobody publishes to any more.
GROUP_ID="$(sed -n 's:.*<groupId>\(.*\)</groupId>.*:\1:p' pom.xml | head -n 1)"
[ -n "$GROUP_ID" ] || die "could not read <groupId> from the root pom.xml"
RELEASES_URL="${LUDWIG_REPO_RELEASES_URL:-$(sed -n 's:.*<ludwig.repo.releases.url>\(.*\)</ludwig.repo.releases.url>.*:\1:p' pom.xml | head -n 1)}"
[ -n "$RELEASES_URL" ] || die "could not read <ludwig.repo.releases.url> from the root pom.xml"

printf 'check_api_baseline.sh: baseline is %s:<module>:%s (from tag %s)\n' \
  "$GROUP_ID" "$BASELINE_VERSION" "$BASELINE_TAG"

LOCAL_REPO="${HOME}/.m2/repository"
GROUP_PATH="${GROUP_ID//.//}"
ABSENT=0
COMPARED=0
UNRESOLVABLE=""

while IFS=$'\t' read -r ARTIFACT MODULE_PATH; do
  [ -n "$ARTIFACT" ] || continue

  # Did the module exist at the baseline tag? If not, there is nothing it could be compared
  # against and no amount of network access would produce one.
  if ! git cat-file -e "${BASELINE_TAG}:${MODULE_PATH}/pom.xml" 2>/dev/null; then
    printf '  ABSENT      %-45s added after %s; this release establishes its baseline, no comparison performed\n' \
      "$ARTIFACT" "$BASELINE_TAG"
    ABSENT=$((ABSENT + 1))
    continue
  fi

  JAR="${LOCAL_REPO}/${GROUP_PATH}/${ARTIFACT}/${BASELINE_VERSION}/${ARTIFACT}-${BASELINE_VERSION}.jar"
  if [ -f "$JAR" ]; then
    printf '  COMPARED    %-45s baseline %s resolved from the local repository\n' "$ARTIFACT" "$BASELINE_VERSION"
    COMPARED=$((COMPARED + 1))
    continue
  fi

  # Not cached. It has to come over the network, and the repository has to answer.
  URL="${RELEASES_URL%/}/${GROUP_PATH}/${ARTIFACT}/${BASELINE_VERSION}/${ARTIFACT}-${BASELINE_VERSION}.jar"
  if command -v curl >/dev/null 2>&1 && curl -sfI --max-time 20 "$URL" >/dev/null 2>&1; then
    printf '  COMPARED    %-45s baseline %s is published and reachable\n' "$ARTIFACT" "$BASELINE_VERSION"
    COMPARED=$((COMPARED + 1))
  else
    printf '  UNRESOLVED  %-45s %s exists (the module is in %s) but could not be fetched from %s\n' \
      "$ARTIFACT" "$BASELINE_VERSION" "$BASELINE_TAG" "$URL"
    UNRESOLVABLE="${UNRESOLVABLE}${UNRESOLVABLE:+, }${ARTIFACT}"
  fi
done <<<"$MODULES"

printf '\n%d compared, %d absent by construction\n' "$COMPARED" "$ABSENT"

if [ -n "$UNRESOLVABLE" ]; then
  printf '\ncheck_api_baseline.sh: FAILED. These modules have a published baseline that could not be\n'
  printf '  resolved: %s\n' "$UNRESOLVABLE"
  printf '  An unresolvable baseline is not an absent one. revapi has just compared each of them\n'
  printf '  against an empty archive and reported nothing, which looks exactly like a passing\n'
  printf '  compatibility check and is not one. Restore access to the releases repository, or\n'
  printf '  populate the local repository, and run the gate again.\n'
  exit 1
fi
exit 0
