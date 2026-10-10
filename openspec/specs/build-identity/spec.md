# Build identity

## Purpose

Defines the build provenance baked into every service artifact — which commit, branch, build time and CI
build it came from — so that a running process can be traced back to the source it was built from without
registry access, and so that one artifact promoted between environments keeps reporting the build it was
built from rather than where it landed.

## Requirements

### Requirement: Build provenance is baked into the service artifact

Every artifact produced by a module parented by `ludwig-service-parent` SHALL contain, as packaged
resources, the git provenance of the checkout it was built from and the Maven coordinates and build time
of the build that produced it.

The git provenance SHALL include the full commit id, the abbreviated commit id, the branch name, the
commit timestamp, and an indicator of whether the working tree contained uncommitted changes.

The git provenance resource SHALL carry those five fields and nothing else. Left unfiltered, the
generating plugin also writes the committer's name and e-mail address, the build host name and the remote
URL, and the management endpoint that serves this resource would then serve those too.

The build time SHALL be recorded at day precision. A service image is built so that one commit yields one
digest, and a full-precision build time in a packaged resource would give every build a different one.
Day precision keeps the digest stable for two builds of one commit on the same UTC day, and not across
midnight; that limit is accepted rather than hidden.

A build with no git checkout available — a source tarball, or a scratch project — SHALL still succeed,
and the git provenance resource SHALL be absent rather than present with placeholder values or present
and empty. An empty resource is not harmless: its mere existence makes the service report a blank
provenance section, which reads as "recorded, and blank".

#### Scenario: Provenance resources are packaged

- **WHEN** a service module is packaged from a git checkout
- **THEN** the artifact contains a git provenance resource carrying the commit id, abbreviated commit id,
  branch, commit timestamp and dirty indicator
- **AND** the artifact contains a build-info resource carrying the group id, artifact id, name, version
  and build time

#### Scenario: Nothing about who built it or where is packaged

- **WHEN** a service module is packaged from a git checkout
- **THEN** the git provenance resource contains exactly the commit id, abbreviated commit id, branch,
  commit timestamp and dirty indicator
- **AND** it contains no committer name, e-mail address, build host name or remote URL

#### Scenario: Two builds of one commit on one day agree on the build time

- **WHEN** the same commit is packaged twice on the same UTC day
- **THEN** both build-info resources carry the same build time, at midnight UTC of that day

#### Scenario: Build outside a git checkout still succeeds

- **WHEN** a service module is packaged from a source tree with no `.git` directory
- **THEN** the build succeeds
- **AND** the git provenance resource is absent from the artifact, not present with only a header
- **AND** no provenance field is populated with a placeholder such as `unknown`

#### Scenario: A dirty tree is recorded, not rejected

- **WHEN** a service module is packaged from a checkout with uncommitted changes
- **THEN** the build succeeds
- **AND** the git provenance resource records that the working tree was dirty

### Requirement: Provenance is obtained at build time, never at runtime

Build provenance SHALL be read only from the resources packaged into the artifact. No module SHALL invoke
`git`, read a `.git` directory, or otherwise inspect the source repository from a running process.

This is what makes the provenance a property of the artifact rather than of its surroundings: a running
container has no checkout to inspect, and a process that found one would be reporting the machine it runs
on instead of the build it came from.

#### Scenario: Promotion between environments preserves identity

- **WHEN** one artifact is deployed to staging and the same artifact is later promoted to production
- **THEN** both deployments report the identical commit id, branch, build timestamp and CI build number

The mechanical check for this is necessarily broader in one direction and narrower in another, and both
are stated here so that neither is mistaken for coverage. Which program a launched process runs, and
which path a file API opens, are string constants, and bytecode analysis cannot read a string constant's
value. So the architecture rules report **every** operating-system process launch and every dependency on
an in-process git library, with legitimate launches exempted by type name in the rule itself; and opening
a `.git` path through ordinary file APIs is **not** checked by any tool. That last case remains a review
question, and this requirement is what a review points at.

#### Scenario: Launching a process is reported

- **WHEN** the platform's architecture rules are evaluated over a module whose production source launches
  an operating-system process, or depends on an in-process git library
- **THEN** the build fails and names the offending type
- **AND** a type named in the rule's own exemption list is not reported

#### Scenario: Reading a process's own runtime properties is not reported

- **WHEN** a production source reads the processor count or the heap size from the runtime
- **THEN** the architecture rules do not report it

### Requirement: Every provenance field is independently optional

Each provenance field SHALL be resolved independently, and an unavailable field SHALL be reported as
absent rather than as a placeholder value.

An absent field is visibly absent to whatever consumes it. A literal `unknown` looks like a real value,
and silently becomes a group that several unrelated builds share — the same reasoning that already
governs the platform's service identity attributes.

#### Scenario: CI build number is absent on a local build

- **WHEN** a developer builds a service on a workstation with no CI build number supplied
- **THEN** the CI build number is absent from the resolved build identity
- **AND** no field reports a placeholder or a fabricated number

#### Scenario: CI build number is present when supplied

- **WHEN** a build is invoked with a CI build number supplied explicitly as a build parameter
- **THEN** that value appears in the packaged build-info resource
- **AND** it appears in the resolved build identity at runtime

### Requirement: Build provenance is exposed separately from telemetry grouping identity

Build provenance SHALL NOT be added to the existing service identity that supplies Micrometer common tags
and OpenTelemetry resource attributes.

Two reasons, both load-bearing. The service identity is a published record whose components are part of
the module's API, so widening it is a breaking change under the platform's API compatibility gate. More
importantly, branch name, build timestamp and dirty indicator as metric tags multiply every series in the
registry by values that change independently of a release — which is the standard way a metrics bill grows
after a change described as harmless.

#### Scenario: Provenance does not become a metric tag

- **WHEN** the common metric tags are resolved for a service
- **THEN** they contain the service, namespace, version and environment as before
- **AND** they contain no branch, build timestamp, dirty indicator or CI build number

#### Scenario: Service identity remains API-compatible

- **WHEN** the API compatibility check runs against the published baseline
- **THEN** the service identity record reports no breaking difference

### Requirement: The provenance plumbing is verified mechanically

The build SHALL fail if the build configuration stops producing the provenance resources, and the test
suite SHALL fail if the resources are absent from a packaged service or carry no commit id.

This requirement exists because the failure it guards against has already happened: the service parent POM
documented that it wrote a git provenance file into the jar, and it did not, for as long as that comment
had no check beside it.

The configuration check SHALL read the service parent's own build configuration and not anything it
inherits. The git provenance resource was, for a time, generated only because an inherited parent
happened to switch it on - a setting that leaves with an upgrade of that parent and takes no line of this
repository with it.

#### Scenario: Build configuration stops emitting provenance

- **WHEN** the service parent's build configuration is edited so that a provenance resource is no longer
  generated, is written where it is not packaged, no longer carries one of the five git fields, or is no
  longer removed when it is empty
- **THEN** the verification gate fails and names the resource that is no longer produced

#### Scenario: A packaged service carries its provenance

- **WHEN** the reference service's integration tests run against its packaged classpath
- **THEN** both provenance resources are found
- **AND** the git provenance carries a non-empty commit id
- **AND** the test fails, rather than being skipped, when the git provenance resource is absent - so the
  reference service's integration tests cannot pass in a build with no git checkout

### Requirement: No release identifier distinct from the version

The platform SHALL NOT define a release identifier separate from the artifact version.

The version is computed by GitVersion from the git history and passed into the build, so a release is a
tag and the tag's version already identifies the release. A second field restating it is one more value
that can disagree with the first. This requirement is recorded so the omission reads as a decision rather
than an oversight, and is not re-added later.

#### Scenario: Provenance carries no separate release identifier

- **WHEN** the resolved build identity is inspected
- **THEN** it exposes the artifact version and the commit provenance
- **AND** it exposes no additional field presented as a release identifier
