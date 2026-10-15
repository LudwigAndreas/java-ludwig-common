#!/usr/bin/env python3
"""Checker behind scripts/check_protos.sh - see that file for why this check exists.

Reads a NUL-separated list of .proto paths on stdin (what `git ls-files -z` prints), plus the two
POMs that pin the protobuf and gRPC versions, and reports every departure from the grpc-contract
capability.

Kept as Python rather than folded into the shell script for the same reason check_migrations.py
is: a .proto has to be read with its comments removed and its braces matched, not grepped. A grep
for ``idempotency_level`` is satisfied by a comment promising to add one.

Every rule carries a stable code (``E_*``). ``--self-test`` asserts that the fixtures under
scripts/testdata/check_protos/ make every one of them both fire and not fire, so a rule cannot rot
into one that never triggers.
"""

import os
import re
import sys
import xml.etree.ElementTree as ElementTree

ROOT_POM = "pom.xml"
BOM_POM = os.path.join("build", "ludwig-bom", "pom.xml")
POM_NS = "{http://maven.apache.org/POM/4.0.0}"

# The one module that may hold a main proto without being a contract module: it owns the shared
# operation message every contract imports.
SHARED_CONTRACT_MODULE = "grpc-core"
CONTRACT_MODULE_SUFFIX = "-grpc-api"

MAIN_PROTO_DIR = "/src/main/proto/"
TEST_PROTO_DIR = "/src/test/proto/"

PACKAGE = re.compile(r"^ludwig((?:\.[a-z][a-z0-9_]*)+)\.v([1-9][0-9]*)$")

# The generator in the root POM, and the runtime in the BOM it must equal.
VERSION_PAIRS = (
    ("protoc.version", "protobuf.version"),
    ("protoc-gen-grpc-java.version", "grpc.version"),
)

ALL_CODES = (
    "E_PROTO_UNREADABLE",
    "E_PLACEMENT",
    "E_PACKAGE",
    "E_JAVA_PACKAGE",
    "E_MULTIPLE_FILES",
    "E_IDEMPOTENCY_UNDECLARED",
    "E_GENERATOR_DRIFT",
)


class Finding:
    """One violation: a stable code, the place, and a sentence that says what to do."""

    def __init__(self, code, path, message):
        self.code = code
        self.path = path
        self.message = message

    def render(self):
        return "%s  %s\n    %s" % (self.code, self.path, self.message)


class Proto:
    """What the checker needs from one file: its package, two options and every rpc."""

    def __init__(self, package, java_package, multiple_files, methods):
        self.package = package
        self.java_package = java_package
        self.multiple_files = multiple_files
        # (service, method, declares_idempotency_level)
        self.methods = methods


def strip_comments(text):
    """Remove // and /* */ comments, leaving string literals intact.

    A character walk rather than a regex, because a // inside a string literal - a URL in an
    option, say - is not a comment and a regex that treats it as one deletes the rest of the line.
    """
    out = []
    i = 0
    length = len(text)
    quote = None
    while i < length:
        char = text[i]
        if quote:
            out.append(char)
            if char == "\\" and i + 1 < length:
                out.append(text[i + 1])
                i += 2
                continue
            if char == quote:
                quote = None
            i += 1
        elif char in "\"'":
            quote = char
            out.append(char)
            i += 1
        elif text.startswith("//", i):
            end = text.find("\n", i)
            i = length if end < 0 else end
        elif text.startswith("/*", i):
            end = text.find("*/", i + 2)
            i = length if end < 0 else end + 2
            out.append(" ")
        else:
            out.append(char)
            i += 1
    return "".join(out)


def block_end(text, open_brace):
    """Index just past the brace that closes the block opened at ``open_brace``."""
    depth = 0
    for i in range(open_brace, len(text)):
        if text[i] == "{":
            depth += 1
        elif text[i] == "}":
            depth -= 1
            if depth == 0:
                return i + 1
    return len(text)


SERVICE = re.compile(r"\bservice\s+([A-Za-z_][A-Za-z0-9_]*)\s*\{")
RPC = re.compile(
    r"\brpc\s+([A-Za-z_][A-Za-z0-9_]*)\s*\([^)]*\)\s*returns\s*\([^)]*\)\s*(;|\{)")
IDEMPOTENCY = re.compile(r"\boption\s+idempotency_level\s*=\s*[A-Z_]+\s*;")


def parse(text):
    """Parse the handful of constructs this check is about. Not a protobuf parser."""
    text = strip_comments(text)
    package = re.search(r"^\s*package\s+([A-Za-z0-9_.]+)\s*;", text, re.M)
    java_package = re.search(r"\boption\s+java_package\s*=\s*\"([^\"]*)\"\s*;", text)
    multiple = re.search(r"\boption\s+java_multiple_files\s*=\s*(true|false)\s*;", text)

    methods = []
    for service in SERVICE.finditer(text):
        end = block_end(text, service.end() - 1)
        body = text[service.end():end]
        for rpc in RPC.finditer(body):
            declared = False
            if rpc.group(2) == "{":
                rpc_end = block_end(body, rpc.end() - 1)
                declared = bool(IDEMPOTENCY.search(body[rpc.end():rpc_end]))
            methods.append((service.group(1), rpc.group(1), declared))

    return Proto(package.group(1) if package else None,
                 java_package.group(1) if java_package else None,
                 multiple.group(1) == "true" if multiple else False,
                 methods)


def module_of(path):
    """The module directory name a proto sits in, or None when it is under no src tree."""
    normalised = "/" + path.replace(os.sep, "/")
    index = normalised.find("/src/")
    if index < 0:
        return None
    return normalised[:index].rsplit("/", 1)[-1] or None


def analyse_proto(base, path):
    findings = []
    normalised = "/" + path.replace(os.sep, "/")
    is_test = TEST_PROTO_DIR in normalised
    is_main = MAIN_PROTO_DIR in normalised

    try:
        with open(os.path.join(base, path), encoding="utf-8") as handle:
            proto = parse(handle.read())
    except (OSError, UnicodeDecodeError) as error:
        return [Finding("E_PROTO_UNREADABLE", path, "cannot be read: %s" % error)]

    if not is_test:
        module = module_of(path)
        in_contract_module = module is not None and (
            module.endswith(CONTRACT_MODULE_SUFFIX) or module == SHARED_CONTRACT_MODULE)
        if not (is_main and in_contract_module):
            findings.append(Finding(
                "E_PLACEMENT", path,
                "a .proto belongs in src/main/proto of a <service>%s module (or of %s). A proto "
                "inside a service makes callers depend on a jar that is built as an image; a "
                "private copy in the caller is the drift a published contract exists to stop."
                % (CONTRACT_MODULE_SUFFIX, SHARED_CONTRACT_MODULE)))

        match = PACKAGE.match(proto.package or "")
        if not match:
            findings.append(Finding(
                "E_PACKAGE", path,
                "package is %r; expected ludwig.<service>.v<N>, e.g. ludwig.billing.v1. The "
                "version is in the package so that v2 can be served beside v1."
                % (proto.package,)))
        else:
            expected = "ru.ludwigandreas%s.grpc.v%s" % (match.group(1), match.group(2))
            if proto.java_package != expected:
                findings.append(Finding(
                    "E_JAVA_PACKAGE", path,
                    "java_package is %r; expected %r. The pattern is the coverage and analysis "
                    "exclusion for generated code, so a contract outside it is measured as "
                    "hand-written." % (proto.java_package, expected)))
        if not proto.multiple_files:
            findings.append(Finding(
                "E_MULTIPLE_FILES", path,
                "declare `option java_multiple_files = true;` - without it every message is a "
                "nested class of one outer class named after the file, and renaming the file "
                "renames every type."))

    for service, method, declared in proto.methods:
        if not declared:
            findings.append(Finding(
                "E_IDEMPOTENCY_UNDECLARED", path,
                "rpc %s.%s declares no idempotency_level. Write one - IDEMPOTENCY_UNKNOWN if a "
                "repeat is not safe - because the gRPC client reads it to decide whether a failed "
                "call may be retried, and silence must not look like a decision."
                % (service, method)))
    return findings


def properties_of(path):
    tree = ElementTree.parse(path)
    properties = tree.getroot().find(POM_NS + "properties")
    if properties is None:
        return {}
    return {child.tag.replace(POM_NS, ""): (child.text or "").strip() for child in properties}


def analyse_versions(base):
    """The generator versions in the root POM must equal the runtime versions in the BOM."""
    root_pom = os.path.join(base, ROOT_POM)
    bom_pom = os.path.join(base, BOM_POM)
    try:
        root, bom = properties_of(root_pom), properties_of(bom_pom)
    except (OSError, ElementTree.ParseError) as error:
        return [Finding("E_GENERATOR_DRIFT", ROOT_POM,
                        "cannot compare generator and runtime versions: %s" % error)]
    findings = []
    for generator, runtime in VERSION_PAIRS:
        if root.get(generator) != bom.get(runtime):
            findings.append(Finding(
                "E_GENERATOR_DRIFT", ROOT_POM,
                "%s is %r but %s in %s is %r. Code generated by one line and run on another "
                "fails in a generated class at the first message - move both together."
                % (generator, root.get(generator), runtime, BOM_POM.replace(os.sep, "/"),
                   bom.get(runtime))))
    return findings


def analyse(base, paths):
    findings = analyse_versions(base)
    for path in sorted(paths):
        findings.extend(analyse_proto(base, path))
    return findings


def read_paths():
    data = sys.stdin.buffer.read().decode("utf-8")
    return [path for path in data.split("\0") if path]


def list_protos(paths):
    for path in sorted(paths):
        try:
            with open(path, encoding="utf-8") as handle:
                proto = parse(handle.read())
        except (OSError, UnicodeDecodeError) as error:
            print("%s  UNREADABLE %s" % (path, error))
            continue
        print("%s\n    package %s -> %s" % (path, proto.package, proto.java_package))
        for service, method, declared in proto.methods:
            print("    rpc %s.%s%s" % (service, method, "" if declared else "  (no idempotency_level)"))
    return 0


def report(findings, count):
    if not findings:
        print("check_protos.sh: %d .proto file(s) conform." % count)
        return 0
    for finding in findings:
        print(finding.render(), file=sys.stderr)
    print("\ncheck_protos.sh: %d violation(s) in %d .proto file(s)." % (len(findings), count),
          file=sys.stderr)
    return 1


def fixture_paths(base):
    found = []
    for directory, _, names in os.walk(base):
        for name in names:
            if name.endswith(".proto"):
                found.append(os.path.relpath(os.path.join(directory, name), base))
    return found


def self_test():
    """Assert the fixtures make every rule both fire and not fire.

    A rule that cannot be shown to fire is a rule that may already have stopped working. Each
    fixture tree is a miniature repository - its own pom.xml, BOM and module directories - so
    placement and version drift are exercised through the same code path as the real tree.
    """
    root = os.path.join("scripts", "testdata", "check_protos")
    results = {}
    for case in ("good", "bad"):
        base = os.path.join(root, case)
        results[case] = analyse(base, fixture_paths(base))

    expected = sorted(ALL_CODES)
    fired = sorted({f.code for f in results["bad"]})
    missing = [code for code in expected if code not in fired]
    unexpected = sorted({f.code for f in results["good"]})

    status = 0
    print("check_protos.sh --self-test")
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


def main():
    argument = sys.argv[1] if len(sys.argv) > 1 else ""
    if argument == "--self-test":
        return self_test()
    paths = read_paths()
    if argument == "--list":
        return list_protos(paths)
    return report(analyse(".", paths), len(paths))


if __name__ == "__main__":
    sys.exit(main())
