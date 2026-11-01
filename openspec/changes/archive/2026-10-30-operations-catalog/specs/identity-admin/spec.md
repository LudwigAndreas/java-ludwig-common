## RENAMED Requirements

- FROM: `### Requirement: The identity area declares three operations`
- TO: `### Requirement: The identity area declares one operation per action`

## MODIFIED Requirements

### Requirement: The identity area declares one operation per action

`identity-projection-spring-boot-starter` SHALL declare, on the `ADMIN` surface, `identity.partner.read`,
`identity.grant.read` and `identity.authority.read` (`READ`), and `identity.partner.onboard`,
`identity.partner.replace`, `identity.partner.suspend`, `identity.partner.activate`, `identity.grant.create` and
`identity.grant.revoke` (`PRIVILEGED`). Every partner and grant change SHALL be `PRIVILEGED`, because each changes
who may access what.

#### Scenario: Granting a data scope requires its own operation
- **WHEN** a caller holding `identity.grant.read` and `identity.grant.revoke` but not `identity.grant.create` posts a grant
- **THEN** the response is `403` and no grant row is written

### Requirement: The partner registry is readable and changeable over the surface

The area SHALL serve:

- `GET /admin/v1/identity/partners` and `GET /admin/v1/identity/partners/{code}` (`identity.partner.read`) - code, display
  name, status, certificate identifiers, roles;
- `POST /admin/v1/identity/partners` (`identity.partner.onboard`) - onboard; `409` if the code exists;
- `PUT /admin/v1/identity/partners/{code}` (`identity.partner.replace`) - replace display name, certificate identifiers
  and roles, with optimistic concurrency through `If-Match`; `412` on a stale version;
- `POST /admin/v1/identity/partners/{code}/suspend` (`identity.partner.suspend`) and `.../activate`
  (`identity.partner.activate`).

A suspended partner SHALL be refused authentication no later than the security authority cache TTL after the
change commits.

#### Scenario: A partner is suspended
- **WHEN** an administrator suspends partner `acme-logistics` with reason `contract ended`
- **THEN** the response is `200` with status `SUSPENDED`, and one audit event of category `admin` records the
  actor, operation `identity.partner.suspend`, the partner code, the reason and outcome succeeded, in the same transaction
  as the change

#### Scenario: Two administrators edit the same partner
- **WHEN** the second `PUT` carries the version the first one replaced
- **THEN** the response is `412` and the second change is not applied

### Requirement: Data-scope grants are readable, grantable and revocable over the surface

The area SHALL serve:

- `GET /admin/v1/identity/grants?subject=&resource=&active=` (`identity.grant.read`);
- `POST /admin/v1/identity/grants` (`identity.grant.create`) - subject, principal type, resource, action, dimension,
  dimension value, optional expiry; the dimension SHALL be one the deployment's data-scope configuration accepts,
  else `400`;
- `POST /admin/v1/identity/grants/{id}/revoke` (`identity.grant.revoke`) - sets the expiry to now and keeps the row.

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

`GET /admin/v1/identity/subjects/{subject}/authorities` (`identity.authority.read`) SHALL return what the authority resolver
answers for the subject now - roles, attributes and active grants - resolved without the authority cache, and SHALL
record an audit event, because it discloses one person's entitlements.

#### Scenario: Support checks why a user is refused
- **WHEN** a support caller inspects subject `alice`
- **THEN** the response lists her resolved roles and active grants, and an audit event records the inspection
