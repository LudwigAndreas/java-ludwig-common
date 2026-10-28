## ADDED Requirements

### Requirement: The administrative surface's structure is owned by ArchUnit as one rule group

The shared architecture rules SHALL include an administrative rule group, on by default and vacuous for a module
with no administrative endpoint, that fails the build when:

- a handler method whose request mapping begins with `/admin/` carries neither the platform's administrative
  operation annotation nor its "any admitted caller" marker;
- a class carrying either annotation, or any of its handler methods, also carries `@PreAuthorize`,
  `@PostAuthorize`, `@Secured` or `@RolesAllowed`, because two gates on one endpoint are two places to change it;
- a handler method carrying either annotation is mapped outside `/admin/v1/`;
- an administrative controller depends directly on a repository.

#### Scenario: An administrative handler without the annotation
- **WHEN** a controller maps `GET /admin/v1/outbox/messages` and the method carries neither administrative
  annotation
- **THEN** the administrative rule group fails and names the method

#### Scenario: An administrative handler that also names a role
- **WHEN** a method carries the administrative operation annotation and `@PreAuthorize("hasRole('ADMIN')")`
- **THEN** the administrative rule group fails and names the method and the second annotation

#### Scenario: A module has no administrative endpoint
- **WHEN** the rule group analyses a module with no handler under `/admin/`
- **THEN** it passes

#### Scenario: Each rule is shown to fail
- **WHEN** the architecture-rules module's own tests run
- **THEN** each administrative rule is asserted to fire on a deliberately violating fixture

### Requirement: The administrative surface's unmechanisable rules are recorded, not dropped

Three rules of the administrative surface cannot be checked in bytecode or source text and SHALL be enforced at
startup or by tests instead, with the reason recorded in a comment at the point of the rule:

- that every operation id named by an annotation is catalogued, and every grant key is catalogued - the catalog is
  assembled from beans at runtime, so it is a startup check;
- that the tier a module assigns to an operation matches what the operation does - a judgement, recorded in the
  module's README table of operations and reviewed with it;
- that a module's administrative endpoints are absent without the security starter - a property of the assembled
  context, so it is an auto-configuration test in each module that declares endpoints.

#### Scenario: The startup checks have their own tests
- **WHEN** `security-spring-boot-starter`'s tests run
- **THEN** an uncatalogued annotation id and an uncatalogued grant key each make a test context fail to start
