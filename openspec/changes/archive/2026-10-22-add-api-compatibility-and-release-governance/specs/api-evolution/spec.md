## Purpose
This platform's jars are consumed by services built and released on their own schedule, so what a
published module promises has to survive between releases. This capability defines which API
differences are permitted at which version bump, and the deprecation that has to precede every
removal, so that a consumer's build breaks here rather than their runtime breaking there.

## ADDED Requirements

### Requirement: Every published library is compared against its previous release
Each module parented by the reactor root `common` with `packaging=jar` SHALL be compared, at the
`verify` phase, against the newest release of the same coordinates published to
`ludwig.repo.releases`. The comparison SHALL be of the module's compiled API, not of its source
text. A service SHALL NOT be compared: it is deployed as an image and consumed by nobody as a
library.

#### Scenario: A public method disappears from a library
- **WHEN** a public method is removed from `audit-core` and the version being built is a patch or
  minor increment of the baseline
- **THEN** `mvn -pl :audit-core -am verify` fails, naming the removed method and the version bump
  that would be required to remove it

#### Scenario: A module has no previous release
- **WHEN** a newly added module is built and no release of its coordinates exists in
  `ludwig.repo.releases`
- **THEN** the build passes and logs that this release establishes the module's baseline, stating
  explicitly that no comparison was performed — a skip that reads as a passing check is the failure
  mode this wording exists to prevent

#### Scenario: The baseline cannot be resolved
- **WHEN** a release of the module's coordinates exists but cannot be fetched, because the
  repository is unreachable or the local repository has no copy and the build is offline
- **THEN** the build fails rather than treating an unresolvable baseline as an absent one. A local
  developer loop may pass `-Drevapi.skip=true` on the command line, and that hatch SHALL NOT appear
  in any POM, script or `Jenkinsfile`

#### Scenario: A service changes its own public API
- **WHEN** a public method is removed from `notification-service`
- **THEN** no API comparison runs, because `notification-service` is parented by
  `ludwig-service-parent` and the gate is declared in the reactor root

### Requirement: The permitted difference is decided by the version bump being attempted
The severity of the difference between a module and its baseline SHALL be checked against the
version increment the build is producing. A binary-incompatible difference SHALL require a major
increment; a source-incompatible but binary-compatible difference SHALL require at least a minor
increment; an additive difference MAY be a patch.

#### Scenario: A breaking change arrives with a patch increment
- **WHEN** the baseline is `1.2.0`, the computed version is `1.2.1`, and a public method's parameter
  type has changed
- **THEN** the build fails, stating both the difference and that `2.0.0` is the smallest version
  that permits it

#### Scenario: A breaking change arrives with a major increment
- **WHEN** the same difference is built with a computed version of `2.0.0`
- **THEN** the build passes, because the version increment is the permission

#### Scenario: A method is added to an interface consumers implement
- **WHEN** a method with no default implementation is added to a published interface such as
  `AuditSink`
- **THEN** the build fails below a major increment, because every existing implementation outside
  this repository stops compiling — the change is binary-compatible for callers and breaking for
  implementors, and the stricter of the two governs

#### Scenario: A new class or an overload is added
- **WHEN** a new public class, or an overload that leaves every existing signature intact, is added
- **THEN** a patch increment is sufficient and the build passes

### Requirement: Deprecation precedes removal, and states what to use instead
Every `@Deprecated` element in a published module SHALL declare `since` with the version in which
the deprecation was introduced and `forRemoval` explicitly, and SHALL carry a Javadoc `@deprecated`
tag naming the replacement API or stating that there is none. An element SHALL NOT be removed unless
the baseline release already carried `@Deprecated(forRemoval = true)` on it.

#### Scenario: A deprecation omits its version
- **WHEN** a declaration is annotated `@Deprecated` or `@Deprecated(forRemoval = true)` with no
  `since` member
- **THEN** `mvn -q validate` fails on that file, naming the rule and the declaration

#### Scenario: A deprecation omits its Javadoc tag
- **WHEN** a declaration is annotated `@Deprecated` and its Javadoc has no `@deprecated` tag
- **THEN** `mvn -q validate` fails, because a deprecation that does not say what to use instead
  moves the problem to whoever reads it

#### Scenario: An API is removed without ever having been deprecated
- **WHEN** a public method absent from the current source was present in the baseline **without**
  `@Deprecated(forRemoval = true)`, and the computed version is a major increment
- **THEN** the build still fails: a major increment permits removing something a consumer was warned
  about, and warning the consumer is the separate, prior step

#### Scenario: An API is deprecated and removed in the same release
- **WHEN** a method is annotated `@Deprecated(forRemoval = true)` and deleted in the same change
- **THEN** the build fails, because the baseline carries no annotation and the deletion is therefore
  indistinguishable from an undeclared removal

#### Scenario: A deprecated API is removed after a release that carried the warning
- **WHEN** the baseline release carries `@Deprecated(since = "1.2.0", forRemoval = true)` on a
  method, and a later build with a major increment removes it
- **THEN** the build passes

### Requirement: The removal window is recorded where it cannot be enforced
A deprecated API SHALL remain for at least one minor release before it is removed. This SHALL be
recorded as a comment at the point of the rule, stating that the check compares one build against
one baseline and therefore cannot see how many releases separate them.

#### Scenario: The rule is present with no check and no explanation
- **WHEN** the removal-window rule appears in a README or a spec with neither a mechanical check nor
  a comment at the point of the rule saying why none is possible
- **THEN** that is a defect against this repository's "encode every rule twice" convention,
  regardless of whether any build fails
