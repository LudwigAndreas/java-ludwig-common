# odata-filter-spring-boot-starter

***English** · [Русский](README.ru.md)*

Enterprise-ready OData `$filter`/`$top`/`$skip`/`$orderby` support for Spring Boot + Spring Data
JPA REST APIs. Parses OData query options into type-safe QueryDSL predicates against your JPA
entities, with the guardrails a production API needs: filter nesting-depth limits, a deny-by-default
field allow-list that covers associations too, role-based field access, max page size, a server-side
tie-breaker that keeps paging deterministic, and a hook for programmatic validation. Add it as a
dependency, annotate your entities, call it from your repository.

## Why

[`odata-server-api`](https://olingo.apache.org)/`odata-server-core` and
`olingo-jpa-processor-v4` are great building blocks but need real work to be safe for a
multi-tenant production API: an unbounded `$filter` can be a denial-of-service vector, and OData's
model has no concept of "this field is only filterable by admins." This starter uses Olingo's own
ABNF tokenizer (`UriTokenizer`) to parse the standard OData `$filter` grammar, then translates it
to a QueryDSL `Predicate` via `PathBuilder` - no `QEntity` annotation-processor step required - and
enforces policy before a single JPQL query is built.

## Quick start

```xml
<dependency>
    <groupId>ru.ludwigandreas</groupId>
    <artifactId>odata-filter-spring-boot-starter</artifactId>
    <version>1.0.0</version>
</dependency>
```

Annotate the entity fields you want filterable/sortable. Everything else - associations included -
is unreachable by default:

```java
@Entity
@FilterPolicy(maxDepth = 4, maxPageSize = 100, defaultPageSize = 20,
        defaultOrderBy = "createdAt desc, id asc")
public class Product {

    @Filterable
    private String name;

    @Filterable(ops = {EQ, GT, GE, LT, LE})
    private BigDecimal price;

    @Filterable(name = "cost", roles = "ROLE_ADMIN")   // exposed under an API name of its own,
    private BigDecimal supplierCost;                   // and only to admins

    private String internalNotes;                      // not annotated -> never filterable

    @Filterable                     // opens Category's own @Filterable fields as "category/code",
    @ManyToOne                      // "category/name", ... - an unannotated association is a wall
    private Category category;
}
```

Two things there are worth reading twice.

**Traversal is opt-in, like everything else.** `Category`'s `@Filterable` fields were chosen for
*Category's* endpoint; without the annotation on the `category` field they stay invisible here. If
association traversal were automatic, opening one field on a widely-referenced entity would widen
the filter surface of every endpoint that happens to reach it. `roles` on the association gates the
whole subtree: a caller needs the association's roles *and* the nested field's own.

**`defaultOrderBy` is what makes paging correct.** A query with no total order lets the database
return rows however it likes, so `$skip=0` and `$skip=20` are two independent queries that can show
the same row twice or skip it entirely - a bug that only appears at production data volumes. It is
*appended* to the caller's `$orderby` rather than used only as a fallback, so it breaks ties under
`$orderby=status` too. End it on a unique column. These paths are server configuration, not caller
input, so they need no `@Filterable` - a surrogate key no client may filter on is the normal
choice - but they are checked against the entity's fields when the policy is first resolved, so a
typo fails loudly instead of reaching the database.

Then run the query **in the layer that owns the entity**: the repository. One call does the whole of
it - predicate, ordering, offset, limit, and the count unless the caller declined it:

```java
@Repository
@RequiredArgsConstructor
class ProductQueryRepositoryImpl implements ProductQueryRepository {

    private static final QProduct PRODUCT = QProduct.product;

    private final ODataQueryExecutor odataExecutor;

    @Override
    public ODataPage<Product> search(ODataQueryOptions options) {
        return odataExecutor.search(Product.class, options, ODataSearch.of(PRODUCT));
    }
}
```

`ODataSearch` is where a repository says what else its query needs. Everything on it is optional:

```java
return odataExecutor.search(Product.class, options, ODataSearch.of(PRODUCT)
        // ANDed into the content query AND the count query, so the page and the total agree. This is
        // where a caller's data scope goes.
        .and(dataAccessGuard.predicate("product", DataAction.READ))
        // Shapes only the content query - QueryDSL refuses a fetch join on a count, and a join that
        // multiplied rows would change the total.
        .content(query -> query.leftJoin(PRODUCT.category).fetchJoin()));
```

**Pass the generated default instance as the root.** `QProduct.product` is aliased `product`, which is
the alias the predicate and the ordering are built against; `new QProduct("p")` is not, and two
different aliases do not fail in JPQL - they cross-join the table to itself and return rows that are
quietly wrong. The executor checks and refuses rather than trusting, naming both aliases. That check is
deliberately at runtime: the alias is the *value* of a string a `Q`-type was constructed with, which
neither Checkstyle nor ArchUnit can read.

If you must drive the query yourself - a projection, a window function, something the two hooks cannot
express - take the ordering from this module rather than writing the path walk again:

```java
OrderSpecifier<?>[] order = ODataPaths.orderSpecifiers(Product.class, query.pageable().getSort());
```

A Spring Data `QuerydslPredicateExecutor` repository also still works, because the predicate is built
against Spring Data's own `SimpleEntityPathResolver` alias convention:

```java
Page<Product> page = repository.findAll(query.predicate().orElse(null), query.pageable());
```

The controller takes the five options as one parameter and passes them down unparsed, so no JPA entity
and no QueryDSL type ever appears above the repository:

```java
@GetMapping
public PageResponse<ProductResponse> search(ODataQueryOptions options) {
    ODataPage<ProductResponse> page = productService.search(options).map(mapper::toResponse);
    return PageResponse.of(page.content(), page.offset(), page.size(), page.totalElements());
}
```

```
GET /products?$filter=price lt 100 and category/code eq 'TOOLS'&$top=20&$orderby=name desc&$count=false
```

[`crud-service-example`](../../services/crud-service-example/README.md) is this arrangement end to end:
controller -> service -> repository, with the caller's data scope ANDed into the same `WHERE`
clause as the `$filter`.

### What the response says about where you are

`PageResponse` reports `offset` as well as `page`, and the reason is `$skip`: OData's offset is
absolute and explicitly need not be a multiple of `$top`, so an unaligned `$skip` has no integer page.
At `$top=20`, `$skip=20` and `$skip=25` both derive `page=1`, and a client paging by offset could not
compute its next request from the response. `offset` is the authoritative position; `page` is the
convenience for the aligned case.

`totalElements` and `totalPages` are **absent** - omitted from the JSON, not null, not zero - when the
caller sent `$count=false`. No count query is issued in that case, which on a deep-paged filtered
query over a large table is usually the slowest part of the request. Zero is not available as a
sentinel because zero is a real total.

### Why not bind a parsed query into the controller parameter

This module used to ship an `ODataQueryArgumentResolver` that filled in an `ODataQuery<Product>`
directly. It was deprecated, off by default, and is now **removed**. Four reasons, and each is worth
reading because each is about what that resolver *produced* rather than how it read the request:

- `ODataQuery<Product>` names a JPA entity in a controller signature, which
  [`architecture-rules`](../../build/architecture-rules/README.md)' `web.controllers-do-not-expose-entities`
  rejects - it inspects type arguments, so the parameter is caught even when the return type is a DTO.
- What it hands you is a QueryDSL `Predicate`, which only a repository can execute. The controller
  must therefore either hold a repository - `layering.controllers-do-not-access-persistence`, plus a
  query running outside any transaction - or pass the predicate down, which puts the entity in the
  service API instead.
- springdoc cannot describe a resolver-bound parameter, so `$filter`, `$top`, `$skip` and `$orderby`
  disappear from the OpenAPI document.
- Argument resolution runs before the handler's `@PreAuthorize`, so a caller who may not use the
  endpoint at all can still learn from a 403 which fields are filterable.

`ODataQueryOptions` is the replacement, and none of the four applies to it: it has no type parameter
and names no entity, it carries the request rather than a query plan, its five parameters are added to
the OpenAPI document by `ODataQueryOptionsOpenApiCustomizer` (active whenever springdoc is on the
classpath), and it evaluates no policy - the only way its resolver can fail is a malformed scalar
option, which is a 400 about the request that says nothing about the data model.

The request-reading half of the old resolver is exactly what the new one kept, including the
non-`$`-prefixed aliases for gateways that mangle a leading `$`
(`odata.filter.web.dollar-prefixed-parameters-only=true` turns them off).

Parsing stays in the repository, and `ODataFilterService` keeps its independence from Spring MVC and
from JPA - which is what lets `export-spring-boot-starter` replay a saved filter on a worker thread
with no request and no `EntityManager`. `ODataQueryExecutor` is the part that needs a
`JPAQueryFactory`, and it lives in its own auto-configuration conditional on one, so a module that
only parses filters still starts.

## What's enforced, and where it's configured

| Concern | Global default (`odata.filter.*`) | Per-entity override (`@FilterPolicy`) |
|---|---|---|
| Filter nesting depth | `max-depth` (4) | `maxDepth` |
| Max page size (`$top`) | `max-page-size` (200) | `maxPageSize` |
| Default page size | `default-page-size` (20) | `defaultPageSize` |
| Association traversal depth | `max-nested-property-depth` (2) | `maxNestedPropertyDepth` |
| Raw `$filter` string length | `max-expression-length` (2048) | - |
| Ordering appended to `$orderby` | - | `defaultOrderBy` |
| `$top` over the max | `page-size-exceeded-strategy` (`REJECT`/`CLAMP`) | - |
| Whether a total is computed | the caller's `$count` (default: yes) | - |

Per-field, via `@Filterable`: which operators are allowed (`ops`), which roles may use it
(`roles`), and whether it's sortable (`sortable`).

For anything the declarative policy can't express (e.g. "date filters on Order may not span more
than a year"), register a `FilterValidator` bean - it runs after the built-in checks, before the
predicate is built:

```java
@Bean
FilterValidator orderDateRangeValidator() {
    return context -> {
        if (context.entityType() == Order.class && spansMoreThanAYear(context.root())) {
            throw new FilterValidationException("date range filters on Order may not span more than 366 days");
        }
    };
}
```

### The audit trail

Every applied filter is recorded to [`audit-core`](../audit-core/README.md)'s single `AuditSink`, under the
`query` category and the `query.filtered` action, naming the entity as the resource. **What it records is
the property paths and the operators, never the values**: `properties=email, name` and
`operators=email eq, name contains` say that a caller filtered on an e-mail address without saying which
one.

That is not a nicety. An audit trail is retained for years and read by people who are not entitled to the
data it guards, so a value in it is a leak with a long tail - and the values are not hashed or truncated
either, because a hash is reversible for anything drawn from a small set (a status, a channel, a country
code) and a truncation leaks exactly the identifying prefix. The only safe treatment of a value nobody
needs is not to collect it, which is why `FilterSummary` is derived from the parsed AST and never reads a
literal.

**Whether a sink outage fails the query is the deployment's decision, not this module's.** The sink is
called with no `try`/`catch`: `AuditFailurePolicy`, resolved from configuration, decides whether a failed
audit write rolls the read back or is logged and survived. A `catch` here would override that with a
choice hard-coded in a library. Configure the policy for the `query` category accordingly - a deployment
that would rather lose the read than lose the record of it sets `FAIL_OPERATION`, and most do not.

An application that has not added `audit-spring-boot-starter` has no sink bean, and the trail then goes to
`audit-core`'s `NoopAuditSink` rather than failing startup.

The `FilterAppliedEvent` Spring application event is **still published**, and is still worth listening for
as an in-process hook - a metric, a cache invalidation. What it is no longer is the way a filter reaches
the audit trail: it used to be the only way, which meant the trail existed only if every consuming service
wrote the same forwarding listener, nothing was redacted, and `AuditFailurePolicy` governed none of it.

## Role resolution

If Spring Security is on the classpath, roles are read from
`SecurityContextHolder`'s `GrantedAuthority`s automatically. Otherwise (or to source roles from
somewhere else, e.g. a gateway-injected header), implement `FilterPrincipalResolver` and expose it
as a bean.

## Supported `$filter` grammar

`and`, `or`, `not`, parenthesized grouping; comparisons `eq ne gt ge lt le`; `in (v1, v2, ...)`;
string functions `contains`/`startswith`/`endswith`; property paths traversing annotated to-one
associations with `/` (e.g. `department/manager/name`). String, integer, decimal, double, boolean,
date, date-time-offset, GUID and enum literals. Not supported (by design, for a bounded, reviewable
surface): arithmetic, `any`/`all`, `$select`/`$expand`, and other OData functions.

`$count` **is** implemented, and honoured: `$count=false` skips the count query and the response then
carries no total. This module previously argued the flag bought the caller nothing, and that was true
for as long as nothing here executed the query - a flag on `ODataQuery` that no code could act on is
worse than no flag. `ODataQueryExecutor` is the one place that can act on it, so it does. A value that
is neither `true` nor `false` is a 400 naming `$count` in a `property` member; `Boolean.parseBoolean`
is deliberately not used, because it would read `$count=yes` as `false` and take the caller's total
away without saying so.

Two further traps worth knowing, both inherited from SQL rather than from this library: a filter
over a nested path (`department/name eq 'Sales'`) becomes an inner join, so rows whose association
is null drop out of the result - including under `ne` and `not` - and three-valued logic means
`not (status eq 'X')` never matches a row whose status is null.

## Error responses

Every exception this module raises extends `ODataFilterException`, and how it reaches the client
depends on one thing: whether
[`web-core-spring-boot-starter`](../../sources/web-core-spring-boot-starter/README.md) is on the classpath.

**With it** - the intended arrangement - this module contributes an `ODataFilterProblemMapper` and the
`i18n/ludwig-odata-filter-messages` bundle to that starter's shared problem pipeline. Query errors
then come back **in the caller's language**, under `ludwig.odata.error.*` codes, in exactly the same
RFC 9457 shape as every other error the service emits, with the offending field in a `property`
member a client can read without parsing a sentence:

```json
{
  "type": "urn:ludwig:problem:ludwig.odata.error.field-forbidden",
  "title": "Доступ запрещён",
  "detail": "У вас нет прав на фильтрацию или сортировку по одному из полей этого выражения. См. поле \"property\".",
  "status": 403,
  "code": "ludwig.odata.error.field-forbidden",
  "property": "supplierCost"
}
```

Reword any of it by defining the same key in your own bundle - the pipeline resolves the
application's bundle first.

**Without it**, the legacy `ODataFilterExceptionHandler` advice is registered instead, so a service
that does not use that starter still gets RFC 7807 responses - in English, from the exceptions' own
developer-facing messages. Disable it with `odata.filter.web.problem-detail-advice-enabled=false` to
handle these exceptions yourself.

The two never coexist. That advice was the module's original answer and it had a structural flaw: a
library can only emit the text it was compiled with, so its English messages were the one part of an
otherwise localized API that could not be translated - and the only escape was to switch it off and
re-handle all seven exception types by hand, which every consumer then did identically. The mapper
plus the bundle removes both halves of that: the meaning is declared here once, the text ships here
in every locale this module supports, and the application can still override any of it.

## Discovering what is filterable

A client should not have to learn the filterable surface by sending filters and reading the rejections -
which is the only alternative, and is the behaviour the `odata.filter.rejected` counter below is documented
as an alerting signal for. Switch discovery on and a caller can ask:

```
GET /api/v1/filter-metadata/product
```

```json
{
  "entity": "product",
  "maxDepth": 4,
  "maxPageSize": 100,
  "defaultPageSize": 20,
  "maxNestedPropertyDepth": 2,
  "defaultOrderBy": "createdAt desc, id asc",
  "properties": [
    { "path": "category/code", "type": "string",  "operators": ["contains", "endswith", "eq", "ge", "gt", "in", "le", "lt", "ne", "startswith"], "sortable": true },
    { "path": "name",          "type": "string",  "operators": ["contains", "endswith", "eq", "startswith"], "sortable": true },
    { "path": "price",         "type": "decimal", "operators": ["eq", "ge", "gt", "le", "lt", "ne"], "sortable": true }
  ]
}
```

Two things to switch on, and both are deliberate:

```yaml
odata:
  filter:
    metadata:
      base-path: /api/v1/filter-metadata   # unset = the endpoint does not exist at all
```

```java
@Entity
@FilterPolicy(maxPageSize = 100, defaultOrderBy = "createdAt desc, id asc",
        metadataName = "product")          // unset = this entity publishes nothing, and 404s
public class ProductEntity { }
```

**Unset means absent, not refusing.** A metadata document maps the queryable surface, which is as useful to
someone enumerating it as to a client, so the surface should not exist until a deployment decided it should.
An endpoint that existed and answered 403 would still confirm the feature and still be one misconfiguration
away from answering. The same reasoning makes it per-entity: an entity nobody named answers 404, and so does
a name nobody used - telling the two apart would make this a way to enumerate the entity model.

**Each caller gets its own document.** Roles come from the same `FilterPrincipalResolver` the query path
uses, and a path the caller may not filter on is **absent** - not present and marked forbidden. A caller
learning that `supplierCost` exists and needs `ROLE_ADMIN` has learned a column name and a privilege name
from an endpoint whose purpose is to tell it less. No role name appears in the document for the same reason,
and a restricted association hides every path beneath it.

**It is a projection of the policy, never a second list.** Every field comes from the resolved
`EntityFilterPolicy`, which comes from the entity's own annotations, so what the document advertises is
exactly what the query path accepts. A field list maintained beside the annotations would drift, and would
drift invisibly in the two cases that matter: a field opened in code and still absent from the document, and
one closed in code and still advertised. A test asserts the agreement directly, because "these two
computations agree" is a property of their output that neither ArchUnit nor Checkstyle can see.

**Only `@Filterable` paths appear.** An unannotated field is invisible to the filter and stays invisible to
discovery - the document projects the allow-list, not the schema.

### Why it is not called `$metadata`

OData's `$metadata` is a CSDL document describing a service: entity sets, navigation properties, actions, an
entity container. This module implements `$filter`, `$orderby`, `$top`, `$skip` and `$count` and none of the
rest, so a document calling itself `$metadata` while describing only the filterable subset would mislead
every generic OData client that found it. That is worse than not having one.

### Why there is no cache

The resolved policy is already memoized by `FilterPolicyRegistry`, so what a request costs is a stream over a
few dozen map entries. A cache would have to be keyed on the caller's **resolved role set** rather than on the
entity - anything else serves an administrator's field list to an unprivileged caller - which means a
`CachePurpose.SECURITY` declaration whose TTL is how long a revoked role keeps seeing a field. Paying that
correctness cost to save a map traversal is the wrong trade. If one is ever needed it goes through
`LudwigCacheRegistry` with that purpose and that key, never a `Caffeine` builder of its own.

## Metrics

Optional (only if Micrometer is on the classpath, enabled by default via
`odata.filter.metrics.enabled`): `odata.filter.applied` counts successfully parsed/translated filters,
`odata.filter.rejected` counts every rejection tagged by `entityType` and `reason` (the rejecting
exception's simple name - `FilterSyntaxException`, `FilterAccessDeniedException`,
`PageSizeExceededException`, ...), and `odata.filter.parse.duration` times the whole `parse` call
regardless of outcome. A sustained spike in `rejected` is either a client bug or someone probing for
what's filterable - worth alerting on either way.

`odata.filter.metadata.served` counts documents handed out by the discovery endpoint, tagged by entity. It
exists to be read next to `rejected`: a rise in rejections is either a client bug or someone probing for what
is filterable, and this counter is what tells the clients that asked properly apart from the ones probing -
which the rejection counter alone cannot do.

## Testing

`mvn test` runs the unit test suite (parser, policy resolution, field/role validation, predicate
building) with no external dependencies. `ODataFilterIntegrationTest` additionally spins up a real
PostgreSQL container via Testcontainers and exercises the full HTTP -> predicate -> Postgres path;
it needs a working Docker daemon.
