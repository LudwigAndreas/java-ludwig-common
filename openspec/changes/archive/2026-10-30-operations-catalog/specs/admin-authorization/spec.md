## REMOVED Requirements

### Requirement: A starter declares administrative operations, never roles
**Reason**: Operations of every surface are declared in one catalog with an `<area>.<object>.<verb>` id, a sensitivity and a surface.
**Migration**: see `operation-authorization`; configure `ludwig.security.roles` and declare `@RequiresOperation` with the operation ids of each area.

### Requirement: An area declares all three tiers
**Reason**: Tiers no longer exist; an area declares exactly the operations its endpoints perform, so there is no empty tier to declare.
**Migration**: see `operation-authorization`; configure `ludwig.security.roles` and declare `@RequiresOperation` with the operation ids of each area.

### Requirement: The consuming service grants operations to its own roles in configuration
**Reason**: Grants are part of the role-centric `ludwig.security.roles` table, which also carries row scope.
**Migration**: see `operation-authorization`; configure `ludwig.security.roles` and declare `@RequiresOperation` with the operation ids of each area.

### Requirement: Every gap in the mapping fails closed
**Reason**: Fail-closed validation covers every operation and every role entry, whatever its surface.
**Migration**: see `operation-authorization`; configure `ludwig.security.roles` and declare `@RequiresOperation` with the operation ids of each area.

### Requirement: Roles come from the authority resolver and the credential's attenuation still applies
**Reason**: Role resolution is the same for every operation, not only administrative ones.
**Migration**: see `operation-authorization`; configure `ludwig.security.roles` and declare `@RequiresOperation` with the operation ids of each area.

### Requirement: A privileged operation requires a stated reason
**Reason**: The reason applies to every `PRIVILEGED` operation on every surface, under the header `Operation-Reason`.
**Migration**: see `operation-authorization`; configure `ludwig.security.roles` and declare `@RequiresOperation` with the operation ids of each area.

### Requirement: A caller can discover the operations it holds
**Reason**: Discovery covers every surface at `GET /me/operations`.
**Migration**: see `operation-authorization`; configure `ludwig.security.roles` and declare `@RequiresOperation` with the operation ids of each area.

### Requirement: Every catalogued operation has a description
**Reason**: Every operation of the one catalog needs a description.
**Migration**: see `operation-authorization`; configure `ludwig.security.roles` and declare `@RequiresOperation` with the operation ids of each area.

### Requirement: An administrative operation can be scoped to named objects
**Reason**: Object scope is the `OBJECT` dimension of an operation's scope in `ludwig.security.roles`, not a pseudo-resource `admin.<area>` in the data-scope policies.
**Migration**: see `operation-authorization`; configure `ludwig.security.roles` and declare `@RequiresOperation` with the operation ids of each area.

## MODIFIED Requirements

### Requirement: The administrative surface is guarded before any handler, whatever filter chain the service runs

Every administrative endpoint SHALL be served under `/admin/v1/`. Every request under `/admin/` SHALL pass a guard
that runs before any handler and independently of which security filter chain the service uses - the platform's
own or one the service declared. That guard SHALL:

- answer an unauthenticated request with `401`;
- refuse a principal of type `PARTNER` with `403`;
- refuse a request whose credential is a personal access token with `403` and the same credential-not-permitted
  problem type the PAT management surface answers with, unless the deployment sets
  `ludwig.security.operations.allow-personal-access-tokens=true`.

A handler under `/admin/` that declares neither an `ADMIN` operation nor that it admits any authenticated caller
SHALL be refused with `403`. No entry of `ludwig.security.public-paths` SHALL match a path under `/admin/v1/`. `ludwig.admin.enabled=false` SHALL
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

### Requirement: Every administrative change is audited through the one sink

Every permitted invocation of an `ADMIN` operation of sensitivity `CHANGE` or `PRIVILEGED` SHALL record one `AuditEvent` through the
platform's `AuditSink`, carrying the actor (the caller's principal and principal type), the operation id, the
target object's identity, the reason when present, and the outcome (succeeded, refused by state, failed). Refused
authorization SHALL be recorded as the security starter already records access decisions. Whether a sink failure
fails the request SHALL be decided by the configured `AuditFailurePolicy`. A `READ` invocation SHALL NOT be audited
by this requirement; an area that must audit reads says so in its own capability.

#### Scenario: An operator retries a dead-lettered message
- **WHEN** a user holding `outbox.message.retry` retries a message with reason `INC-4411`
- **THEN** one audit event records the user's subject, principal type `USER`, operation `outbox.message.retry`, the
  message id, reason `INC-4411` and outcome succeeded, and it contains no part of the message payload

#### Scenario: An administrative service performs the same retry
- **WHEN** an administrative service principal performs the same retry without a reason
- **THEN** the audit event records the service's subject and principal type `SERVICE` and has no reason

#### Scenario: The operation is refused by the object's state
- **WHEN** the retried message is not in a retryable state and the endpoint answers `409`
- **THEN** the audit event records outcome refused-by-state

### Requirement: People can be required to arrive through a sanctioned acting party
When `ludwig.admin.human-callers.required-via` is non-empty, a request to the administrative surface by a `USER`
principal SHALL be refused with `403` unless its validated token names, as the immediate acting party, one of the
listed subjects. The refusal SHALL be audited as an administrative denial. Service, partner and system principals
SHALL NOT be affected. When the list is empty, no acting party SHALL be required.

#### Scenario: A person calls with a token from the administrative UI
- **WHEN** `required-via` is `[admin-console]` and alice calls `GET /admin/v1/outbox/messages` with a token whose
  `act.sub` is `admin-console` and she holds `outbox.message.read`
- **THEN** the response is `200`

#### Scenario: A person calls directly
- **WHEN** `required-via` is `[admin-console]` and alice calls the same endpoint with a token that has no `act`
  claim
- **THEN** the response is `403`, and the audit trail records the denial with alice as actor and no acting party

#### Scenario: Automation is unaffected
- **WHEN** `required-via` is `[admin-console]` and a service principal holding `outbox.message.read` calls the endpoint
  without an `act` claim
- **THEN** the response is `200`

### Requirement: One evaluation serves every transport
Admission of the caller (refusing partners, personal access tokens unless allowed, and people not through a required
acting party), authorization of an administrative operation, the reason requirement, the audit of denials and the
binding of the administrative invocation SHALL be performed by the same evaluation for HTTP and gRPC, so that a
grant, a refusal and an audit record mean the same on both.

#### Scenario: The same grant on both transports
- **WHEN** a role is granted `catalog.product.read` and a holder calls an HTTP endpoint and a gRPC method that both require it
- **THEN** both succeed, and a caller without it is refused on both (`403` and `PERMISSION_DENIED`), each refusal
  producing one denial audit event whose action is the refused method's name

#### Scenario: A partner on gRPC
- **WHEN** a partner holding a role granted an operation calls a gRPC method requiring it
- **THEN** the call fails with `PERMISSION_DENIED`, as the same partner is refused on the HTTP surface
