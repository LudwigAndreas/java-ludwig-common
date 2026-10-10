## ADDED Requirements

### Requirement: The client-implies-stub rule is an ArchUnit rule shipped by test-support-local

The rule that every `@LudwigRestClient` interface in a service with a local application has a stub
or an explicit pass-through SHALL be an ArchUnit rule, and SHALL be shipped by `test-support-local`
as a test base a service subclasses, not by `architecture-rules`. `architecture-rules` excludes
test classes from its import and has no in-repo dependencies, so it can see neither the stubs nor
the annotation; the tool is still ArchUnit, which keeps structure with one owner.

#### Scenario: A service enables the rule
- **WHEN** a service adds a subclass of the module's rule base under its `architecture` test
  directory
- **THEN** the rule runs at `test`, imports the service's main and test classes, and fails naming
  any client interface with no stub and no pass-through

#### Scenario: A service has no local application
- **WHEN** a service does not subclass the rule base
- **THEN** no rule runs, because a stub is only owed by a service that can be launched locally

#### Scenario: The rule is also needed where ArchUnit cannot see
- **WHEN** a rest client exists only as a configured name, with no annotated interface
- **THEN** the launcher's start-up check fails for it. That check is behavioural and is enforced
  by the launcher and its integration test, not by the triad

### Requirement: The test-scope-only rule for test-support artifacts is an enforcer rule

That no artifact whose id begins with `test-support` reaches a service's compile or runtime
classpath SHALL be failed by a Maven enforcer rule in `ludwig-service-parent`. It is a fact about
the dependency graph, which bytecode analysis and source-text analysis both cannot see.

#### Scenario: A service declares a test-support artifact at compile scope
- **WHEN** a service POM declares `test-support`, `test-support-security` or `test-support-local`
  without `<scope>test</scope>`
- **THEN** that service's build fails in the existing `enforce-platform-rules` execution, naming
  the artifact

#### Scenario: Both existing services are checked by the new rule
- **WHEN** the rule is added
- **THEN** `crud-service-example` and `notification-service` pass it unchanged, because both
  already declare these artifacts at test scope
