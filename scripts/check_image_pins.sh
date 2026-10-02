#!/usr/bin/env bash
#
# Every container image reference in this repository carries a sha256 digest.
#
#   scripts/check_image_pins.sh            check the tracked tree
#   scripts/check_image_pins.sh --list     print every reference found, pinned or not
#
# WHY THIS EXISTS
#
# The container-image-pinning capability says every image reference carries name, version AND
# digest, everywhere - "including in READMEs and docker run examples". Until now exactly one place
# was checked: sources/test-support/src/main/resources/images.properties, by ImagePinningTest. The
# Dockerfiles, the Kubernetes manifest, the Jenkinsfile and every README were on the honour system,
# and this change adds a CI tool image to the Jenkinsfile, which would have widened that hole.
#
# It is a script rather than a rule in one of the three analysis tools because an image reference
# is not a Java fact and not a POM fact: ArchUnit and SonarQube read bytecode and source,
# Checkstyle reads Java and properties text, and none of them is looking at a Jenkinsfile, a
# Markdown file or a YAML manifest. The enforcer reads the effective model, which does not contain
# any of those either. scripts/gate.sh runs this, so every change passes through it.
#
# WHAT COUNTS AS A REFERENCE
#
# A line is examined when it carries one of the triggers below, which is what keeps SQL out of the
# results - `FROM outbox_message t` in a README is prose about a query, not an image:
#
#   image:        a YAML image key (Kubernetes, compose)
#   FROM          a Dockerfile directive, and only in a file named Dockerfile*
#   docker run / docker pull / docker build --pull
#
# On such a line, a token shaped like `[registry/]name:tag` must be followed by `@sha256:` and 64
# hex characters. A reference given as a digest with NO tag also passes: the digest is the
# immutable identity and the tag is the convenience, so digest-only is stricter, not weaker.
# test-support's Kafka pin is deliberately in that form - Testcontainers' KRaft KafkaContainer
# rejects the combined tag-and-digest string - and this script must not punish it.
#
# EXIT CODES:
#   0  every reference found is pinned
#   1  at least one is not
#   3  missing prerequisite

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT" || exit 3

command -v python3 >/dev/null 2>&1 || { printf 'check_image_pins.sh: python3 is not on PATH\n' >&2; exit 3; }
command -v git >/dev/null 2>&1 || { printf 'check_image_pins.sh: git is not on PATH\n' >&2; exit 3; }

case "${1:-}" in
  --list|"") ;;
  -h|--help) sed -n '3,6p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
  *) printf 'check_image_pins.sh: unknown option %s\n' "$1" >&2; exit 3 ;;
esac

git ls-files -z -- \
  '*.md' '*.yml' '*.yaml' '*.properties' 'Jenkinsfile' '**/Dockerfile' '**/Dockerfile.*' \
  | python3 scripts/check_image_pins.py "${1:-}"
