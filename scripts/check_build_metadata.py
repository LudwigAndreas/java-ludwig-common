"""Checker behind scripts/check_build_metadata.sh - see that file for why this check exists.

Reads one POM - build/ludwig-service-parent/pom.xml unless a path is given - and asserts that it
still configures the two plugins that write build provenance into a service artifact:
git-commit-id-maven-plugin for git.properties and spring-boot-maven-plugin's build-info goal for
META-INF/build-info.properties.

Kept as Python rather than folded into the shell script for the same reason check_migrations.py
is: the POM has to be read as XML, not as text. A grep for ``generateGitPropertiesFile`` is
satisfied by the comment explaining the setting, which is the exact failure this check exists to
catch - prose that says the file is generated, beside configuration that does not generate it.

Every rule carries a stable code (``E_*``). ``--self-test`` asserts that the fixtures under
scripts/testdata/check_build_metadata/ make every one of them both fire and not fire, so a rule
cannot rot into one that never triggers.
"""

import os
import re
import sys
import xml.etree.ElementTree as ElementTree

DEFAULT_POM = os.path.join("build", "ludwig-service-parent", "pom.xml")

GIT_PLUGIN = "git-commit-id-maven-plugin"
BOOT_PLUGIN = "spring-boot-maven-plugin"
ANTRUN_PLUGIN = "maven-antrun-plugin"

GIT_RESOURCE = "git.properties"
BUILD_INFO_RESOURCE = "META-INF/build-info.properties"

# The keys the build-identity capability says the git provenance carries. When the POM narrows
# what the plugin emits, the allow-list has to admit every one of these, or the file is generated
# and the field is silently missing from it.
REQUIRED_GIT_KEYS = (
    "git.commit.id",
    "git.commit.id.abbrev",
    "git.branch",
    "git.commit.time",
    "git.dirty",
)

# Where the file has to be written for the jar plugin to package it.
OUTPUT_DIRECTORY = "${project.build.outputDirectory}"


class Finding:
    """One violation: a stable code, the place, and a sentence that says what to do."""

    def __init__(self, code, path, message):
        self.code = code
        self.path = path
        self.message = message

    def __str__(self):
        return "  %-26s %s\n%s%s" % (self.code, self.path, " " * 29, self.message)


def local(tag):
    """An element's name without its XML namespace, so the POM reads the way it is written."""
    return tag.rsplit("}", 1)[-1]


def child(element, name):
    if element is None:
        return None
    for candidate in element:
        if local(candidate.tag) == name:
            return candidate
    return None


def children(element, name):
    if element is None:
        return []
    return [candidate for candidate in element if local(candidate.tag) == name]


def text(element):
    return (element.text or "").strip() if element is not None else ""


def find_plugin(container, artifact_id):
    for plugin in children(child(container, "plugins"), "plugin"):
        if text(child(plugin, "artifactId")) == artifact_id:
            return plugin
    return None


class Plugin:
    """One plugin as this POM configures it: the active declaration, backed by its management.

    The plugin has to be DECLARED under build/plugins to count as present - an entry that exists
    only in pluginManagement configures a plugin nobody runs. Once it is declared, its settings
    are read from the declaration first and from this file's pluginManagement second, which is how
    Maven itself merges the two.

    Deliberately NOT consulted: the parent POM. spring-boot-starter-parent's pluginManagement sets
    generateGitPropertiesFile today, and that is the reason this check insists on seeing it here -
    a setting that lives only in someone else's parent leaves with a Boot upgrade.
    """

    def __init__(self, build, artifact_id):
        self.declared = find_plugin(build, artifact_id)
        managed = find_plugin(child(build, "pluginManagement"), artifact_id)
        self.sources = [p for p in (self.declared, managed) if p is not None]

    def present(self):
        return self.declared is not None

    def setting(self, name):
        """The plugin-level configuration element of that name, or None."""
        for source in self.sources:
            found = child(child(source, "configuration"), name)
            if found is not None:
                return found
        return None

    def executions(self):
        found = []
        for source in self.sources:
            found.extend(children(child(source, "executions"), "execution"))
        return found

    def runs(self, goal):
        for execution in self.executions():
            goals = [text(g) for g in children(child(execution, "goals"), "goal")]
            if goal in goals:
                return True
        return False


def admits(patterns, key):
    for pattern in patterns:
        try:
            if re.search(pattern, key):
                return True
        except re.error:
            # A pattern Java cannot compile either. It admits nothing, which is what is reported.
            continue
    return False


def check_git(build, path, findings):
    plugin = Plugin(build, GIT_PLUGIN) if build is not None else None
    if plugin is None or not plugin.present():
        findings.append(Finding("E_GIT_PROPERTIES_OFF", path,
                                "%s is no longer produced: %s is not declared under build/plugins."
                                % (GIT_RESOURCE, GIT_PLUGIN)))
        return

    if text(plugin.setting("generateGitPropertiesFile")) != "true":
        findings.append(Finding("E_GIT_PROPERTIES_OFF", path,
                                "%s is no longer produced: %s does not set "
                                "<generateGitPropertiesFile>true</generateGitPropertiesFile> in this "
                                "file." % (GIT_RESOURCE, GIT_PLUGIN)))

    filename = text(plugin.setting("generateGitPropertiesFilename"))
    if filename != OUTPUT_DIRECTORY + "/" + GIT_RESOURCE:
        findings.append(Finding("E_GIT_PROPERTIES_PATH", path,
                                "%s is not written where it is packaged: "
                                "<generateGitPropertiesFilename> is '%s', want '%s/%s'."
                                % (GIT_RESOURCE, filename or "(unset)", OUTPUT_DIRECTORY, GIT_RESOURCE)))

    include_only = plugin.setting("includeOnlyProperties")
    if include_only is not None:
        patterns = [text(entry) for entry in include_only if text(entry)]
        for key in REQUIRED_GIT_KEYS:
            if not admits(patterns, key):
                findings.append(Finding("E_GIT_PROPERTY_FILTERED", path,
                                        "%s no longer carries %s: no <includeOnlyProperty> pattern "
                                        "admits it." % (GIT_RESOURCE, key)))

    if text(plugin.setting("failOnNoGitDirectory")) != "false":
        findings.append(Finding("E_NO_GIT_FAILS", path,
                                "a build with no git checkout no longer succeeds: %s does not set "
                                "<failOnNoGitDirectory>false</failOnNoGitDirectory>." % GIT_PLUGIN))


def check_build_info(build, path, findings):
    plugin = Plugin(build, BOOT_PLUGIN) if build is not None else None
    if plugin is None or not plugin.present() or not plugin.runs("build-info"):
        findings.append(Finding("E_BUILD_INFO_UNBOUND", path,
                                "%s is no longer produced: %s has no execution running the "
                                "build-info goal. spring-boot-starter-parent does not bind it."
                                % (BUILD_INFO_RESOURCE, BOOT_PLUGIN)))


def check_prune(build, path, findings):
    """The step that removes a git.properties with no commit in it.

    With no .git directory git-commit-id-maven-plugin 8.0.2 still writes the file, holding a header
    comment and nothing else. The capability requires the resource to be ABSENT there, and the only
    thing that makes it so is this execution - see the comment beside it in the POM.
    """
    plugin = Plugin(build, ANTRUN_PLUGIN) if build is not None else None
    pruned = False
    if plugin is not None and plugin.present():
        for execution in plugin.executions():
            for delete in execution.iter():
                if local(delete.tag) != "delete":
                    continue
                names = [e.get("name", "") for e in delete.iter() if local(e.tag) == "include"]
                if GIT_RESOURCE in names:
                    pruned = True
    if not pruned:
        findings.append(Finding("E_EMPTY_GIT_PROPERTIES_KEPT", path,
                                "a build with no git checkout packages an empty %s: no %s "
                                "execution deletes it." % (GIT_RESOURCE, ANTRUN_PLUGIN)))


def analyse(path):
    findings = []
    try:
        root = ElementTree.parse(path).getroot()
    except (OSError, ElementTree.ParseError) as error:
        return [Finding("E_POM_UNREADABLE", path, "cannot be read as a POM: %s" % error)]

    build = child(root, "build")
    check_git(build, path, findings)
    check_build_info(build, path, findings)
    check_prune(build, path, findings)
    return findings


def report(path, findings):
    if findings:
        print("check_build_metadata.sh: FAILED. %d violation(s) of the build-identity capability:"
              % len(findings))
        print("")
        for finding in sorted(findings, key=lambda f: (f.code, f.message)):
            print(finding)
        print("")
        print("  Every service artifact carries %s and %s, written by" % (GIT_RESOURCE, BUILD_INFO_RESOURCE))
        print("  plugins configured in %s. See openspec/specs/build-identity/spec.md." % DEFAULT_POM)
        return 1

    print("check_build_metadata.sh: %s configures %s and %s." % (path, GIT_RESOURCE, BUILD_INFO_RESOURCE))
    return 0


def self_test():
    """Assert the fixtures make every rule both fire and not fire.

    A rule that cannot be shown to fire is a rule that may already have stopped working - and this
    check exists because a statement about the build went unchecked for as long as it was only a
    comment.
    """
    root = os.path.join("scripts", "testdata", "check_build_metadata")
    results = {}
    for case in ("good", "bad"):
        base = os.path.join(root, case)
        findings = []
        for name in sorted(os.listdir(base)):
            findings.extend(analyse(os.path.join(base, name)))
        results[case] = findings

    expected = sorted(ALL_CODES)
    fired = sorted({f.code for f in results["bad"]})
    missing = [code for code in expected if code not in fired]
    unexpected = sorted({f.code for f in results["good"]})

    status = 0
    print("check_build_metadata.sh --self-test")
    print("  good fixture: %d finding(s) (want 0)" % len(results["good"]))
    for code in unexpected:
        print("    UNEXPECTED %s" % code)
        status = 1
    print("  bad fixture:  %d of %d rule(s) fired" % (len(fired), len(expected)))
    for code in expected:
        print("    %-28s %s" % (code, "fires" if code in fired else "NEVER FIRES"))
    if missing:
        status = 1
    if status == 0:
        print("  every rule both fires and stays quiet.")
    return status


ALL_CODES = (
    "E_POM_UNREADABLE",
    "E_GIT_PROPERTIES_OFF",
    "E_GIT_PROPERTIES_PATH",
    "E_GIT_PROPERTY_FILTERED",
    "E_NO_GIT_FAILS",
    "E_BUILD_INFO_UNBOUND",
    "E_EMPTY_GIT_PROPERTIES_KEPT",
)


def main():
    argument = sys.argv[1] if len(sys.argv) > 1 else ""
    if argument == "--self-test":
        return self_test()

    path = argument or DEFAULT_POM
    return report(path, analyse(path))


if __name__ == "__main__":
    sys.exit(main())
