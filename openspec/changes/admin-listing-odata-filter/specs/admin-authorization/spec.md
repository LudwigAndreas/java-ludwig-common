## ADDED Requirements

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
