## Purpose

Defines how a starter offers administrative operations without naming a role, how the consuming service grants
those operations to its own roles, and the security, audit and tenancy contract every administrative endpoint on
the platform shares - for human administrators reaching it through an administrative UI and for administrative
service principals alike.

## ADDED Requirements

### Requirement: A starter declares administrative operations, never roles

A module that offers administrative endpoints SHALL declare each operation it offers in the administrative
operation catalog, with an id of the form `<area>.<tier>`, where `<area>` is a lower-case word naming the module's
administrative area and `<tier>` is exactly one of `read`, `operate` or `privileged`:

- `read` - observes state and changes nothing;
- `operate` - changes state in a way that is safe to repeat and cannot put this service out of agreement with an
  external system or change who may access what;
- `privileged` - changes who may access what, or can put this service out of agreement with an external system,
  or discards work irrecoverably.

A module SHALL NOT name a role, an authority string or a token claim in the authorization of an administrative
endpoint.

#### Scenario: The catalog lists every declared operation
- **WHEN** a service runs with two starters that each declare their operations
- **THEN** the catalog contains the union of both declarations, each operation once, with its area and tier

#### Scenario: An operation id outside the grammar is refused
- **WHEN** a module declares an operation whose id is not `<area>.<tier>` with one of the three tiers
- **THEN** the application fails to start, naming the id

#### Scenario: Two modules declare the same operation id
- **WHEN** two declarations carry the same id
- **THEN** the application fails to start, naming the id and both declaring modules

### Requirement: An area declares all three tiers

A module that declares any operation of an area SHALL declare that area's `read`, `operate` and `privileged`
operations, including a tier that guards no endpoint yet, so that a service's grant block for an area is the same
three lines in every release and a later release adding an endpoint to an empty tier does not make an existing
grant block fail startup.

#### Scenario: An area with no privileged endpoint
- **WHEN** a module declares `messaging.read` and `messaging.operate` but not `messaging.privileged`
- **THEN** the application fails to start, naming the area and the missing tier

#### Scenario: Granting an empty tier
- **WHEN** a service grants `messaging.privileged` and no endpoint is guarded by it
- **THEN** the application starts, and the grant has no effect until an endpoint uses it

### Requirement: The consuming service grants operations to its own roles in configuration

A service SHALL grant administrative operations under `ludwig.admin.grants`, a map from operation id to the list
of the service's roles that hold it. A role SHALL be accepted with or without the `ROLE_` prefix and matched in its
prefixed form. A caller SHALL hold an operation if and only if at least one of its resolved roles is listed for that
operation. There SHALL be no wildcard, no implication between tiers, and no default grant: holding
`outbox.privileged` does not confer `outbox.read`.

#### Scenario: Notification grants the outbox operations to its administrator role
- **WHEN** notification-service configures `outbox.read: [NOTIFICATION_ADMIN, NOTIFICATION_SUPPORT]` and
  `outbox.operate: [NOTIFICATION_ADMIN]`, and a caller holding only `ROLE_NOTIFICATION_SUPPORT` invokes an
  `outbox.operate` endpoint
- **THEN** the response is `403`, and the same caller's `outbox.read` request succeeds

#### Scenario: A role written without its prefix still matches
- **WHEN** the grant lists `NOTIFICATION_ADMIN` and the caller's resolved roles contain `ROLE_NOTIFICATION_ADMIN`
- **THEN** the caller holds the operation

#### Scenario: A higher tier does not imply a lower one
- **WHEN** a caller's role is granted `outbox.privileged` and nothing else
- **THEN** an `outbox.read` request is refused with `403`

#### Scenario: Another service uses a different role for the same operation
- **WHEN** crud-service-example grants `outbox.operate: [CATALOG_ADMIN]`
- **THEN** a caller holding `ROLE_CATALOG_ADMIN` may invoke it there, with no change to the outbox starter

### Requirement: Every gap in the mapping fails closed

An operation with no grant SHALL be refused for every caller. A grant whose key is not a catalogued operation id,
or whose role list is empty or contains a blank entry, SHALL fail startup. An administrative endpoint that names an
operation id absent from the catalog SHALL fail startup. Startup SHALL log, once, the catalogued operations that no
role holds.

#### Scenario: A misspelled operation id in configuration
- **WHEN** the service configures `ludwig.admin.grants.outbox.opreate`
- **THEN** the application fails to start, naming `outbox.opreate` and listing the catalogued ids of area `outbox`

#### Scenario: An operation nobody was granted
- **WHEN** `outbox.privileged` is catalogued and absent from `ludwig.admin.grants`
- **THEN** every request to an `outbox.privileged` endpoint is `403`, and the startup log names `outbox.privileged`
  among the ungranted operations

#### Scenario: An endpoint names an operation the catalog does not contain
- **WHEN** an administrative endpoint is annotated with an id no module declared
- **THEN** the application fails to start, naming the endpoint and the id

### Requirement: Roles come from the authority resolver and the credential's attenuation still applies

The roles an administrative grant is evaluated against SHALL be the caller's roles as resolved by the platform's
authority resolution - the identity projection for users, `ludwig.identity.service-roles` for service principals -
after the personal-access-token intersection. A role carried as a claim in the token SHALL NOT be considered.

#### Scenario: A token asserts a role the projection does not hold
- **WHEN** a user's token carries a `roles` claim containing `NOTIFICATION_ADMIN` and the projection holds no such
  role for the subject
- **THEN** every operation granted only to `NOTIFICATION_ADMIN` is refused with `403`

#### Scenario: A revoked role stops working within the authority cache window
- **WHEN** `NOTIFICATION_ADMIN` is removed from a user in the directory and the projection commits the change
- **THEN** the user's administrative requests are refused no later than the security authority cache TTL after that
  commit, without the user's token changing

#### Scenario: An administrative microservice is granted through its workload identity
- **WHEN** `ludwig.identity.service-roles` maps an administrative service's workload identity to
  `NOTIFICATION_ADMIN` and that service calls an `outbox.operate` endpoint with its own credential
- **THEN** the request is permitted

### Requirement: The administrative surface is guarded before any handler, whatever filter chain the service runs

Every administrative endpoint SHALL be served under `/admin/v1/`. Every request under `/admin/` SHALL pass a guard
that runs before any handler and independently of which security filter chain the service uses - the platform's
own or one the service declared. That guard SHALL:

- answer an unauthenticated request with `401`;
- refuse a principal of type `PARTNER` with `403`;
- refuse a request whose credential is a personal access token with `403` and the same credential-not-permitted
  problem type the PAT management surface answers with, unless the deployment sets
  `ludwig.admin.allow-personal-access-tokens=true`.

A handler under `/admin/` that declares neither an operation nor that it admits any authenticated caller SHALL be
refused with `403`. No entry of `ludwig.security.public-paths` SHALL match a path under `/admin/v1/`. `ludwig.admin.enabled=false` SHALL
remove every administrative endpoint and the guard.

#### Scenario: An unauthenticated request
- **WHEN** a request without a credential reaches `/admin/v1/outbox/messages`
- **THEN** the response is `401`

#### Scenario: The service declares its own filter chain
- **WHEN** a service replaces the platform's filter chain with its own that permits `/admin/**` anonymously
- **THEN** an unauthenticated administrative request is still answered `401`, and no handler runs

#### Scenario: A partner reaches the surface
- **WHEN** a partner authenticated by client certificate calls any `/admin/v1/` path, whatever roles it holds
- **THEN** the response is `403`

#### Scenario: A PAT-credentialed caller reaches the surface
- **WHEN** a request authenticated by a PAT-derived assertion calls an administrative endpoint whose operation its
  owner holds
- **THEN** the response is `403` with the same problem `type` the PAT management surface answers a PAT caller
  with, not `401`

#### Scenario: A public-path entry tries to open the surface
- **WHEN** `ludwig.security.public-paths` contains `/admin/**`
- **THEN** the application fails to start, naming the entry

#### Scenario: The surface is switched off
- **WHEN** `ludwig.admin.enabled=false`
- **THEN** every `/admin/v1/` path answers as an unmapped path does in that service

### Requirement: Administrative endpoints exist only where they can be secured

A module's administrative endpoints SHALL be registered only when `security-spring-boot-starter`'s method security
is active in the application. A module used without the security starter SHALL start and SHALL serve no
administrative endpoint.

#### Scenario: A starter is used in a service without the security starter
- **WHEN** a service depends on a module that declares administrative endpoints but not on
  `security-spring-boot-starter`
- **THEN** the application starts, and every `/admin/v1/` path answers as an unmapped path

### Requirement: A privileged operation requires a stated reason

A request to a `privileged` endpoint SHALL carry an `Admin-Reason` header whose value is between 1 and 500
characters after trimming. A request without one SHALL be refused with `400` and a problem type distinct from a
validation failure of the body, before the operation runs. On `read` and `operate` endpoints the header SHALL be
optional and, when present, recorded.

#### Scenario: A privileged request without a reason
- **WHEN** a caller holding `reconciliation.privileged` calls a privileged endpoint without `Admin-Reason`
- **THEN** the response is `400` with the admin-reason-required problem type and the operation has no effect

#### Scenario: Authorization is decided before the reason is checked
- **WHEN** a caller not holding the operation calls the same endpoint without `Admin-Reason`
- **THEN** the response is `403`, so that the reason requirement does not reveal what a caller could do

### Requirement: Every administrative change is audited through the one sink

Every permitted invocation of an `operate` or `privileged` endpoint SHALL record one `AuditEvent` through the
platform's `AuditSink`, carrying the actor (the caller's principal and principal type), the operation id, the
target object's identity, the reason when present, and the outcome (succeeded, refused by state, failed). Refused
authorization SHALL be recorded as the security starter already records access decisions. Whether a sink failure
fails the request SHALL be decided by the configured `AuditFailurePolicy`. A `read` invocation SHALL NOT be audited
by this requirement; an area that must audit reads says so in its own capability.

#### Scenario: An operator retries a dead-lettered message
- **WHEN** a user holding `outbox.operate` retries a message with reason `INC-4411`
- **THEN** one audit event records the user's subject, principal type `USER`, operation `outbox.operate`, the
  message id, reason `INC-4411` and outcome succeeded, and it contains no part of the message payload

#### Scenario: An administrative service performs the same retry
- **WHEN** an administrative service principal performs the same retry without a reason
- **THEN** the audit event records the service's subject and principal type `SERVICE` and has no reason

#### Scenario: The operation is refused by the object's state
- **WHEN** the retried message is not in a retryable state and the endpoint answers `409`
- **THEN** the audit event records outcome refused-by-state

### Requirement: A human caller is confined to their tenant on tenant-carrying resources

When the service is multi-tenant (a tenant claim is configured), an administrative endpoint over resources that
carry a tenant SHALL confine a `USER` caller to resources of the caller's own tenant, answering `404` for a
resource of another tenant and omitting such resources from listings. A `SERVICE` principal SHALL NOT be confined.
Resources that carry no tenant (an outbox row, a run lock, a reconciliation task) SHALL NOT be confined. Each area's
capability states which of its resources carry a tenant.

#### Scenario: An administrator of one tenant looks up another tenant's resource
- **WHEN** a user of tenant `acme` holding the read operation requests a resource belonging to tenant `globex`
- **THEN** the response is `404`, the same as for a resource that does not exist

#### Scenario: An administrative service is not confined
- **WHEN** a service principal holding the same operation makes the same request
- **THEN** the resource is returned

### Requirement: A caller can discover the operations it holds

`GET /admin/v1/me/operations` SHALL return the operations the caller holds, each with its id, area, tier and a
localized description, and SHALL NOT list operations the caller does not hold. Any caller the administrative chain
admits may call it.

#### Scenario: An administrative UI decides which controls to show
- **WHEN** a user holding only `ROLE_NOTIFICATION_SUPPORT` calls it in notification-service
- **THEN** the response lists `outbox.read` and not `outbox.operate`, with descriptions in the caller's locale

#### Scenario: A caller holding nothing
- **WHEN** an authenticated user with no granted operation calls it
- **THEN** the response is `200` with an empty list
