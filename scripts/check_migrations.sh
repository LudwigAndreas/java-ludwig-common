#!/usr/bin/env bash
#
# Every Liquibase changeset in this repository is formatted SQL, and the layout is uniform.
#
#   scripts/check_migrations.sh              check the tracked tree
#   scripts/check_migrations.sh --list       print every root and changeset found, conforming or not
#   scripts/check_migrations.sh --self-test  check the checker against its own fixtures
#
# WHY THIS EXISTS
#
# The database-migration capability says a changeset is formatted SQL in a file named
# NNNN-<slug>.sql whose changeset id is <prefix>-NNNN-<slug> with author ludwig-<prefix>, and that
# the XML root changelog holds includes and comments only. Before that rule, 13 modules shipped 84
# changesets and 46 of them already wrapped a raw <sql> block, so the repository was half SQL and
# half XML with nothing saying which to use - and the inconsistencies that accumulated were exactly
# the ones nobody greps for: two changesets numbered 003 and two numbered 004 in
# crud-service-example, an id numbered 004b in reconciliation, one file in notification-service
# using a different author from its five siblings, prefixes that disagreed with their own authors,
# and the roots split 14 files on dbchangelog-4.20.xsd against 12 on 4.27.
#
# It is a script rather than a rule in one of the three analysis tools because a changelog is a
# resource file: ArchUnit reads bytecode and cannot see it, Checkstyle reads Java source text and
# is not pointed at it, SonarQube is looking for bugs in code, and the enforcer reads the effective
# POM model. The enforcement-triad capability names gate scripts as the owner for exactly this
# class of fact, alongside scripts/manifest.sh layout and scripts/check_image_pins.sh.
# scripts/gate.sh runs this, so every change passes through it.
#
# WHY `git ls-files` AND NOT `find`
#
# Every one of these files is copied into target/classes/db/changelog/ by process-resources, so a
# find-based sweep would report each violation twice and would report build output as a source
# problem. Tracked files are the ones a change can actually fix.
#
# EXIT CODES:
#   0  the tree conforms (or --list / a passing --self-test)
#   1  at least one violation
#   3  missing prerequisite

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT" || exit 3

command -v python3 >/dev/null 2>&1 || { printf 'check_migrations.sh: python3 is not on PATH\n' >&2; exit 3; }
command -v git >/dev/null 2>&1 || { printf 'check_migrations.sh: git is not on PATH\n' >&2; exit 3; }

case "${1:-}" in
  --list|"") ;;
  --self-test) exec python3 scripts/check_migrations.py --self-test ;;
  -h|--help) sed -n '3,7p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
  *) printf 'check_migrations.sh: unknown option %s\n' "$1" >&2; exit 3 ;;
esac

# Both extensions, because the rule is as much about which files are XML as about what the SQL
# says: an XML file that still holds a changeset is the violation this check exists to catch.
#
# scripts/testdata/ is excluded because the --self-test fixtures deliberately violate every rule;
# without the exclusion this check would report its own test data as a problem with the repository.
git ls-files -z -- '*/db/changelog/*.xml' '*/db/changelog/*.sql' ':(exclude)scripts/testdata/*' \
  | python3 scripts/check_migrations.py "${1:-}"
