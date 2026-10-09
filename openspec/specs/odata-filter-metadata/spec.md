# Filter-policy discovery

## Purpose
What a client may learn about a filterable endpoint's query surface without probing it: the properties it
may name, the operators each one permits, the limits the server will enforce, and what the caller's own
roles remove from that answer.

## Requirements

### Requirement: The filterable surface is published as a document, not discovered by probing
An entity whose filter policy is published SHALL expose a document stating every property path a caller
may name, each path's data type, the operators permitted on it, whether it may be used for ordering, and
the limits the server enforces - nesting depth, maximum and default page size, association traversal depth,
and the server-side ordering appended to the caller's.

#### Scenario: A client needs to build a filter expression
- **WHEN** a client asks for a published entity's filter metadata
- **THEN** it receives the property paths, their types, their permitted operators, their sortability and
  the enforced limits, and never needs to send a filter to find out whether a field is filterable

#### Scenario: A client would otherwise learn the surface from rejections
- **WHEN** no document is available
- **THEN** the only discovery mechanism is sending filters and reading 400s and 403s - the behaviour this
  module's own `odata.filter.rejected` counter is documented as an alerting signal for. A module that tells
  operators to alarm on probing SHALL offer the alternative

#### Scenario: The document is compared against what the query path enforces
- **WHEN** the document says an operator is permitted on a path
- **THEN** a query using that operator on that path is not rejected for policy reasons, because the
  document and the enforcement are projections of the same resolved policy and not two descriptions of it

### Requirement: Publication is opt-in per entity
An entity SHALL have no metadata document unless it declares one. The endpoint SHALL answer as it does for
an unknown entity when asked about an entity that has not declared publication.

#### Scenario: An entity has filterable fields but declares no publication
- **WHEN** metadata is requested for it
- **THEN** the response is a 404 rendered through the shared problem pipeline, in the caller's language and
  in the same RFC 9457 shape as every other error the service emits. It is not an empty document, which
  would confirm the entity exists

#### Scenario: A deployment has not configured discovery at all
- **WHEN** no base path is configured
- **THEN** no endpoint is mounted. A document is a map of the queryable surface, equally useful to a client
  and to someone enumerating it, so neither the endpoint nor any entity's publication is on by default

#### Scenario: A new filterable field is added to a published entity
- **WHEN** a field is annotated filterable on an entity that already publishes
- **THEN** it appears in the document with no further declaration, because the document is a projection of
  the resolved policy rather than a second list to maintain

### Requirement: The document states only what this caller may use
The document SHALL be resolved against the requesting caller's roles, through the same role resolution the
query path uses. A path the caller may not filter or sort on SHALL be absent.

#### Scenario: A field is restricted to a role the caller does not hold
- **WHEN** a caller without that role requests the document
- **THEN** the path is absent from it

#### Scenario: A field is restricted to a role the caller does hold
- **WHEN** a caller holding that role requests the document
- **THEN** the path is present, with the operators that field permits

#### Scenario: A restricted path is reported as forbidden rather than omitted
- **WHEN** a representation marking a path as present-but-forbidden is considered
- **THEN** it is refused. A caller learning that a path exists and which privilege it needs has learned a
  column name and a privilege name from an endpoint whose purpose was to tell it less

#### Scenario: An association is restricted
- **WHEN** a caller may not traverse an association
- **THEN** every path beneath it is absent, matching the query path's rule that a nested path is never
  easier to reach than the association leading to it

#### Scenario: The document is cached across callers
- **WHEN** caching the document is considered
- **THEN** any cache SHALL be keyed on the caller's resolved roles, because a document cached per entity
  would serve an administrator's field list to an unprivileged caller

#### Scenario: As built, there is no cache
- **WHEN** the implementation is read
- **THEN** it caches nothing. The resolved policy is already memoized, so what a request costs is a stream
  over a few dozen map entries, and a cache would have to declare `CachePurpose.SECURITY` - its TTL being how
  long a revoked role keeps seeing a field. Paying that correctness cost to save a map traversal is the wrong
  trade. If one is ever added it goes through `LudwigCacheRegistry` with that purpose and a role-set key,
  never a `Caffeine` builder of its own

### Requirement: The document describes the filter policy and does not claim to be OData metadata
The document SHALL describe this module's resolved policy. It SHALL NOT be presented as an OData CSDL
`$metadata` document.

#### Scenario: A client expects CSDL
- **WHEN** the document is named or typed
- **THEN** it is named for what it is - the entity's filter policy - because this module implements
  `$filter`, `$orderby`, `$top`, `$skip` and `$count` and no entity container, navigation property or
  action. A document calling itself `$metadata` while describing only the filterable subset would mislead
  every generic OData client that found it

#### Scenario: A field is not filterable
- **WHEN** an entity has fields that are not annotated filterable
- **THEN** they are absent from the document. It is a projection of the allow-list, never of the schema

### Requirement: A metadata request is observable separately from a query
Metadata requests SHALL be counted separately from filter applications and filter rejections.

#### Scenario: An operator is investigating a rejection spike
- **WHEN** rejections rise
- **THEN** the metadata counter distinguishes clients that asked for the surface from clients probing for
  it, which is the distinction the existing rejection counter cannot make on its own
