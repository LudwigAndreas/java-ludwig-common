## MODIFIED Requirements

### Requirement: The version lives in exactly one place
The project version SHALL be expressed as `${revision}` in every POM and SHALL NOT be written as a
literal in any of them. `flatten-maven-plugin` resolves it on install and deploy.

What *sets* `${revision}` is the git history, computed by GitVersion and passed as
`-Drevision=<computed>` on the command line; see the `release-versioning` capability for the mapping
from history to version. `.mvn/maven.config` retains a `-Drevision` value as the fallback for a
checkout with no tags and for a build with no GitVersion available, and a command-line user property
beats it. It is therefore no longer the source of the released version and SHALL NOT be edited to cut
one.

#### Scenario: A module hard-codes its version
- **WHEN** a POM declares a literal version instead of `${revision}`
- **THEN** that is a defect: the module stops moving with the reactor, and a release produces two
  different versions from one build

#### Scenario: A release is cut
- **WHEN** `master` is tagged `v1.2.0` and the pipeline builds that commit with
  `mvn -Drevision=1.2.0 -Prelease -Pci deploy`, where the value of `-Drevision` came from GitVersion
  rather than from a person
- **THEN** `-Prelease` only *enforces* the release preconditions — no SNAPSHOT dependencies, a
  concrete revision, a changelog entry for that version in both locales — and does not itself set the
  version; the version came from the tag by way of `-Drevision`

#### Scenario: A local build with no version on the command line
- **WHEN** a developer runs `mvn clean install` with no `-Drevision`
- **THEN** the value in `.mvn/maven.config` applies, and the build produces a locally installable
  SNAPSHOT — which is what that file is now for, and the only thing it is for

#### Scenario: A change edits the version file to release
- **WHEN** a change edits `-Drevision` in `.mvn/maven.config` in order to publish a version
- **THEN** that is a defect against the `release-versioning` capability: the release is the tag, and a
  value in that file which disagrees with the history misleads the next local build
