# admin-authorization Specification

## Purpose
Defines how a starter offers administrative operations without naming a role, how the consuming service grants
those operations to its own roles, and the security, audit and tenancy contract every administrative endpoint on
the platform shares - for human administrators reaching it through an administrative UI and for administrative
service principals alike.
## Requirements
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

### Requirement: Administrative endpoints exist only where they can be secured

A module's administrative endpoints SHALL be registered only when `security-spring-boot-starter`'s method security
is active in the application. A module used without the security starter SHALL start and SHALL serve no
administrative endpoint.

#### Scenario: A starter is used in a service without the security starter
- **WHEN** a service depends on a module that declares administrative endpoints but not on
  `security-spring-boot-starter`
- **THEN** the application starts, and every `/admin/v1/` path answers as an unmapped path

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

### Requirement: A paged administrative listing over stored rows filters with OData, and only with OData
Every paged administrative listing over stored rows SHALL take its filter as an OData `$filter` expression over the
fields its area declares filterable, and SHALL take no other filter parameter; a path segment that names what is
listed (a task) is not a filter. The expression SHALL be applied together with every server-side rule (tenant
confinement, default time windows, path and object scope), so that a filter can only narrow what the caller could
already list. The listing SHALL keep its fixed order and its `cursor`/`size` paging, and SHALL refuse `$orderby`,
`$top`, `$skip` and `$count` with `400`. A field that is not declared filterable, and an expression the OData starter
rejects (syntax, depth, length), SHALL be refused with `400` using the OData starter's problem types. In a service
without the OData starter, a `$filter` SHALL be refused with `400`, and the listing SHALL still answer unfiltered.
The listing's own audit event SHALL record the filter's field paths and operators, never its values.

#### Scenario: A filter narrows a listing
- **WHEN** an operator lists outbox messages with `$filter=status eq 'DEAD_LETTER' and eventType eq 'OrderCreated'`
- **THEN** only dead-lettered `OrderCreated` messages are returned, newest first, with a `next` cursor when more exist

#### Scenario: A filter cannot widen confinement
- **WHEN** a `USER` administrator of tenant `acme` searches the audit trail with `$filter=actorSubject eq 'gary'`
  and gary's events belong to tenant `globex`
- **THEN** no event is returned

#### Scenario: Offset paging is refused
- **WHEN** a caller adds `$skip=40` to an administrative listing
- **THEN** the response is `400` and nothing is listed

#### Scenario: An unfilterable field is refused
- **WHEN** a caller filters outbox messages on `lastError`
- **THEN** the response is `400` naming the field as not filterable

#### Scenario: A former filter parameter has no effect
- **WHEN** a caller lists outbox messages with `?status=FAILED` and no `$filter`
- **THEN** the parameter is ignored and the listing is not narrowed by it

#### Scenario: The filter's values are not audited
- **WHEN** an auditor searches with `$filter=actorSubject eq 'alice'`
- **THEN** the search's audit event records the path `actorSubject` and operator `eq`, and not the value `alice`

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
