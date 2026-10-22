#!/usr/bin/env bash
#
# The services' published API documents (docs/api/), OpenAPI and AsyncAPI, as one command.
#
#   scripts/api-docs.sh refresh                       regenerate every committed document
#   scripts/api-docs.sh check                         fail on a missing or drifted document
#   scripts/api-docs.sh missing                       fail on a missing document only (no build)
#   scripts/api-docs.sh publish --version <v> [--dry-run]
#                                                     check, then upload each document to Nexus as
#                                                     ru.ludwigandreas:<service>:<v>:yaml:<kind>
#
# The services are read from project-index.json (role "service"), so a new service is covered without
# editing this script. A service without one kind of surface is listed in docs/api/README.md with a
# line of the form
#
#   - `<service>`: no asyncapi surface
#
# The repository is the one libraries deploy to: ludwig.repo.{releases,snapshots}.{id,url} from the
# root POM, the snapshots one when <v> ends in -SNAPSHOT. Credentials are your settings.xml.
#
# EXIT CODES:
#   0  success
#   1  a document is missing or drifted, or a Maven command failed
#   2  usage error
#   3  missing prerequisite
#
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT" || exit 3

die() { printf 'api-docs.sh: %s\n' "$*" >&2; exit "${2:-2}"; }

KINDS=(openapi asyncapi)
ITS="ApiDocumentIT,AsyncApiDocumentIT"

services() {
  python3 - <<'PY'
import json
with open("project-index.json", encoding="utf-8") as h:
    data = json.load(h)
for m in data["modules"]:
    if m["role"] == "service":
        print(m["artifactId"])
PY
}

# Every service has every kind of document, unless README.md says it has no such surface.
missing() {
  local failed=0 svc kind
  for svc in $(services); do
    for kind in "${KINDS[@]}"; do
      [ -f "docs/api/$svc.$kind.yaml" ] && continue
      grep -qF -- "- \`$svc\`: no $kind surface" docs/api/README.md && continue
      printf 'api-docs.sh: docs/api/%s.%s.yaml is missing, and docs/api/README.md does not list %s as having no %s surface\n' \
        "$svc" "$kind" "$svc" "$kind" >&2
      failed=1
    done
  done
  return $failed
}

# The document ITs alone. -am, so the services are built against this checkout's modules and not
# against whatever an earlier build left in ~/.m2; the dependencies' own tests are skipped.
run_its() {
  local pl
  pl="$(services | sed 's/^/:/' | paste -sd, -)"
  mvn -q -pl "$pl" -am verify -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false \
    -Dit.test="$ITS" -Dfailsafe.failIfNoSpecifiedTests=false "$@"
}

evaluate() {
  mvn -q -N help:evaluate -Dexpression="$1" -DforceStdout
}

publish() {
  local version="" dry=0
  while [ $# -gt 0 ]; do
    case "$1" in
      --version) shift; version="${1:-}"; shift ;;
      --dry-run) dry=1; shift ;;
      *) die "unknown publish option $1" ;;
    esac
  done
  [ -n "$version" ] || die "publish needs --version <v>"

  local repo=releases
  case "$version" in *-SNAPSHOT) repo=snapshots ;; esac
  local id url
  id="$(evaluate "ludwig.repo.$repo.id")" || die "could not read ludwig.repo.$repo.id from the root POM" 1
  url="$(evaluate "ludwig.repo.$repo.url")" || die "could not read ludwig.repo.$repo.url from the root POM" 1

  if [ "$dry" = 1 ]; then
    printf '# dry run: the check is not run; publish without --dry-run runs it first\n'
  else
    missing || exit 1
    run_its || die "a committed document is stale; run scripts/api-docs.sh refresh and review the diff" 1
  fi

  local svc kind file cmd
  for svc in $(services); do
    for kind in "${KINDS[@]}"; do
      file="docs/api/$svc.$kind.yaml"
      [ -f "$file" ] || continue
      cmd="mvn -q -N deploy:deploy-file -Dfile=$file -DgroupId=ru.ludwigandreas -DartifactId=$svc"
      cmd+=" -Dversion=$version -Dpackaging=yaml -Dclassifier=$kind -DgeneratePom=false"
      cmd+=" -DrepositoryId=$id -Durl=$url"
      if [ "$dry" = 1 ]; then
        printf '%s\n' "$cmd"
      else
        printf '=== %s\n' "$cmd"
        # shellcheck disable=SC2086
        $cmd || die "upload of $file failed" 1
      fi
    done
  done
}

command -v mvn >/dev/null 2>&1 || die "mvn is not on PATH" 3

case "${1:-}" in
  refresh) run_its -Dludwig.apidocs.write=true || exit 1
           printf 'Documents refreshed. Read the diff under docs/api/ before committing it.\n' ;;
  check)   missing || exit 1; run_its || exit 1 ;;
  missing) missing || exit 1 ;;
  publish) shift; publish "$@" ;;
  -h|--help|"") sed -n '3,25p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; [ -n "${1:-}" ] || exit 2 ;;
  *) die "unknown command $1" ;;
esac
