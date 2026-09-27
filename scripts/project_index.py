#!/usr/bin/env python3
"""Generate project-index.json and PROJECT_INDEX.md from the POMs and the filesystem.

Never edit the generated files by hand. A hand-maintained index drifts from the reactor,
and an index that is quietly wrong is worse than no index at all: an agent that trusts it
stops checking. Everything here is derived, so regenerating is always the fix.

Invoked through ``scripts/manifest.sh build``; run directly only when debugging the generator.

Staleness is content-based, not timestamp-based. The header carries a SHA over the full set
of POM paths and their contents (``pomSetSha``), because mtimes change on every checkout and
would report a fresh index as stale after every ``git clone`` or branch switch.
"""

import hashlib
import json
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from datetime import datetime, timezone

POM_NS = "{http://maven.apache.org/POM/4.0.0}"
GROUP = "ru.ludwigandreas"

# Directories that are build output or VCS metadata. Indexing target/ would surface QueryDSL
# Q-types and MapStruct implementations as if they were source, which is exactly the noise the
# Checkstyle configuration also goes out of its way to exclude.
PRUNE_DIRS = {"target", "generated-sources", ".git", ".idea", "node_modules", ".mvn"}


def repo_root():
    here = os.path.dirname(os.path.abspath(__file__))
    return os.path.dirname(here)


def walk(root):
    """os.walk with the build-output directories pruned in place."""
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in PRUNE_DIRS]
        yield dirpath, dirnames, filenames


def text(node, tag):
    child = node.find(POM_NS + tag)
    return child.text.strip() if child is not None and child.text else None


def read_revision(root):
    path = os.path.join(root, ".mvn", "maven.config")
    if not os.path.isfile(path):
        return None
    with open(path, encoding="utf-8") as handle:
        match = re.search(r"-Drevision=(\S+)", handle.read())
    return match.group(1) if match else None


def module_dirs(root):
    """The <module> entries of the reactor root, in declaration order."""
    tree = ET.parse(os.path.join(root, "pom.xml"))
    modules = tree.getroot().find(POM_NS + "modules")
    if modules is None:
        return []
    return [m.text.strip() for m in modules.findall(POM_NS + "module") if m.text]


def classify(artifact_id, parent_artifact, packaging):
    """Assign the module's role.

    Ordered most specific first: the two published platform POMs are recognised by their
    coordinates, a service by its parent (which is the definition in CLAUDE.md, not a naming
    convention), and only then do the name-based buckets apply.
    """
    if artifact_id == "ludwig-bom":
        return "bom"
    if artifact_id == "ludwig-service-parent":
        return "parent"
    if parent_artifact == "ludwig-service-parent":
        return "service"
    if artifact_id.startswith("test-support"):
        return "test-support"
    if artifact_id in ("architecture-rules", "checkstyle-rules"):
        return "rules"
    if artifact_id.endswith("-spring-boot-starter"):
        return "starter"
    return "library"


def package_root(module_path):
    """The deepest package directory that still contains every source file below it.

    Returned as a dotted name. Descends while there is exactly one subdirectory and no .java
    file at the current level, which lands on ru.ludwigandreas.<x> for every module here.
    """
    base = os.path.join(module_path, "src", "main", "java")
    if not os.path.isdir(base):
        base = os.path.join(module_path, "src", "test", "java")
        if not os.path.isdir(base):
            return None
    parts = []
    current = base
    while True:
        try:
            entries = sorted(os.listdir(current))
        except OSError:
            break
        subdirs = [e for e in entries if os.path.isdir(os.path.join(current, e))]
        has_java = any(e.endswith(".java") for e in entries)
        if has_java or len(subdirs) != 1:
            break
        parts.append(subdirs[0])
        current = os.path.join(current, subdirs[0])
    return ".".join(parts) if parts else None


def in_repo_dependencies(pom_root):
    """In-repo <dependencies>, every scope included.

    Scope is recorded but never used to filter. Maven builds the reactor DAG from all declared
    dependencies: test, provided and optional do not exempt an edge. That is the whole reason
    test-support and cache-spring-boot-starter carry no sibling dependency, so an index that
    hid test-scoped edges would hide precisely the cycles this field exists to prevent.
    """
    found = []
    container = pom_root.find(POM_NS + "dependencies")
    if container is None:
        return found
    for dep in container.findall(POM_NS + "dependency"):
        if text(dep, "groupId") != GROUP:
            continue
        found.append({
            "artifactId": text(dep, "artifactId"),
            "scope": text(dep, "scope") or "compile",
            "optional": (text(dep, "optional") or "false") == "true",
        })
    return sorted(found, key=lambda d: d["artifactId"])


def imports_bom(pom_root):
    managed = pom_root.find(POM_NS + "dependencyManagement")
    if managed is None:
        return False
    for dep in managed.iter(POM_NS + "dependency"):
        if text(dep, "artifactId") == "ludwig-bom" and text(dep, "scope") == "import":
            return True
    return False


def rel(root, path):
    return os.path.relpath(path, root).replace(os.sep, "/")


def find_autoconfig(root, module_path):
    path = os.path.join(
        module_path, "src", "main", "resources", "META-INF", "spring",
        "org.springframework.boot.autoconfigure.AutoConfiguration.imports",
    )
    return rel(root, path) if os.path.isfile(path) else None


def find_changelogs(root, module_path):
    """Liquibase changelogs shipped in src/main/resources/db/changelog.

    Only main resources: a test changelog is scaffolding for that module's own container test,
    not a contract a consumer applies.
    """
    base = os.path.join(module_path, "src", "main", "resources", "db", "changelog")
    if not os.path.isdir(base):
        return []
    out = []
    for dirpath, _dirnames, filenames in walk(base):
        for name in filenames:
            if name.endswith((".xml", ".yaml", ".yml", ".sql")):
                out.append(rel(root, os.path.join(dirpath, name)))
    return sorted(out)


def find_i18n(root, module_path):
    base = os.path.join(module_path, "src", "main", "resources", "i18n")
    if not os.path.isdir(base):
        return []
    return sorted(
        rel(root, os.path.join(base, name))
        for name in os.listdir(base)
        if name.endswith(".properties")
    )


REST_MARKERS = ("@RestController", "@Controller", "@RequestMapping")
ACTUATOR_MARKERS = ("@Endpoint", "@WebEndpoint", "@ReadOperation", "HealthIndicator", "InfoContributor")


def find_surfaces(module_path):
    """Which externally reachable surfaces the module exposes.

    Annotation-text based: a definitive answer needs the compiled bytecode, and the index has
    to stay runnable in under a second on a clean clone with nothing built.
    """
    base = os.path.join(module_path, "src", "main", "java")
    surfaces = set()
    if not os.path.isdir(base):
        return []
    for dirpath, _dirnames, filenames in walk(base):
        for name in filenames:
            if not name.endswith(".java"):
                continue
            with open(os.path.join(dirpath, name), encoding="utf-8", errors="replace") as handle:
                body = handle.read()
            if any(marker in body for marker in REST_MARKERS):
                surfaces.add("rest")
            if any(marker in body for marker in ACTUATOR_MARKERS):
                surfaces.add("actuator")
    return sorted(surfaces) if surfaces else ["none"]


def find_test_dirs(root, module_path):
    """Which of unit / integration / architecture exist, and where.

    Searched anywhere under src/test/java rather than at a fixed depth: job-core nests them at
    ru/ludwigandreas/job/core/ and crud-service-example at ru/ludwigandreas/example/catalog/,
    so the fixed path CLAUDE.md describes would miss most modules.
    """
    base = os.path.join(module_path, "src", "test", "java")
    out = {}
    if not os.path.isdir(base):
        return out
    for dirpath, dirnames, _filenames in walk(base):
        for name in dirnames:
            if name in ("unit", "integration", "architecture"):
                out.setdefault(name, rel(root, os.path.join(dirpath, name)))
    return out


def count_tests(module_path):
    """Test classes split by the surefire/failsafe naming rule.

    The split is not cosmetic: surefire excludes *IT and *IntegrationTest, and failsafe runs
    only those at verify. A test named neither way runs under surefire; a test misnamed as an
    integration test never runs under `mvn test` at all. Surfacing both counts makes the
    asymmetry visible in the index.
    """
    base = os.path.join(module_path, "src", "test", "java")
    unit = integration = 0
    if not os.path.isdir(base):
        return {"surefire": 0, "failsafe": 0}
    for dirpath, _dirnames, filenames in walk(base):
        for name in filenames:
            if not name.endswith(".java"):
                continue
            stem = name[:-5]
            if stem.endswith("IT") or stem.endswith("IntegrationTest"):
                integration += 1
            elif stem.endswith("Test") or stem.endswith("Tests"):
                unit += 1
    return {"surefire": unit, "failsafe": integration}


def readme_paths(root, module_path):
    out = {}
    for key, name in (("readme", "README.md"), ("readmeRu", "README.ru.md")):
        path = os.path.join(module_path, name)
        out[key] = rel(root, path) if os.path.isfile(path) else None
    return out


def git(root, *args):
    try:
        return subprocess.check_output(
            ["git"] + list(args), cwd=root, stderr=subprocess.DEVNULL
        ).decode().strip()
    except (subprocess.CalledProcessError, OSError):
        return None


def sha256_file(path):
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(65536), b""):
            digest.update(chunk)
    return digest.hexdigest()


def pom_set_fingerprint(root):
    """(sha, newest_pom_relpath, newest_pom_iso) over every POM in the reactor.

    The SHA covers paths as well as contents, so adding or deleting a module changes it even if
    no existing POM was edited.
    """
    poms = []
    for dirpath, _dirnames, filenames in walk(root):
        for name in filenames:
            if name == "pom.xml":
                poms.append(os.path.join(dirpath, name))
    poms.sort()
    digest = hashlib.sha256()
    newest_path, newest_mtime = None, -1.0
    for path in poms:
        digest.update(rel(root, path).encode())
        digest.update(sha256_file(path).encode())
        mtime = os.path.getmtime(path)
        if mtime > newest_mtime:
            newest_path, newest_mtime = path, mtime
    newest_iso = (
        datetime.fromtimestamp(newest_mtime, tz=timezone.utc).isoformat().replace("+00:00", "Z")
        if newest_path else None
    )
    return digest.hexdigest(), (rel(root, newest_path) if newest_path else None), newest_iso


def build(root):
    revision = read_revision(root)
    modules = []
    for module_dir in module_dirs(root):
        module_path = os.path.join(root, module_dir)
        pom_path = os.path.join(module_path, "pom.xml")
        pom_root = ET.parse(pom_path).getroot()
        parent = pom_root.find(POM_NS + "parent")
        parent_artifact = text(parent, "artifactId") if parent is not None else None
        artifact_id = text(pom_root, "artifactId")
        packaging = text(pom_root, "packaging") or "jar"
        record = {
            "name": module_dir,
            "artifactId": artifact_id,
            "packaging": packaging,
            "role": classify(artifact_id, parent_artifact, packaging),
            "parentPom": parent_artifact or "none",
            "importsLudwigBom": imports_bom(pom_root),
            "packageRoot": package_root(module_path),
            "inRepoDependencies": in_repo_dependencies(pom_root),
            "inRepoDependents": [],
            "autoConfigImports": find_autoconfig(root, module_path),
            "liquibaseChangelogs": find_changelogs(root, module_path),
            "i18nBundles": find_i18n(root, module_path),
            "surfaces": find_surfaces(module_path),
            "testDirs": find_test_dirs(root, module_path),
            "testClasses": count_tests(module_path),
            "pom": rel(root, pom_path),
        }
        record.update(readme_paths(root, module_path))
        modules.append(record)

    by_artifact = {m["artifactId"]: m for m in modules}
    for module in modules:
        for dep in module["inRepoDependencies"]:
            target = by_artifact.get(dep["artifactId"])
            if target is not None:
                target["inRepoDependents"].append({
                    "artifactId": module["artifactId"],
                    "scope": dep["scope"],
                })
    for module in modules:
        module["inRepoDependents"].sort(key=lambda d: d["artifactId"])

    pom_sha, newest_pom, newest_iso = pom_set_fingerprint(root)
    header = {
        "generator": "scripts/project_index.py (via scripts/manifest.sh build)",
        "generatedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "revision": revision,
        "moduleCount": len(modules),
        "commit": git(root, "rev-parse", "HEAD"),
        "commitShort": git(root, "rev-parse", "--short", "HEAD"),
        "branch": git(root, "rev-parse", "--abbrev-ref", "HEAD"),
        "freshness": {
            "pomSetSha": pom_sha,
            "newestPom": newest_pom,
            "newestPomModified": newest_iso,
        },
    }
    return {"index": header, "modules": modules}


# --------------------------------------------------------------------------------------
# Rendering
# --------------------------------------------------------------------------------------

GATE_BY_ROLE = {
    "bom": "mvn clean install  (every module imports ludwig-bom)",
    "parent": "mvn clean install  (every service inherits it)",
}

PREAMBLE = """\
## How to use this instead of searching

This file and `project-index.json` beside it are generated from the POMs and the filesystem by
`scripts/manifest.sh build`. They are the answer to every *structural* question about this
repository, and consulting them is cheaper by an order of magnitude than a search across 29
modules.

Read down this ladder and **stop at the first rung that answers your question**:

| # | Question | Where |
|---|---|---|
| 1 | Which module owns this? What depends on it? Which POM tier is it? | this file / `project-index.json` / `scripts/manifest.sh module <path>` |
| 2 | *Why* is this module shaped this way? | that module's `README.md`, linked per module below |
| 3 | Which files exist under this path shape? | `code-index` MCP: `find_files` |
| 4 | What is in this file? Where is this symbol defined? | `code-index` MCP: `get_file_summary`, then `get_symbol_body` |
| 5 | Who calls this? Which code contains this text? | `code-index` MCP: `search_code_advanced` — **not** `called_by`, which is intra-file only |
| 6 | Anything the above could not answer | `rg` — last resort |

Reaching rung 6 means saying, in your response, why rungs 1-5 could not answer it. That
sentence is the maintenance mechanism: a question the index cannot answer is a bug to file
against the index, not a reason to stop using it.

**Two fields decide whether a change builds**, so read them before adding any dependency:

- `inRepoDependencies` lists **every scope**, because Maven builds the reactor DAG from all
  declared dependencies and `test`, `provided` and `optional` do not exempt an edge. This is
  what the dependency-direction check in `docs/agent-operations.md` §5.1 is run against.
- `inRepoDependents` is the reverse edge, and it is the list the verification gate walks: every
  dependent of a module you touched gets its own `mvn -pl <dependent> -am verify`.

`ludwig-bom` is imported by every module (`importsLudwigBom`) rather than depended on, so it
carries no dependent edges. Changing it, or `ludwig-service-parent`, means a full
`mvn clean install` — there is no narrower gate.
"""


def render_markdown(data):
    header = data["index"]
    modules = data["modules"]
    lines = []
    lines.append("# PROJECT_INDEX")
    lines.append("")
    lines.append(
        "> **Generated file — do not edit.** Regenerate with `scripts/manifest.sh build`. "
        "Hand edits are lost and, worse, believed in the meantime."
    )
    lines.append("")
    lines.append("| | |")
    lines.append("|---|---|")
    lines.append("| revision | `%s` |" % header["revision"])
    lines.append("| modules | %d |" % header["moduleCount"])
    lines.append("| commit | `%s` (`%s`) |" % (header["commitShort"], header["branch"]))
    lines.append("| generated | %s |" % header["generatedAt"])
    fresh = header["freshness"]
    lines.append("| **freshness** | POM-set SHA `%s` · newest POM `%s` @ %s |" % (
        fresh["pomSetSha"][:16], fresh["newestPom"], fresh["newestPomModified"]))
    lines.append("")
    lines.append(
        "The freshness row is how you tell this index is stale: `scripts/manifest.sh stale` "
        "recomputes the POM-set SHA and exits non-zero if it differs. It is content-based, not "
        "timestamp-based, so a checkout or a branch switch does not report a false stale."
    )
    lines.append("")
    lines.append(PREAMBLE)
    lines.append("")

    lines.append("## Modules by role")
    lines.append("")
    order = ["bom", "parent", "library", "starter", "service", "test-support", "rules"]
    for role in order:
        group = [m for m in modules if m["role"] == role]
        if not group:
            continue
        lines.append("### %s (%d)" % (role, len(group)))
        lines.append("")
        lines.append("| module | parent POM | package root | surfaces | in-repo deps | dependents |")
        lines.append("|---|---|---|---|---|---|")
        for m in group:
            deps = ", ".join(
                "`%s`%s" % (d["artifactId"], "" if d["scope"] == "compile" else " (%s)" % d["scope"])
                for d in m["inRepoDependencies"]
            ) or "—"
            dependents = ", ".join("`%s`" % d["artifactId"] for d in m["inRepoDependents"]) or "—"
            lines.append("| **%s** | `%s` | `%s` | %s | %s | %s |" % (
                m["name"], m["parentPom"], m["packageRoot"] or "—",
                ", ".join(m["surfaces"]), deps, dependents))
        lines.append("")

    lines.append("## Dependency graph")
    lines.append("")
    lines.append(
        "Every in-repo edge, at every scope, `A -> B` meaning *A declares a dependency on B*. "
        "This is the graph Maven orders the reactor by. Before adding an edge A -> B, check that "
        "B has no path back to A here."
    )
    lines.append("")
    lines.append("```")
    roots = []
    for m in modules:
        if not m["inRepoDependencies"]:
            roots.append(m["artifactId"])
        for d in m["inRepoDependencies"]:
            suffix = "" if d["scope"] == "compile" else "  [%s]" % d["scope"]
            lines.append("%-42s -> %s%s" % (m["artifactId"], d["artifactId"], suffix))
    lines.append("```")
    lines.append("")
    lines.append(
        "**No in-repo dependencies at all** (%d modules) — several of them deliberately, because "
        "a module with no sibling edge can be depended on from anywhere without closing a cycle: "
        "%s." % (len(roots), ", ".join("`%s`" % r for r in sorted(roots)))
    )
    lines.append("")

    lines.append("## Per-module detail")
    lines.append("")
    for m in sorted(modules, key=lambda x: x["name"]):
        lines.append("### `%s`" % m["name"])
        lines.append("")
        lines.append("- role **%s**, packaging `%s`, parent `%s`, imports `ludwig-bom`: %s" % (
            m["role"], m["packaging"], m["parentPom"],
            "yes" if m["importsLudwigBom"] else "no"))
        lines.append("- package root `%s`" % (m["packageRoot"] or "—"))
        readme = "[`%s`](%s)" % (m["readme"], m["readme"]) if m["readme"] else "—"
        readme_ru = "[`%s`](%s)" % (m["readmeRu"], m["readmeRu"]) if m["readmeRu"] else "—"
        lines.append("- README %s · %s" % (readme, readme_ru))
        lines.append("- surfaces: %s" % ", ".join(m["surfaces"]))
        if m["autoConfigImports"]:
            lines.append("- auto-configuration: `%s`" % m["autoConfigImports"])
        if m["liquibaseChangelogs"]:
            lines.append("- Liquibase: %s" % ", ".join("`%s`" % c for c in m["liquibaseChangelogs"]))
        if m["i18nBundles"]:
            lines.append("- i18n: %s" % ", ".join("`%s`" % b for b in m["i18nBundles"]))
        if m["testDirs"]:
            lines.append("- test dirs: %s" % ", ".join(
                "%s (`%s`)" % (k, v) for k, v in sorted(m["testDirs"].items())))
        counts = m["testClasses"]
        lines.append("- test classes: %d surefire, %d failsafe (`*IT` / `*IntegrationTest`)" % (
            counts["surefire"], counts["failsafe"]))
        gate = GATE_BY_ROLE.get(m["role"])
        if gate:
            lines.append("- **gate**: `%s`" % gate)
        else:
            cmds = ["mvn -pl %s -am verify" % m["name"]]
            cmds += ["mvn -pl %s -am verify" % d["artifactId"] for d in m["inRepoDependents"]]
            lines.append("- **gate**: `mvn -q validate`, then %s" % ", ".join("`%s`" % c for c in cmds))
        lines.append("")
    return "\n".join(lines) + "\n"


def main(argv):
    root = repo_root()
    data = build(root)
    if "--check" in argv:
        # `stale`: recompute the fingerprint and compare with what the committed index claims.
        path = os.path.join(root, "project-index.json")
        if not os.path.isfile(path):
            sys.stderr.write("project-index.json is missing - run: scripts/manifest.sh build\n")
            return 2
        with open(path, encoding="utf-8") as handle:
            recorded = json.load(handle)["index"]["freshness"]["pomSetSha"]
        current = data["index"]["freshness"]["pomSetSha"]
        if recorded != current:
            sys.stderr.write(
                "project-index.json is STALE: recorded POM-set SHA %s, actual %s\n"
                "  run: scripts/manifest.sh build\n" % (recorded[:16], current[:16])
            )
            return 1
        print("project-index.json is current (POM-set SHA %s)" % current[:16])
        return 0

    with open(os.path.join(root, "project-index.json"), "w", encoding="utf-8") as handle:
        json.dump(data, handle, indent=2, sort_keys=False)
        handle.write("\n")
    with open(os.path.join(root, "PROJECT_INDEX.md"), "w", encoding="utf-8") as handle:
        handle.write(render_markdown(data))
    print("project-index.json + PROJECT_INDEX.md: %d modules, revision %s, POM-set SHA %s" % (
        data["index"]["moduleCount"], data["index"]["revision"],
        data["index"]["freshness"]["pomSetSha"][:16]))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
