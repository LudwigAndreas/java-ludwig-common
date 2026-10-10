## Purpose

Defines the build provenance baked into every service artifact — which commit, branch, build time and CI
build it came from — so that a running process can be traced back to the source it was built from without
registry access, and so that one artifact promoted between environments keeps reporting the build it was
built from rather than where it landed.

## ADDED Requirements

### Requirement: Build provenance is baked into the service artifact

Every artifact produced by a module parented by `ludwig-service-parent` SHALL contain, as packaged
resources, the git provenance of the checkout it was built from and the Maven coordinates and build time
of the build that produced it.

The git provenance SHALL include the full commit id, the abbreviated commit id, the branch name, the
commit timestamp, and an indicator of whether the working tree contained uncommitted changes.

A build with no git checkout available — a source tarball, or a scratch project — SHALL still succeed,
and the git provenance resource SHALL be absent rather than present with placeholder values.

#### Scenario: Provenance resources are packaged

- **WHEN** a service module is packaged from a git checkout
- **THEN** the artifact contains a git provenance resource carrying the commit id, abbreviated commit id,
  branch, commit timestamp and dirty indicator
- **AND** the artifact contains a build-info resource carrying the group id, artifact id, name, version
  and build time

#### Scenario: Build outside a git checkout still succeeds

- **WHEN** a service module is packaged from a source tree with no `.git` directory
- **THEN** the build succeeds
- **AND** the git provenance resource is absent from the artifact
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

#### Scenario: No runtime repository access

- **WHEN** the platform's architecture rules are evaluated over any module
- **THEN** the build fails if a production source invokes a git process or reads a `.git` path

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

#### Scenario: Build configuration stops emitting provenance

- **WHEN** the service parent's build configuration is edited so that a provenance resource is no longer
  generated
- **THEN** the verification gate fails and names the resource that is no longer produced

#### Scenario: A packaged service carries its provenance

- **WHEN** the reference service's integration tests run against its packaged classpath
- **THEN** both provenance resources are found
- **AND** the git provenance carries a non-empty commit id

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
