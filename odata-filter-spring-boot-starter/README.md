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

Then parse the caller's options **in the layer that owns the entity**: the repository.

```java
@Repository
@RequiredArgsConstructor
class ProductQueryRepositoryImpl implements ProductQueryRepository {

    private static final QProduct PRODUCT = QProduct.product;

    private final JPAQueryFactory queryFactory;
    private final ODataFilterService filterService;

    @Override
    public Page<Product> search(ProductSearchCriteria criteria) {   // criteria = the raw strings
        ODataQuery<Product> query = filterService.parse(
                Product.class, criteria.filter(), criteria.top(), criteria.skip(), criteria.orderBy());
        Pageable pageable = query.pageable();

        List<Product> content = queryFactory.selectFrom(PRODUCT)
                .where(query.predicate())
                .orderBy(orderSpecifiers(pageable.getSort()))
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        return PageableExecutionUtils.getPage(content, pageable, () -> count(query.predicate()));
    }
}
```

A Spring Data repository is just as good, and shorter - `query.predicate()` is built dynamically via
QueryDSL's `PathBuilder` against Spring Data's default `SimpleEntityPathResolver` alias convention
(the uncapitalized simple class name), so no `QProduct` generation is needed for it to line up:

```java
Page<Product> page = repository.findAll(query.predicate(), query.pageable());
```

The one thing to watch for in hand-written QueryDSL is that alias: a root declared as
`new QProduct("p")` addresses a different alias than the predicate does, and the two would
silently cross-join rather than fail.

The controller takes the options as plain strings and passes them down unparsed, so no JPA entity
and no QueryDSL type ever appears above the repository:

```java
@GetMapping
public PageResponse<ProductResponse> search(
        @RequestParam(name = "$filter", required = false) String filter,
        @RequestParam(name = "$orderby", required = false) String orderBy,
        @RequestParam(name = "$top", required = false) Integer top,
        @RequestParam(name = "$skip", required = false) Integer skip) {
    Page<Product> page = productService.search(new ProductQuery(filter, orderBy, top, skip));
    return PageResponse.of(page.map(mapper::toResponse));
}
```

```
GET /products?$filter=price lt 100 and category/code eq 'TOOLS'&$top=20&$orderby=name desc
```

[`crud-service-example`](../crud-service-example/README.md) is this arrangement end to end:
controller -> service -> repository, with the caller's data scope ANDed into the same `WHERE`
clause as the `$filter`.

### Why not bind it straight into the controller parameter

The module used to document a one-liner: declare `ODataQuery<Product>` as a controller parameter and
let an argument resolver fill it in. That resolver still exists, is deprecated, and is **off by
default** (`odata.filter.web.argument-resolver-enabled=true` brings it back). Four reasons it went:

- `ODataQuery<Product>` names a JPA entity in a controller signature, which
  [`architecture-rules`](../architecture-rules/README.md)' `web.controllers-do-not-expose-entities`
  rejects - it inspects type arguments, so the parameter is caught even when the return type is a DTO.
- What it hands you is a QueryDSL `Predicate`, which only a repository can execute. The controller
  must therefore either hold a repository - `layering.controllers-do-not-access-persistence`, plus a
  query running outside any transaction - or pass the predicate down, which puts the entity in the
  service API instead.
- springdoc cannot describe the parameter, so `$filter`, `$top`, `$skip` and `$orderby` disappear
  from the OpenAPI document. Declared as `@RequestParam`s they document themselves.
- Argument resolution runs before the handler's `@PreAuthorize`, so a caller who may not use the
  endpoint at all can still learn from a 403 which fields are filterable.

Nothing is lost by dropping it: `ODataFilterService` has no dependency on Spring MVC, which is also
what lets a batch job or a GraphQL resolver replay a saved filter.

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

Every applied filter also publishes a `FilterAppliedEvent` (entity type, raw filter, resolved
predicate, caller roles) - write an `@EventListener` for it to build an audit trail.

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

`$count` is not implemented either, and is ignored rather than rejected. It used to be parsed into a
flag on `ODataQuery` that nothing could act on - `Page` always carries a total, and so does the
`PageResponse` these endpoints return, so `$count=false` bought the caller nothing while looking
like it did. Skipping the count query is a decision for the repository (return a `Slice`, or count
conditionally), not something a query parameter can ask for here.

Two further traps worth knowing, both inherited from SQL rather than from this library: a filter
over a nested path (`department/name eq 'Sales'`) becomes an inner join, so rows whose association
is null drop out of the result - including under `ne` and `not` - and three-valued logic means
`not (status eq 'X')` never matches a row whose status is null.

## Error responses

Every exception this module raises extends `ODataFilterException`, and how it reaches the client
depends on one thing: whether
[`web-core-spring-boot-starter`](../web-core-spring-boot-starter/README.md) is on the classpath.

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

## Metrics

Optional (only if Micrometer is on the classpath, enabled by default via
`odata.filter.metrics.enabled`): `odata.filter.applied` counts successfully parsed/translated filters,
`odata.filter.rejected` counts every rejection tagged by `entityType` and `reason` (the rejecting
exception's simple name - `FilterSyntaxException`, `FilterAccessDeniedException`,
`PageSizeExceededException`, ...), and `odata.filter.parse.duration` times the whole `parse` call
regardless of outcome. A sustained spike in `rejected` is either a client bug or someone probing for
what's filterable - worth alerting on either way.

## Testing

`mvn test` runs the unit test suite (parser, policy resolution, field/role validation, predicate
building) with no external dependencies. `ODataFilterIntegrationTest` additionally spins up a real
PostgreSQL container via Testcontainers and exercises the full HTTP -> predicate -> Postgres path;
it needs a working Docker daemon.
