# identity-admin Specification

## Purpose
Gives administrators and administrative services a supported way to manage the identity projection's
administrator-owned tables - the partner registry and data-scope grants - and to see what a subject is entitled to,
with every change recorded in the audit trail rather than only stamped on the row.
## Requirements
### Requirement: The identity area declares three operations

`identity-projection-spring-boot-starter` SHALL declare `identity.read`, `identity.operate` and
`identity.privileged`. Partner and grant changes SHALL be `identity.privileged`, because each changes who may access
what. `identity.operate` SHALL be declared and SHALL guard no endpoint in this release, so that a service's grant
block can name it without failing startup when a later release adds one.

#### Scenario: Granting a data scope requires the privileged tier
- **WHEN** a caller holding `identity.read` and `identity.operate` but not `identity.privileged` posts a grant
- **THEN** the response is `403` and no grant row is written

### Requirement: The partner registry is readable and changeable over the surface

The area SHALL serve:

- `GET /admin/v1/identity/partners` and `GET /admin/v1/identity/partners/{code}` (`identity.read`) - code, display
  name, status, certificate identifiers, roles;
- `POST /admin/v1/identity/partners` (`identity.privileged`) - onboard; `409` if the code exists;
- `PUT /admin/v1/identity/partners/{code}` (`identity.privileged`) - replace display name, certificate identifiers
  and roles, with optimistic concurrency through `If-Match`; `412` on a stale version;
- `POST /admin/v1/identity/partners/{code}/suspend` and `.../activate` (`identity.privileged`).

A suspended partner SHALL be refused authentication no later than the security authority cache TTL after the
change commits.

#### Scenario: A partner is suspended
- **WHEN** an administrator suspends partner `acme-logistics` with reason `contract ended`
- **THEN** the response is `200` with status `SUSPENDED`, and one audit event of category `admin` records the
  actor, operation `identity.privileged`, the partner code, the reason and outcome succeeded, in the same transaction
  as the change

#### Scenario: Two administrators edit the same partner
- **WHEN** the second `PUT` carries the version the first one replaced
- **THEN** the response is `412` and the second change is not applied

### Requirement: Data-scope grants are readable, grantable and revocable over the surface

The area SHALL serve:

- `GET /admin/v1/identity/grants?subject=&resource=&active=` (`identity.read`);
- `POST /admin/v1/identity/grants` (`identity.privileged`) - subject, principal type, resource, action, dimension,
  dimension value, optional expiry; the dimension SHALL be one the deployment's data-scope configuration accepts,
  else `400`;
- `POST /admin/v1/identity/grants/{id}/revoke` (`identity.privileged`) - sets the expiry to now and keeps the row.

A grant change SHALL take effect on the replica that served it after commit, and on every replica no later than the
data-scope cache TTL.

#### Scenario: A time-boxed grant is created
- **WHEN** an administrator grants subject `bob` `read` on `order` with dimension `tenant` value `acme`, expiring
  in seven days
- **THEN** the grant is returned with its id, an audit event records it, and `bob`'s next query on that replica is
  scoped by it

#### Scenario: A revoked grant keeps its history
- **WHEN** the grant is revoked
- **THEN** the row remains with `expires_at` at the revocation time, the listing with `active=false` shows it, and an
  audit event records the revocation

#### Scenario: An unknown dimension is refused
- **WHEN** a grant names a dimension the deployment does not accept
- **THEN** the response is `400` and nothing is written

### Requirement: A subject's effective authorities can be inspected

`GET /admin/v1/identity/subjects/{subject}/authorities` (`identity.read`) SHALL return what the authority resolver
answers for the subject now - roles, attributes and active grants - resolved without the authority cache, and SHALL
record an audit event, because it discloses one person's entitlements.

#### Scenario: Support checks why a user is refused
- **WHEN** a support caller inspects subject `alice`
- **THEN** the response lists her resolved roles and active grants, and an audit event records the inspection

### Requirement: Users are tenant-confined; partners and grants follow the subject

Subjects in `security_user` carry a tenant. In a multi-tenant service a `USER` caller SHALL see, inspect and grant
only for subjects of their own tenant; grant listings SHALL include only grants whose subject is of that tenant. The
partner registry carries no tenant and SHALL NOT be confined.

#### Scenario: A tenant administrator inspects another tenant's user
- **WHEN** a user of tenant `acme` inspects a subject of tenant `globex`
- **THEN** the response is `404`

