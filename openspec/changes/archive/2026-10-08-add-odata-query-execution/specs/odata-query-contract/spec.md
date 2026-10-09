## Purpose

How a caller's OData query options travel from an HTTP request to a database query and back out as a
page: who is allowed to parse them, which layer may execute the resulting predicate, what the response
envelope tells the caller about its own position, and when the service pays for a count query.

## ADDED Requirements

### Requirement: The caller's query options are one parameter object, not four parameters
The four OData query options and `$count` SHALL be carried by a single request type that names no JPA
entity and holds no QueryDSL expression, so that a controller can declare them once and an OpenAPI
document can describe them.

#### Scenario: A controller exposes a filterable collection
- **WHEN** an endpoint accepts `$filter`, `$orderby`, `$top`, `$skip` and `$count`
- **THEN** it declares one parameter object rather than five `@RequestParam`s, and the generated
  OpenAPI document lists all five query parameters with their types

#### Scenario: The parameter object is inspected for entity types
- **WHEN** the request type's declaration is examined
- **THEN** it holds only strings, integers and a boolean. A type naming the entity would be rejected
  by `architecture-rules`' `web.controllers-do-not-expose-entities`, which inspects type arguments, and
  a type holding a `Predicate` would put a query plan in a layer that cannot execute it

#### Scenario: Two option strings are transposed at a call site
- **WHEN** a call site passes the ordering expression where the filter expression belongs
- **THEN** it does not compile, because the options are named members of a record rather than
  positional `String` parameters

### Requirement: Dynamic ordering paths are built by the module, never by a consumer
A consumer SHALL NOT construct the QueryDSL ordering expressions for `$orderby` itself. The module
SHALL expose the conversion from a resolved ordering to QueryDSL ordering expressions, against the same
query root the predicate is built against.

#### Scenario: A repository needs ordering expressions for its own query
- **WHEN** a repository drives its own query - because it adds a fetch join, or selects a projection -
  and needs the ordering expressions for the caller's `$orderby` plus the entity's configured
  tie-breaker
- **THEN** it obtains them from this module, and no reflective property-path walk, no raw-typed
  ordering construction and no unchecked-cast suppression appears in the repository

#### Scenario: A consumer's ordering root disagrees with the predicate's root
- **WHEN** ordering expressions and the predicate are built against different query roots
- **THEN** the query cross-joins the same table to itself rather than failing, which is why the
  conversion is the module's and not the consumer's. The module derives both from one root

### Requirement: A consumer can execute the whole query without writing the paging and counting code
The module SHALL provide an execution path that, from an entity type and the caller's options, produces
a page of entities: predicate applied, ordering applied, offset and limit applied, total resolved
according to `$count`. A consumer SHALL be able to contribute additional predicates and additional
query structure without re-implementing any of those four steps.

#### Scenario: A service lists a collection with no special query needs
- **WHEN** a repository exposes a filtered, ordered, paged search over one entity
- **THEN** it delegates the execution and holds no `Pageable` arithmetic, no page-assembly call and no
  count method of its own

#### Scenario: The caller's data scope must be enforced in the same WHERE clause
- **WHEN** a repository must AND the caller's data-access scope into the query
- **THEN** it contributes that predicate to the execution, and the scope lands in the same `WHERE`
  clause as the `$filter`. Filtering the page afterwards would return fewer than `$top` rows against a
  total that counts rows the caller may not see, so both the page and the pager would be wrong

#### Scenario: A repository needs a fetch join or a projection
- **WHEN** an entity's response always includes an association, or the endpoint selects fewer columns
  than the entity has
- **THEN** the repository supplies that query structure and still delegates predicate, ordering, paging
  and counting. An execution path a consumer cannot shape is one that gets bypassed, and bypassing it
  reintroduces the hand-written code this requirement exists to remove

### Requirement: The response envelope states the caller's absolute position
A paged response SHALL carry the absolute row offset that was applied. A page index SHALL NOT be the
only position the envelope reports.

#### Scenario: A caller pages by an offset that is not a multiple of the page size
- **WHEN** a caller requests `$top=20&$skip=25`
- **THEN** the response reports an offset of 25. Reporting a page index alone makes `$skip=20` and
  `$skip=25` indistinguishable in the response, so a caller cannot compute its next request from what
  it was sent

#### Scenario: A page index is derived from an unaligned offset
- **WHEN** an offset is not a whole multiple of the page size
- **THEN** the derived page index is not published as if it were the caller's position. The request
  vocabulary is offset-based and the envelope SHALL not silently convert it to a page-based one

### Requirement: A caller can decline the count query
`$count=false` SHALL cause the total to be neither computed nor reported. The default SHALL be to
report it.

#### Scenario: A caller asks for a page without a total
- **WHEN** a request carries `$count=false`
- **THEN** no count query is issued and the response reports no total. On a deep-paged filtered query
  over a large table the count is usually the slowest part of the request, which is the whole reason
  the option exists

#### Scenario: A caller does not mention $count
- **WHEN** a request omits `$count`
- **THEN** the total is computed and reported, because a pager is what most callers of a collection
  endpoint need and silence must not change an existing response

#### Scenario: $count carries something that is not a boolean
- **WHEN** `$count` is present with a value that is neither `true` nor `false`
- **THEN** the request is rejected through the shared problem pipeline with a localized message under
  this module's own code namespace, in the same RFC 9457 shape as every other error the service emits

### Requirement: The absence of a filter is observable
The parse result SHALL distinguish "the caller supplied no filter" from "the caller supplied a filter
that matches everything". It SHALL NOT represent the former as an always-true predicate.

#### Scenario: A consumer that does not page needs only the predicate
- **WHEN** a consumer parses options for a purpose where `$top` and `$skip` are meaningless - a report
  over a whole result set, or a saved filter replayed by a batch job - and the caller supplied no
  filter
- **THEN** it observes that there is no predicate, and appends nothing to its query. An always-true
  predicate ANDed into every statement is a condition the consumer cannot tell apart from a real one

#### Scenario: A caller supplies a filter
- **WHEN** a non-blank `$filter` is parsed successfully
- **THEN** a predicate is present, and it is the translation of that filter and nothing else

### Requirement: Options are parsed in the layer that owns the entity
The options SHALL be parsed where the entity is owned - the persistence layer - and the layers above it
SHALL pass the caller's options down unparsed. No argument resolver SHALL bind a parse result into a
controller parameter.

#### Scenario: A controller receives a filterable request
- **WHEN** a request with OData options reaches a controller
- **THEN** the controller passes the options object down and never holds a predicate, a `Pageable` or
  an entity type. The parse output is a QueryDSL predicate, which only a repository can execute

#### Scenario: A framework-level resolver would fill a parse result into a handler parameter
- **WHEN** such a resolver is considered
- **THEN** it is refused, for four reasons each of which is independently sufficient: the parameter
  names a JPA entity at the REST boundary, so `architecture-rules` rejects it; the controller must then
  either hold a repository or pass a predicate into the service API; the parameter cannot be described
  in the OpenAPI document, so the four query options vanish from it; and argument resolution runs before
  the handler's authorization check, so a caller who may not use the endpoint can still learn from a 403
  which fields are filterable
