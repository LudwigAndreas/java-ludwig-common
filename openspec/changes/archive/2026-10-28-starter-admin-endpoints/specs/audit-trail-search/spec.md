## Purpose

Makes the stored audit trail searchable over the administrative surface, so the record of who did what - including
every administrative action - can be read without database access, confined to the reader's tenant and itself
recorded.

## ADDED Requirements

### Requirement: The audit area declares its operations

`audit-spring-boot-starter` SHALL declare `audit.read`, `audit.operate` and `audit.privileged`. In this release only
`audit.read` guards an endpoint.

#### Scenario: A caller without audit.read
- **WHEN** a caller not holding `audit.read` searches
- **THEN** the response is `403`

### Requirement: The stored trail is searchable with keyset pagination

`GET /admin/v1/audit/events` (`audit.read`) SHALL filter by category, action, actor subject, resource type,
resource id, outcome and an occurred-at range, newest first, with a page size of at most 200 and an opaque cursor.
A search without an occurred-at range SHALL be limited to the last 30 days. Each event SHALL be returned as stored,
with attributes as the envelope redacted them at construction.

#### Scenario: Who touched this partner
- **WHEN** a caller searches resource type `partner` and resource id `acme-logistics`
- **THEN** the events are returned newest first, each with actor, action, outcome and reason, and the response
  carries a cursor when more exist

#### Scenario: An oversized page is requested
- **WHEN** the page size is 1000
- **THEN** the response is `400`

### Requirement: Each search is itself recorded

Every search SHALL record one audit event (category `admin`, operation `audit.read`) carrying the filter used and
the number of events returned, and no returned event.

#### Scenario: A search leaves a trace
- **WHEN** a caller searches actor subject `alice`
- **THEN** one event records the caller, the filter `actor=alice` and the result count

### Requirement: A tenant administrator sees only their tenant's trail

The store SHALL record the acting principal's tenant with each event when one is known. In a multi-tenant service a
`USER` caller SHALL see only events whose actor tenant equals their own; events with no actor tenant SHALL be
visible only to `SERVICE` callers.

#### Scenario: A tenant administrator searches
- **WHEN** a user of tenant `acme` searches with no actor filter
- **THEN** no event whose actor belonged to `globex`, and no event without an actor tenant, is returned

#### Scenario: Events stored before the column existed
- **WHEN** events recorded before this release are searched by a `USER` in a multi-tenant service
- **THEN** they are not returned, because their actor tenant is unknown, and a `SERVICE` caller still sees them
