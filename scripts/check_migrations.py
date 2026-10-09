"""Checker behind scripts/check_migrations.sh - see that file for why this check exists.

Reads NUL-separated paths on stdin (``git ls-files -z``) and enforces the ``database-migration``
capability: every Liquibase changeset is formatted SQL, one per file, in a file whose name carries
the changeset's own identity, wired up by an XML root changelog that does nothing but include them.

Kept as Python rather than folded into the shell script for the same reason check_image_pins.py is:
the interesting part is a per-module grouping and a dozen rules that each need a precise message,
and that is unreadable as a pipeline of ``grep -E`` - which is how a check nobody trusts gets
disabled.

Every rule carries a stable code (``E_*``). The codes are not decoration: ``--self-test`` asserts
that the fixture tree under scripts/testdata/check_migrations/ makes every one of them both fire
and not fire, so a rule cannot rot into one that never triggers.
"""

import os
import re
import sys

# The dbchangelog XSD version every root changelog pins. It tracks the Liquibase that
# spring-boot-dependencies resolves (4.27.0 under Spring Boot 3.3.5), and moves when that moves -
# which is why it is one constant here rather than a literal in 13 files nobody greps for.
PINNED_XSD = "4.27"

# The three precondition types Liquibase's formatted-SQL parser implements. Anything else raises
# "'<type>' precondition type is not supported." at parse time, which means at service startup.
# Checking it here turns a startup failure into a build failure. Verified against
# liquibase.parser.core.formattedsql.FormattedSqlChangeLogParser in 4.27.0.
SUPPORTED_PRECONDITIONS = ("sql-check", "table-exists", "view-exists")

HEADER = "--liquibase formatted sql"

# `--changeset <author>:<id> [attr:value ...]`
CHANGESET = re.compile(r"^--\s*changeset\s+(?P<author>[^:\s]+):(?P<id>\S+)(?P<attrs>.*)$")

# `NNNN-<slug>.sql`, four digits and lower-case kebab-case. The number is zero-padded so that the
# tree sorts in application order in every tool that lists a directory.
SQL_FILENAME = re.compile(r"^(?P<number>\d{4})-(?P<slug>[a-z0-9]+(?:-[a-z0-9]+)*)\.sql$")

ROLLBACK = re.compile(r"^--\s*rollback\s+\S", re.IGNORECASE)
PRECONDITION = re.compile(r"^--\s*precondition-(?P<type>[a-zA-Z0-9-]+)\b")
PRECONDITIONS = re.compile(r"^--\s*preconditions\s+\S")
CHANGE_SET_XML = re.compile(r"<changeSet\b")
INCLUDE_XML = re.compile(r"<include\b[^>]*\bfile\s*=\s*\"(?P<file>[^\"]+)\"", re.DOTALL)
XSD_XML = re.compile(r"dbchangelog-(?P<version>[0-9.]+)\.xsd")

# A module's resources root, and the classpath-relative path beneath it. Matching this is what
# scopes a module: `sources/audit-spring-boot-starter/src/test/resources/db/changelog/...` is a
# different scope from the same module's `src/main`, because they are applied by different beans
# against different databases and their ids never share a DATABASECHANGELOG.
SCOPE = re.compile(
    r"^(?P<module>(?:build|services|sources)/[^/]+)/src/(?P<sourceset>main|test)"
    r"/resources/(?P<classpath>db/changelog/.*)$"
)

# Directive names Liquibase recognises in a formatted SQL file. Used only to catch a near-miss: a
# line that opens with one of these but does not parse is a directive the author believes is
# active and Liquibase reads as prose. A dropped `--precondition-table-exists` that way is a
# migration that fails on the one consumer the guard existed for.
KNOWN_DIRECTIVES = (
    "changeset",
    "rollback",
    "preconditions",
    "property",
    "validchecksum",
    "ignorelines",
)

# Directives this checker recognises but has no rule about, so they must not be mistaken for a
# near-miss. The format header is one of them: it opens with `liquibase`, which would otherwise
# look like a directive name that failed to parse on every single conforming file.
INERT = (
    re.compile(r"^--\s*liquibase\s+formatted\s+sql\s*$", re.IGNORECASE),
    re.compile(r"^--\s*comment\s+\S", re.IGNORECASE),
    re.compile(r"^--\s*property\s+\S", re.IGNORECASE),
    re.compile(r"^--\s*validCheckSum\s+\S", re.IGNORECASE),
    re.compile(r"^--\s*ignoreLines:\s*\S", re.IGNORECASE),
)


class Finding:
    """One violation: a stable code, the place, and a sentence that says what to do."""

    def __init__(self, code, path, line, message):
        self.code = code
        self.path = path
        self.line = line
        self.message = message

    def __str__(self):
        where = "%s:%d" % (self.path, self.line) if self.line else self.path
        return "  %-22s %s\n%s%s" % (self.code, where, " " * 25, self.message)


class SqlChangeset:
    """One formatted-SQL changelog file, which by rule holds exactly one changeset."""

    def __init__(self, path, classpath):
        self.path = path
        self.classpath = classpath
        self.name = os.path.basename(path)
        self.number = None
        self.slug = None
        self.author = None
        self.id = None
        self.prefix = None


# Maps a logical path onto the file that actually holds it. Empty in every real run; populated
# only by --self-test, whose fixtures sit under scripts/testdata/ rather than in the module layout
# SCOPE matches. Keeping the fixture tree shallow is what makes it readable as a fixture.
_ALIAS = {}


def read(path):
    try:
        with open(_ALIAS.get(path, path), encoding="utf-8") as handle:
            return handle.read().splitlines()
    except (OSError, UnicodeDecodeError):
        return None


def parse_attrs(text):
    """`splitStatements:false dbms:postgresql` -> {'splitstatements': 'false', 'dbms': ...}."""
    attrs = {}
    for token in text.split():
        if ":" in token:
            key, _, value = token.partition(":")
            attrs[key.strip().lower()] = value.strip()
    return attrs


def check_sql_file(path, classpath, findings):
    """Every rule that is a fact about one SQL changelog file on its own."""
    lines = read(path)
    if lines is None:
        findings.append(Finding("E_UNREADABLE", path, 0, "could not be read as UTF-8 text."))
        return None

    changeset = SqlChangeset(path, classpath)

    first = next((line for line in lines if line.strip()), "")
    if first.strip().lower() != HEADER:
        findings.append(Finding(
            "E_SQL_HEADER", path, 1,
            "first non-blank line must be exactly '%s'. Without it Liquibase parses the whole "
            "file as one unnamed changeset with no id, author or rollback." % HEADER))

    declarations = []
    rollbacks = 0
    for number, line in enumerate(lines, start=1):
        stripped = line.strip()
        if not stripped.startswith("--"):
            continue

        match = CHANGESET.match(stripped)
        if match:
            declarations.append((number, match))
            continue
        if ROLLBACK.match(stripped):
            rollbacks += 1
            continue

        precondition = PRECONDITION.match(stripped)
        if precondition:
            kind = precondition.group("type")
            if kind not in SUPPORTED_PRECONDITIONS:
                findings.append(Finding(
                    "E_PRECONDITION", path, number,
                    "'%s' is not a precondition type the formatted-SQL parser implements. It "
                    "supports only %s, and raises a parse error at startup for anything else."
                    % (kind, ", ".join(SUPPORTED_PRECONDITIONS))))
            continue
        if PRECONDITIONS.match(stripped):
            continue
        if any(pattern.match(stripped) for pattern in INERT):
            continue

        # A prose comment is fine and expected. A line that merely *looks* like a directive is not.
        # .strip() before taking the first token, not after: Liquibase itself matches its directives
        # as `--\s*<name>`, so `--     changeset moved.` in a prose paragraph is a line it tries to
        # parse as a changeset and then rejects the whole file over. Splitting without stripping
        # yields an empty first token for exactly those indented lines, which is the shape that got
        # a wrapped sentence beginning with the word "changeset" past this check once already.
        words = stripped[2:].strip().split()
        head = re.sub(r"[^a-z]", "", words[0].lower()) if words else ""
        if head in KNOWN_DIRECTIVES:
            findings.append(Finding(
                "E_DIRECTIVE_SUSPECT", path, number,
                "opens with the directive name '%s' but does not parse as one. Before the first "
                "changeset Liquibase rejects the whole changelog over this; after it, Liquibase "
                "reads the line as prose and whatever it was meant to declare is not in effect. "
                "If it is prose, reflow it so the line does not begin with the word."
                % head))

    if len(declarations) != 1:
        findings.append(Finding(
            "E_CHANGESET_COUNT", path, declarations[0][0] if declarations else 1,
            "holds %d changesets; the rule is exactly one per file, which is what lets the "
            "filename carry the changeset's identity." % len(declarations)))

    name_match = SQL_FILENAME.match(changeset.name)
    if not name_match:
        findings.append(Finding(
            "E_FILENAME", path, 0,
            "name must be NNNN-<slug>.sql - four zero-padded digits and a lower-case "
            "kebab-case slug."))

    if not declarations:
        return changeset

    line_number, match = declarations[0]
    changeset.author = match.group("author")
    changeset.id = match.group("id")
    attrs = parse_attrs(match.group("attrs"))

    if attrs.get("dbms") != "postgresql":
        findings.append(Finding(
            "E_DBMS", path, line_number,
            "changeset must declare dbms:postgresql. Every test that applies these changelogs "
            "runs against a PostgreSQLContainer, and several use constructs no other database "
            "has; declaring it puts that constraint on the statement rather than in a header "
            "comment the next changeset gets appended below."))

    if rollbacks == 0:
        findings.append(Finding(
            "E_ROLLBACK", path, line_number,
            "changeset declares no rollback. Give it one, or '--rollback NOT REQUIRED', which "
            "Liquibase parses to the same empty rollback as XML's <rollback/>. The point is not "
            "that every migration is reversible - it is that the author decided and recorded it."))

    if name_match:
        changeset.number = name_match.group("number")
        changeset.slug = name_match.group("slug")
        suffix = "-%s-%s" % (changeset.number, changeset.slug)
        if not changeset.id.endswith(suffix):
            findings.append(Finding(
                "E_ID_MISMATCH", path, line_number,
                "changeset id '%s' does not end in '%s', so the filename and the row in "
                "DATABASECHANGELOG name the same migration differently."
                % (changeset.id, suffix)))
        else:
            changeset.prefix = changeset.id[: -len(suffix)]
            if not changeset.prefix:
                findings.append(Finding(
                    "E_ID_MISMATCH", path, line_number,
                    "changeset id '%s' carries no module prefix. A library's changelog is "
                    "applied into a consuming application's DATABASECHANGELOG, where an "
                    "un-namespaced id is a collision waiting for one unlucky consumer."
                    % changeset.id))

    return changeset


def check_xml_file(path, findings):
    """Every rule that is a fact about one XML changelog on its own. Returns its includes.

    Run on every XML file under db/changelog, not only on a scope's single root: a scope that has
    the wrong number of XML files is reported separately, and the files in it still have to be
    checked - which is the bug the fixture self-test caught, since the one file in the repository
    pinned to the wrong XSD was in exactly that position.
    """
    lines = read(path)
    if lines is None:
        findings.append(Finding("E_UNREADABLE", path, 0, "could not be read as UTF-8 text."))
        return []

    text = "\n".join(lines)

    for number, line in enumerate(lines, start=1):
        if CHANGE_SET_XML.search(line):
            findings.append(Finding(
                "E_XML_CHANGESET", path, number,
                "declares a changeset in XML. A changelog's XML holds includes and comments only; "
                "the changeset belongs in its own NNNN-<slug>.sql file."))

    versions = {match.group("version") for match in XSD_XML.finditer(text)}
    for version in sorted(versions):
        if version != PINNED_XSD:
            findings.append(Finding(
                "E_XSD_VERSION", path, 0,
                "pins dbchangelog-%s.xsd; every root pins %s, the version matching the Liquibase "
                "that spring-boot-dependencies resolves." % (version, PINNED_XSD)))

    includes = []
    for match in INCLUDE_XML.finditer(text):
        target = match.group("file")
        line = text.count("\n", 0, match.start()) + 1
        includes.append((line, target))
    return includes


def classpath_of(target):
    """`classpath:db/changelog/x.sql` and `db/changelog/x.sql` name the same resource."""
    cleaned = target.strip()
    for prefix in ("classpath:", "classpath*:"):
        if cleaned.startswith(prefix):
            cleaned = cleaned[len(prefix):]
    return cleaned.lstrip("/")


def analyse(paths):
    """Group the tracked changelog files into scopes and apply every rule."""
    findings = []
    scopes = {}

    for path in sorted(paths):
        match = SCOPE.match(path)
        if not match:
            continue
        key = (match.group("module"), match.group("sourceset"))
        scope = scopes.setdefault(key, {"roots": [], "sql": [], "xml": []})
        classpath = match.group("classpath")
        if path.endswith(".sql"):
            scope["sql"].append((path, classpath))
        elif path.endswith(".xml"):
            scope["xml"].append((path, classpath))

    # Every resource any scope ships, so that a service's include of a starter's changelog - which
    # lives in another module's jar entirely - resolves rather than being reported missing.
    everywhere = {}
    for scope in scopes.values():
        for path, classpath in scope["sql"] + scope["xml"]:
            everywhere.setdefault(classpath, []).append(path)

    inventory = []

    for key in sorted(scopes):
        module, sourceset = key
        scope = scopes[key]
        label = "%s (%s)" % (module, sourceset)

        # Every XML file is checked on its own terms first, whatever the scope's shape.
        includes_by_path = {}
        for path, _ in sorted(scope["xml"]):
            includes_by_path[path] = check_xml_file(path, findings)

        # The root is the scope's single XML changelog. In practice each scope has exactly one, so
        # a scope with two is reported rather than guessed at - picking one would make the orphan
        # and ordering rules report against a file that may not be the real root.
        if len(scope["xml"]) != 1:
            findings.append(Finding(
                "E_ROOT_COUNT",
                module, 0,
                "%s has %d XML changelogs under db/changelog; a scope has exactly one root, and "
                "every changeset lives in a .sql file beside it."
                % (label, len(scope["xml"]))))
            root_path, includes = None, []
        else:
            root_path = scope["xml"][0][0]
            includes = includes_by_path[root_path]

        changesets = []
        for path, classpath in sorted(scope["sql"]):
            changeset = check_sql_file(path, classpath, findings)
            if changeset:
                changesets.append(changeset)

        # One prefix and one author per scope, and the author derived from the prefix. Deriving it
        # is what lets this check verify namespacing without a per-module table it could drift from.
        prefixes = sorted({c.prefix for c in changesets if c.prefix})
        if len(prefixes) > 1:
            findings.append(Finding(
                "E_PREFIX_MIXED", module, 0,
                "%s mixes changeset id prefixes %s. Every changeset reachable from one root "
                "shares one prefix." % (label, ", ".join(prefixes))))
        for changeset in changesets:
            if changeset.prefix and changeset.author != "ludwig-%s" % changeset.prefix:
                findings.append(Finding(
                    "E_AUTHOR", changeset.path, 0,
                    "author is '%s' but the id prefix is '%s', so the author must be 'ludwig-%s'. "
                    "The author is derived from the prefix so that namespacing is checkable "
                    "without a per-module table."
                    % (changeset.author, changeset.prefix, changeset.prefix)))

        seen_numbers = {}
        for changeset in changesets:
            if not changeset.number:
                continue
            if changeset.number in seen_numbers:
                findings.append(Finding(
                    "E_NUMBER_DUPLICATE", changeset.path, 0,
                    "number %s is already used by %s in the same module. A repeated number is a "
                    "reader's collision even where Liquibase's own key survives it, because that "
                    "key includes the filename."
                    % (changeset.number, seen_numbers[changeset.number])))
            else:
                seen_numbers[changeset.number] = changeset.path

        by_classpath = {c.classpath: c for c in changesets}
        included_here = []
        for line, target in includes:
            resolved = classpath_of(target)
            if resolved not in everywhere:
                findings.append(Finding(
                    "E_INCLUDE_MISSING", root_path, line,
                    "includes '%s', which no module ships. An include is resolved when the "
                    "changelog is parsed, so this fails every migration - and therefore every "
                    "startup - rather than degrading." % target))
                continue
            if resolved in by_classpath:
                included_here.append((line, by_classpath[resolved]))

        # Ascent is checked over this module's own files only. A foreign include is deliberately
        # interleaved in notification-service - job-core's root above the changeset that drops the
        # old lock table, idempotency's above the one that drops the old claim table - and firing
        # on that would make the rule's first real encounter a false positive.
        previous = None
        for line, changeset in included_here:
            # A file whose name does not parse has no number to compare; E_FILENAME already has it,
            # and ordering around it would be an opinion about a name that must change anyway.
            if changeset.number is None:
                continue
            if previous and changeset.number <= previous[1].number:
                findings.append(Finding(
                    "E_ORDER", root_path, line,
                    "includes %s after %s, so the numbering is not the application order. A "
                    "reader who trusts the numbers to say when a column exists would be wrong."
                    % (changeset.name, previous[1].name)))
            previous = (line, changeset)

        included_paths = {c.classpath for _, c in included_here}
        for changeset in changesets:
            if changeset.classpath not in included_paths:
                findings.append(Finding(
                    "E_ORPHAN", changeset.path, 0,
                    "is not included by %s, so it is never applied. A migration file nothing "
                    "includes is a schema change that exists in the tree and not in the database."
                    % (os.path.basename(root_path) if root_path else "the scope's root")))

        inventory.append({
            "label": label,
            "root": root_path,
            "prefix": prefixes[0] if len(prefixes) == 1 else ",".join(prefixes),
            "author": changesets[0].author if changesets else "-",
            "changesets": [c for _, c in included_here] or changesets,
        })

    return findings, inventory


def report(findings, inventory, listing):
    changeset_count = sum(len(entry["changesets"]) for entry in inventory)

    if listing:
        for entry in inventory:
            print("%s" % entry["label"])
            print("  root      %s" % (entry["root"] or "MISSING"))
            print("  prefix    %s" % (entry["prefix"] or "-"))
            print("  author    %s" % entry["author"])
            for changeset in entry["changesets"]:
                print("  %-6s    %s" % (changeset.number or "????", changeset.name))
        print("")
        print("%d root changelog(s), %d changeset(s), %d finding(s)."
              % (len(inventory), changeset_count, len(findings)))
        return 0

    if findings:
        print("check_migrations.sh: FAILED. %d violation(s) of the database-migration capability:"
              % len(findings))
        print("")
        for finding in sorted(findings, key=lambda f: (f.path, f.line, f.code)):
            print(finding)
        print("")
        print("  Every changeset is Liquibase formatted SQL, one per file, named NNNN-<slug>.sql")
        print("  with id <prefix>-NNNN-<slug> and author ludwig-<prefix>; the XML root changelog")
        print("  holds includes and comments only. See openspec/specs/database-migration/spec.md.")
        return 1

    print("check_migrations.sh: %d root changelog(s), %d changeset(s), all conforming."
          % (len(inventory), changeset_count))
    return 0


def self_test():
    """Assert the fixture tree makes every rule both fire and not fire.

    A rule that cannot be shown to fire is a rule that may already have stopped working. This is
    the check on the checker, which is otherwise the one rule in the repository with nothing
    standing behind it.
    """
    root = os.path.join("scripts", "testdata", "check_migrations")
    results = {}
    for case in ("good", "bad"):
        base = os.path.join(root, case)
        paths = []
        for directory, _, names in os.walk(base):
            for name in names:
                paths.append(os.path.join(directory, name))
        # The fixtures live under a scripts/ path, so rewrite them onto the module layout SCOPE
        # expects. Keeping the fixture tree shallow is what makes it readable as a fixture.
        rewritten = {}
        for path in paths:
            relative = os.path.relpath(path, base)
            module, _, rest = relative.partition(os.sep)
            rewritten["sources/%s/src/main/resources/%s" % (module, rest)] = path
        _ALIAS.clear()
        _ALIAS.update(rewritten)
        try:
            findings, _ = analyse(sorted(rewritten))
        finally:
            _ALIAS.clear()
        results[case] = findings

    expected = sorted(ALL_CODES)
    fired = sorted({f.code for f in results["bad"]})
    missing = [code for code in expected if code not in fired]
    unexpected = sorted({f.code for f in results["good"]})

    status = 0
    print("check_migrations.sh --self-test")
    print("  good fixture: %d finding(s) (want 0)" % len(results["good"]))
    for code in unexpected:
        print("    UNEXPECTED %s" % code)
        status = 1
    print("  bad fixture:  %d of %d rule(s) fired" % (len(fired), len(expected)))
    for code in expected:
        print("    %-22s %s" % (code, "fires" if code in fired else "NEVER FIRES"))
    if missing:
        status = 1
    if status == 0:
        print("  every rule both fires and stays quiet.")
    return status


ALL_CODES = (
    "E_SQL_HEADER",
    "E_CHANGESET_COUNT",
    "E_FILENAME",
    "E_ID_MISMATCH",
    "E_DBMS",
    "E_ROLLBACK",
    "E_PRECONDITION",
    "E_DIRECTIVE_SUSPECT",
    "E_XML_CHANGESET",
    "E_XSD_VERSION",
    "E_PREFIX_MIXED",
    "E_AUTHOR",
    "E_NUMBER_DUPLICATE",
    "E_ORDER",
    "E_INCLUDE_MISSING",
    "E_ORPHAN",
    "E_ROOT_COUNT",
)


def main():
    argument = sys.argv[1] if len(sys.argv) > 1 else ""
    if argument == "--self-test":
        return self_test()

    paths = [p for p in sys.stdin.read().split("\0") if p]
    findings, inventory = analyse(paths)
    return report(findings, inventory, argument == "--list")


if __name__ == "__main__":
    sys.exit(main())
