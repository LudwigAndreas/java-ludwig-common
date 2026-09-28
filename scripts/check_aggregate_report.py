#!/usr/bin/env python3
"""Assert the aggregate coverage report actually covers the platform.

A green build proves the report was GENERATED, not that it measured anything. An aggregator whose
dependency list had been emptied would build green and report nothing, and so would one built
before the modules it measures. This checks the report's content instead:

  1. every jar module in project-index.json appears as a group in the report;
  2. every group that HAS Java sources carries a non-zero INSTRUCTION counter, so a module that is
     present but uninstrumented is caught as well as one that is absent.

The distinction in (2) matters and was found the hard way: `checkstyle-rules` is a resources-only
jar - it ships checkstyle.xml and has no .java at all - so zero instructions there is correct, not
a missing measurement. Treating "no code to measure" and "code that was not measured" as the same
thing makes the check cry wolf on the first run, which is how a check gets deleted.

Exit codes: 0 all good, 1 the report disagrees with the manifest, 3 the report is missing.

Run after `mvn clean install` / `mvn verify`:

    python3 scripts/check_aggregate_report.py
"""
import json
import os
import sys
import xml.etree.ElementTree as ET

REPORT = os.path.join("build", "jacoco-aggregate", "target", "site",
                      "jacoco-aggregate", "jacoco.xml")


def main():
    root_dir = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    os.chdir(root_dir)

    if not os.path.isfile(REPORT):
        sys.stderr.write(
            "aggregate report not found at %s\n"
            "Run `mvn clean install` (or `mvn verify`) over the full reactor first: the report is\n"
            "written by the last module in the reactor, so a partial build does not produce it.\n"
            % REPORT)
        return 3

    manifest = json.load(open("project-index.json", encoding="utf-8"))
    jar_modules = [m for m in manifest["modules"] if m["packaging"] != "pom"]
    expected = {m["artifactId"] for m in jar_modules}

    # Modules with no Java at all. checkstyle-rules is one: a resources-only jar shipping
    # checkstyle.xml. Zero instructions there is the correct answer, so it must not be reported
    # as an uninstrumented module.
    def has_java(module):
        src = os.path.join(module["path"], "src", "main", "java")
        for _dirpath, _dirnames, filenames in os.walk(src):
            if any(f.endswith(".java") for f in filenames):
                return True
        return False

    codeless = {m["artifactId"] for m in jar_modules if not has_java(m)}

    tree = ET.parse(REPORT)
    report = tree.getroot()

    # jacoco's aggregate report emits one <group> per measured project.
    groups = {}
    for g in report.findall("group"):
        counters = {c.get("type"): (int(c.get("missed", 0)), int(c.get("covered", 0)))
                    for c in g.findall("counter")}
        groups[g.get("name")] = counters

    problems = []

    missing = sorted(expected - set(groups))
    for name in missing:
        problems.append("MISSING from the report entirely: %s" % name)

    unexpected = sorted(set(groups) - expected)
    for name in unexpected:
        problems.append("in the report but not a jar module in the manifest: %s" % name)

    for name in sorted(set(groups) & expected):
        missed, covered = groups[name].get("INSTRUCTION", (0, 0))
        if missed + covered == 0 and name not in codeless:
            problems.append(
                "has Java sources but NO instructions counted - not instrumented: %s" % name)
        if missed + covered > 0 and name in codeless:
            problems.append(
                "has no .java but reports instructions - the codeless check is wrong: %s" % name)

    total = {c.get("type"): (int(c.get("missed", 0)), int(c.get("covered", 0)))
             for c in report.findall("counter")}
    inst_missed, inst_covered = total.get("INSTRUCTION", (0, 0))
    branch_missed, branch_covered = total.get("BRANCH", (0, 0))

    print("aggregate report: %s" % REPORT)
    print("modules expected (jar packaging): %d" % len(expected))
    print("modules present in report       : %d" % len(groups))
    if inst_missed + inst_covered:
        print("instruction coverage            : %.1f%%  (%d covered / %d total)"
              % (100.0 * inst_covered / (inst_missed + inst_covered),
                 inst_covered, inst_missed + inst_covered))
    if branch_missed + branch_covered:
        print("branch coverage                 : %.1f%%  (%d covered / %d total)"
              % (100.0 * branch_covered / (branch_missed + branch_covered),
                 branch_covered, branch_missed + branch_covered))
    print()

    width = max(len(n) for n in groups) if groups else 10
    for name in sorted(groups):
        m, c = groups[name].get("INSTRUCTION", (0, 0))
        pct = ("%5.1f%%" % (100.0 * c / (m + c))) if (m + c) else "   n/a"
        note = "  (no Java - resources only)" if name in codeless else ""
        print("  %-*s  %s  %7d instructions%s" % (width, name, pct, m + c, note))

    if problems:
        sys.stderr.write("\n%d problem(s):\n" % len(problems))
        for p in problems:
            sys.stderr.write("  - %s\n" % p)
        sys.stderr.write(
            "\nA module missing from the report is a module missing from the platform's coverage\n"
            "number, and the build stays green either way. Add it to\n"
            "build/jacoco-aggregate/pom.xml <dependencies>.\n")
        return 1

    print("\nOK: every jar module is in the report and every one has instructions counted.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
