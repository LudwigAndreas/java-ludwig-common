## MODIFIED Requirements

### Requirement: The identity area declares one operation per action

`identity-projection-spring-boot-starter` SHALL declare, on the `ADMIN` surface, `identity.partner.read`,
`identity.grant.read`, `identity.authority.read` and `identity.source.read` (`READ`), and `identity.partner.onboard`,
`identity.partner.replace`, `identity.partner.suspend`, `identity.partner.activate`, `identity.grant.create` and
`identity.grant.revoke` (`PRIVILEGED`). Every partner and grant change SHALL be `PRIVILEGED`, because each changes
who may access what.

#### Scenario: Granting a data scope requires its own operation
- **WHEN** a caller holding `identity.grant.read` and `identity.grant.revoke` but not `identity.grant.create` posts a grant
- **THEN** the response is `403` and no grant row is written

#### Scenario: Reading the sources needs its own operation
- **WHEN** a caller holding `identity.authority.read` but not `identity.source.read` calls
  `GET /admin/v1/identity/sources`
- **THEN** the response is `403`

## ADDED Requirements

### Requirement: The identity sources are listable
`GET /admin/v1/identity/sources` (`identity.source.read`) SHALL list every declared source with: the feeds serving
it (`kafka` with its topic, `directory`), its users counted by status, and when its newest applied change happened at the
source. For a source served by a directory it SHALL also list the stored watermark and the last sync and last sweep, each
with its time, outcome, error code when failed, pages read, and the changes a sync read or the users a sweep disabled. A declared source no feed
serves SHALL be listed with no feeds. The listing SHALL carry no user's data.

#### Scenario: A service with a Kafka source and a directory source
- **WHEN** an administrator lists the sources of a service declaring `staff` (Kafka) and `customers` (directory)
- **THEN** the response lists `staff` with its topic and user counts, and `customers` with its watermark, last sync
  and last sweep

#### Scenario: A failed sync is visible
- **WHEN** `customers`' last sync failed with a timeout
- **THEN** the listing shows that sync's outcome as failed with its error code
