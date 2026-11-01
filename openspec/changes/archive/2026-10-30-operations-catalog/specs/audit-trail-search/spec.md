## MODIFIED Requirements

### Requirement: The audit area declares its operations

`audit-spring-boot-starter` SHALL declare one operation, `audit.event.read`, on the `ADMIN` surface with sensitivity
`READ`. It guards the search.

#### Scenario: A caller without audit.event.read
- **WHEN** a caller not holding `audit.event.read` searches
- **THEN** the response is `403`

### Requirement: The stored trail is searchable with keyset pagination

`GET /admin/v1/audit/events` (`audit.event.read`) SHALL filter with `$filter` over the audit event's filterable fields,
newest first, with a page size of at most 200 and an opaque cursor. A search whose filter does not constrain
`occurredAt` SHALL be limited to the last 30 days. Each event SHALL be returned as stored, with attributes as the
envelope redacted them at construction, and with the party its actor acted through as `via`.

#### Scenario: Who touched this partner
- **WHEN** a caller searches `$filter=resourceType eq 'partner' and resourceId eq 'acme-logistics'`
- **THEN** the events are returned newest first, each with actor, action, outcome and reason, and the response
  carries a cursor when more exist

#### Scenario: An oversized page is requested
- **WHEN** the page size is 1000
- **THEN** the response is `400`

#### Scenario: A filter on the time range lifts the default window
- **WHEN** a caller searches `$filter=occurredAt ge 2026-01-01T00:00:00Z`
- **THEN** events back to that instant are returned, not only the last 30 days

### Requirement: Each search is itself recorded

Every search SHALL record one audit event (category `admin`, operation `audit.event.read`) carrying the filter's field
paths and operators, never its values, and the number of events returned, and no returned event.

#### Scenario: A search leaves a trace
- **WHEN** a caller searches `$filter=actorSubject eq 'alice'`
- **THEN** one event records the caller, the filter path `actorSubject` with operator `eq`, and the result count,
  and does not contain `alice`
