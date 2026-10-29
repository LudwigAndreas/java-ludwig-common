## MODIFIED Requirements

### Requirement: The stored trail is searchable with keyset pagination

`GET /admin/v1/audit/events` (`audit.read`) SHALL filter with `$filter` over the audit event's filterable fields,
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

Every search SHALL record one audit event (category `admin`, operation `audit.read`) carrying the filter's field
paths and operators, never its values, and the number of events returned, and no returned event.

#### Scenario: A search leaves a trace
- **WHEN** a caller searches `$filter=actorSubject eq 'alice'`
- **THEN** one event records the caller, the filter path `actorSubject` with operator `eq`, and the result count,
  and does not contain `alice`

## ADDED Requirements

### Requirement: Audit events are filterable on their envelope fields
`$filter` on `GET /admin/v1/audit/events` SHALL accept `occurredAt`, `category`, `action`, `outcome`,
`actorSubject`, `actorType`, `actorVia`, `onBehalfOf`, `resourceType`, `resourceId` and `correlationId`. The
free-text `reason`, the display and resource names, the actor's tenant and the event attributes SHALL NOT be
filterable.

#### Scenario: Denied PAT operations this week
- **WHEN** an auditor searches `$filter=outcome eq 'DENIED' and startswith(action, 'pat.') and occurredAt ge
  2026-10-26T00:00:00Z`
- **THEN** only denied events whose action starts with `pat.` since that instant are returned

#### Scenario: Everything done through the administrative UI yesterday
- **WHEN** an auditor searches `$filter=actorVia eq 'admin-console'` with yesterday's `occurredAt` range
- **THEN** only events whose actor acted through `admin-console` in that range are returned, each with `via` =
  `admin-console`

## REMOVED Requirements

### Requirement: The trail can be searched by acting party
**Reason**: The `via` query parameter is replaced by `$filter=actorVia eq '...'`; every listing filters with OData
only. Each returned event still carries its `via`.
**Migration**: Replace `?via=admin-console` with `?$filter=actorVia eq 'admin-console'`.
