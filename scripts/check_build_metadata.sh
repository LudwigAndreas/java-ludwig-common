#!/usr/bin/env bash
#
# The service parent still writes build provenance into every service artifact.
#
#   scripts/check_build_metadata.sh              check build/ludwig-service-parent/pom.xml
#   scripts/check_build_metadata.sh --self-test  check the checker against its own fixtures
#
# WHY THIS EXISTS
#
# The build-identity capability says every service artifact carries git.properties and
# META-INF/build-info.properties, and that the build fails when the configuration stops producing
# them. It says so because the failure has already happened here: ludwig-service-parent carried a
# comment stating that it "drops a git.properties into the jar", next to a plugin block that set
# nothing of the kind - the file existed only because spring-boot-starter-parent happened to
# switch it on - and ServiceIdentityResolver fell back to a build.version property "present
# whenever the build-info goal ran", a goal that was bound nowhere. Both read as true and neither
# was checked.
#
# This checks the CONFIGURATION. BuildProvenanceIntegrationTest in crud-service-example checks the
# OUTCOME - that the two resources are really on a packaged service's classpath. Both are needed:
# this one costs milliseconds and names the setting that went missing, that one cannot be fooled
# by configuration that looks right and produces nothing.
#
# It is a script rather than a rule in one of the three analysis tools because whether a plugin
# goal is bound is a fact about POM text: ArchUnit reads bytecode and cannot see it, Checkstyle
# reads Java source and is not pointed at it, and the enforcer has no rule about another plugin's
# configuration. The enforcement-triad capability names gate scripts as the owner for this class
# of fact, alongside scripts/check_migrations.sh and scripts/check_image_pins.sh.
# scripts/gate.sh runs this, so every change passes through it.
#
# EXIT CODES:
#   0  both resources are configured (or a passing --self-test)
#   1  at least one violation
#   3  missing prerequisite

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT" || exit 3

command -v python3 >/dev/null 2>&1 || { printf 'check_build_metadata.sh: python3 is not on PATH\n' >&2; exit 3; }

case "${1:-}" in
  "") ;;
  --self-test) exec python3 scripts/check_build_metadata.py --self-test ;;
  -h|--help) sed -n '3,6p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
  *) printf 'check_build_metadata.sh: unknown option %s\n' "$1" >&2; exit 3 ;;
esac

python3 scripts/check_build_metadata.py
