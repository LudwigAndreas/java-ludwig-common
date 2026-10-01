"""Scanner behind scripts/check_image_pins.sh - see that file for why this check exists.

Reads NUL-separated paths on stdin (``git ls-files -z``) and reports every container image
reference that is not pinned by ``@sha256:<64 hex>``.

Kept as Python rather than folded into the shell script because the interesting part is three
regular expressions and the rule about which line is even a candidate, and that is unreadable
written as ``grep -E`` pipelines - which is how a check nobody trusts gets disabled.
"""

import re
import sys

# A line is only examined if it carries one of these. Without the trigger, `FROM outbox_message t`
# in a README about a SQL query reads exactly like a Dockerfile directive, and a checker that
# cries wolf on prose is one people learn to ignore.
YAML_IMAGE = re.compile(r"(?:^|\s)image:\s*(?P<ref>\S+)")
DOCKERFILE_FROM = re.compile(r"^\s*FROM\s+(?P<ref>\S+)", re.IGNORECASE)
DOCKER_CLI = re.compile(r"docker\s+(?:run|pull|create)\b(?P<rest>.*)")

# A variable whose name ends in IMAGE, holding the reference. The Jenkinsfile's GITVERSION_IMAGE
# is the case this exists for: the `docker run` line a few lines below it interpolates the
# variable, so without this trigger the one image the pipeline pulls would be the one reference
# in the repository nobody checked - which is how the hole this script closes opened.
NAMED_IMAGE = re.compile(r"\b[A-Za-z0-9_]*IMAGE\s*[:=]+\s*['\"]?(?P<ref>[^'\"\s]+)", re.IGNORECASE)

# [registry[:port]/]path/name:tag, optionally @sha256:...
IMAGE_TOKEN = re.compile(
    r"^(?P<name>[a-z0-9][a-z0-9._-]*(?::\d+)?(?:/[a-z0-9][a-z0-9._-]*)*)"
    r"(?::(?P<tag>[A-Za-z0-9_][A-Za-z0-9._-]*))?"
    r"(?:@(?P<digest>sha256:[0-9a-f]{64}))?$"
)

DIGEST = re.compile(r"@sha256:[0-9a-f]{64}\b")

# The ONE accepted stand-in for a digest, and it is accepted because it is a template rather than
# a reference: the Kubernetes manifest ships with the digest deliberately absent so that a
# deployment cannot copy a stale one out of version control. It has to be spelled exactly, and it
# has to be greppable, so that "did anyone leave a placeholder in?" is one command.
PLACEHOLDER = "REPLACE_WITH_RELEASE_DIGEST"

# Words that appear where an image name would and never are one.
NOT_AN_IMAGE = {"scratch"}


def references(path, line):
    """Yield every image reference on one line of one file, without repeating one."""
    seen = set()

    def emit(ref):
        ref = ref.strip("\"'")
        # A shell or Groovy interpolation is not a reference - it is a pointer to one, and the
        # value it points at is checked wherever it is written down. crud-service-example's README
        # reads its postgres image out of test-support's images.properties for exactly that
        # reason, so that the digest is never copied into prose that can drift.
        if "$" in ref:
            return None
        if ref and ref not in seen:
            seen.add(ref)
            return ref
        return None

    is_dockerfile = path.rsplit("/", 1)[-1].startswith("Dockerfile")

    for pattern in (YAML_IMAGE, NAMED_IMAGE):
        match = pattern.search(line)
        if match:
            ref = emit(match.group("ref"))
            if ref:
                yield ref

    if is_dockerfile:
        match = DOCKERFILE_FROM.match(line)
        if match:
            ref = emit(match.group("ref"))
            if ref:
                yield ref

    match = DOCKER_CLI.search(line)
    if match:
        # The image is the last bare word on a `docker run` line: everything before it is flags
        # and their values, and everything after it is the container's own command.
        for token in reversed(match.group("rest").split()):
            token = token.strip("\"'`\\")
            if not token or token.startswith("-") or "=" in token:
                continue
            if IMAGE_TOKEN.match(token) and ("/" in token or ":" in token):
                ref = emit(token)
                if ref:
                    yield ref
            break


def unpinned(ref):
    """True when this reference must carry a digest and does not."""
    if ref in NOT_AN_IMAGE or ref.startswith("$") or ref.startswith("${"):
        return False
    if PLACEHOLDER in ref:
        return False
    match = IMAGE_TOKEN.match(ref)
    if not match:
        return False
    if match.group("digest"):
        return False
    # A bare `name@sha256:...` with no tag is pinned; IMAGE_TOKEN already matched the digest above,
    # so anything reaching here has a tag, a name, or both, and no digest.
    return not DIGEST.search(ref)


def main():
    listing = sys.argv[1:2] == ["--list"]
    paths = [p for p in sys.stdin.read().split("\0") if p]

    found = []
    problems = []
    for path in paths:
        try:
            with open(path, encoding="utf-8") as handle:
                lines = handle.readlines()
        except (OSError, UnicodeDecodeError):
            continue
        for number, line in enumerate(lines, start=1):
            for ref in references(path, line):
                found.append((path, number, ref))
                if unpinned(ref):
                    problems.append((path, number, ref))

    if listing:
        for path, number, ref in found:
            state = "UNPINNED" if unpinned(ref) else "pinned  "
            print("%s  %s:%d  %s" % (state, path, number, ref))
        return 0

    if problems:
        print("check_image_pins.sh: FAILED. These image references carry no sha256 digest:")
        for path, number, ref in problems:
            print("  %s:%d  %s" % (path, number, ref))
        print("")
        print("  A tag can be re-pointed at different content, so a tag-only reference makes a")
        print("  build reproducible until somebody else's release. Add @sha256:<digest>, the way")
        print("  sources/test-support/src/main/resources/images.properties already does.")
        return 1

    print("check_image_pins.sh: %d image reference(s), all pinned by digest." % len(found))
    return 0


if __name__ == "__main__":
    sys.exit(main())
