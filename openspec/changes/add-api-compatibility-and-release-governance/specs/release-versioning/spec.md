## Purpose
A version that a human types is a version a human can get wrong, and every module in this reactor is
published under one and the same version. This capability makes that version a function of the git
history —
computed by GitVersion, expressed through the existing `${revision}` placeholder — so that a release
is a tag, a snapshot is a branch build, and neither is a file somebody edited.

## ADDED Requirements

### Requirement: The version is computed from the git history
The project version SHALL be computed by GitVersion from the repository's git history and passed to
Maven as `-Drevision=<computed>` on the command line. A build on the default publishing branch SHALL
produce the release version the history implies; a build on any other branch SHALL produce
`<MajorMinorPatch>-SNAPSHOT`, so that Maven's release/snapshot repository split continues to decide
where an artifact lands.

#### Scenario: A tagged build on the publishing branch
- **WHEN** `master` is at the tag `v1.2.0` and the pipeline builds it
- **THEN** every artifact in the reactor is version `1.2.0`, and `deploy` publishes to
  `ludwig.repo.releases`

#### Scenario: A build on a development branch
- **WHEN** `develop` is built some commits after the tag `v1.2.0`
- **THEN** every artifact is `1.3.0-SNAPSHOT` — a Maven snapshot, not a pre-release label such as
  `1.3.0-alpha.4`, because a non-SNAPSHOT version would be published to the releases repository as an
  immutable artifact and would satisfy `requireReleaseDeps` while being an untested branch build

#### Scenario: The checkout has no history
- **WHEN** the pipeline's checkout is shallow, or tags were not fetched, so GitVersion cannot see the
  tag it needs
- **THEN** the version stage fails and the build stops, rather than continuing with a version derived
  from an incomplete history

#### Scenario: A developer builds locally
- **WHEN** a developer runs `mvn clean install` with no `-Drevision` on the command line
- **THEN** the value in `.mvn/maven.config` applies as the fallback and the build succeeds offline
  with no GitVersion available; a command-line `-Drevision` overrides it, which is the precedence CI
  relies on

#### Scenario: The version tool is referenced by tag alone
- **WHEN** the pipeline references the GitVersion container image without a `sha256` digest
- **THEN** that is a defect against the `container-image-pinning` capability, which covers every
  image reference in this repository and needs no exception for a build tool

### Requirement: A release is cut by tagging, not by editing a file
`.mvn/maven.config` SHALL NOT be edited to perform a release. A release SHALL be a tag on the
publishing branch, and `-Prelease` SHALL continue to enforce its preconditions — a concrete version
and no SNAPSHOT dependencies — rather than setting the version.

#### Scenario: A release is attempted from an untagged commit
- **WHEN** `-Prelease` is used on a commit that GitVersion resolves to a SNAPSHOT version
- **THEN** `requireReleaseVersion` fails the build, which is the same protection as before and is now
  reached without anyone having had to remember a command-line argument

#### Scenario: A change edits the version file
- **WHEN** a change edits the `-Drevision` value in `.mvn/maven.config` for any reason other than
  moving the tag-less fallback forward after a release
- **THEN** that is a defect: the file is no longer part of the release procedure, and a value in it
  that disagrees with the history is a trap for the next local build

### Requirement: Every release carries a changelog entry in both locales
`CHANGELOG.md` and `CHANGELOG.ru.md` SHALL exist at the repository root, hold one entry per released
version for the whole reactor, and carry an `Unreleased` section between releases. A release build
SHALL fail unless both files carry a heading for the version being released.

#### Scenario: A release has no changelog entry
- **WHEN** a build with `-Prelease` produces version `1.2.0` and `CHANGELOG.md` has no heading for
  `1.2.0`
- **THEN** the build fails at `validate`, before anything is compiled or published

#### Scenario: Only one locale was updated
- **WHEN** `CHANGELOG.md` carries the `1.2.0` heading and `CHANGELOG.ru.md` does not
- **THEN** the build fails naming the locale that is missing, on the same rule this repository
  already applies to `README.md` and `README.ru.md`

#### Scenario: A snapshot build
- **WHEN** a branch build produces `1.3.0-SNAPSHOT` without `-Prelease`
- **THEN** no changelog heading is required, because the version is not one a consumer can pin to

### Requirement: The computed version is recoverable from the artifact
Every published jar SHALL carry the version and the commit it was built from in its manifest, and
every image SHALL carry them as labels, so that a version computed rather than written can still be
traced back to a commit.

#### Scenario: An operator has only the jar
- **WHEN** a consumer inspects a published jar's `MANIFEST.MF`
- **THEN** it names the version and the commit id, and the commit id is the one the version was
  computed from
