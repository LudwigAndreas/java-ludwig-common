## Purpose
A consumer of this platform gets whatever the publish step happened to attach, so the set of
artifacts a module publishes has to be a decision rather than an accident. This capability fixes that
set — jar, flattened POM, and sources and Javadoc under `-Pci` — states which build the documentation
is checked in, and requires every plugin taking part to carry a version.

## ADDED Requirements

### Requirement: A published library ships its jar, its flattened POM, its sources and its Javadoc
A module parented by the reactor root `common` with `packaging=jar` SHALL publish, under `-Pci`, its
jar, its flattened POM, a `-sources` jar and a `-javadoc` jar. A default build without `-Pci` SHALL
NOT build sources or Javadoc, so the local loop stays fast. A service SHALL publish neither, because
it is consumed as an image rather than as a dependency.

#### Scenario: A library is published
- **WHEN** `mvn -Pci -DskipTests deploy` runs for `web-core-spring-boot-starter`
- **THEN** the repository receives its jar, a POM with `${revision}` resolved to a literal, a
  `-sources` jar and a `-javadoc` jar

#### Scenario: A developer builds locally
- **WHEN** `mvn clean install` runs with no profile
- **THEN** no `-sources` or `-javadoc` jar is produced, and the build does not spend time rendering
  documentation nobody is about to read

#### Scenario: A service is published
- **WHEN** the same `-Pci` publish runs for `notification-service`
- **THEN** no `-javadoc` jar is attached, because the module is parented by `ludwig-service-parent`
  and the attachment is declared in the reactor root

### Requirement: Javadoc correctness is gated, Javadoc completeness is not
The Javadoc build SHALL fail on malformed documentation — a broken `@link`, an unknown tag, a
parameter that does not exist — and SHALL NOT fail on missing documentation. Missing Javadoc remains
the existing Checkstyle warning tier.

#### Scenario: A Javadoc reference does not resolve
- **WHEN** a class's Javadoc contains `{@link NoSuchType}` and `-Pci` is active
- **THEN** the build fails, naming the file and the unresolved reference

#### Scenario: A public method has no Javadoc at all
- **WHEN** a public method carries no Javadoc comment
- **THEN** `mvn -q validate` reports `MissingJavadocMethod` as a warning and the build passes, which
  is the existing behaviour and is deliberately unchanged here

### Requirement: Every plugin taking part in the build declares a version
No plugin SHALL be configured without a version. The check SHALL fail the build rather than warn,
because Maven's own response to an unpinned plugin is a warning that has been printed on every build
of this repository without anyone acting on it.

#### Scenario: A plugin is configured with no version
- **WHEN** a module's POM configures a plugin and declares neither a version nor a managed version it
  inherits
- **THEN** `mvn -q validate` fails on that module, naming the plugin

#### Scenario: The known unpinned plugin
- **WHEN** the reactor is built after this change
- **THEN** `build/architecture-rules/pom.xml` no longer triggers Maven's "problems were encountered
  while building the effective model … `maven-jar-plugin` is missing" warning, because the version is
  managed in the root POM
