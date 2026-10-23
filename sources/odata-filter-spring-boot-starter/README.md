# odata-filter-spring-boot-starter

***English** · [Русский](README.ru.md)*

OData `$filter` / `$top` / `$skip` / `$orderby` / `$count` for Spring Boot REST APIs over Spring Data JPA. Query
options are parsed with Olingo's ABNF tokenizer and translated to type-safe QueryDSL predicates, with production
guardrails: nesting-depth and length limits, a deny-by-default field allow-list that covers associations, role-based
field access, page-size limits, a server-side tie-breaker that keeps paging deterministic, programmatic validators,
an audit record of every filter (paths and operators, never values), and an opt-in per-caller discovery endpoint.

| | |
|---|---|
| Role | starter |
| Depends on (in-repo) | [`audit-core`](../audit-core), [`web-core-spring-boot-starter`](../web-core-spring-boot-starter) |
| Used by | `export`, `user-settings` starters; both services |
| Surfaces | REST query parameters; optional `GET <base-path>/{entity}` discovery |

## Usage

```xml
<dependency>
    <groupId>ru.ludwigandreas</groupId>
    <artifactId>odata-filter-spring-boot-starter</artifactId>
</dependency>
```

### 1. Annotate the entity

Only annotated fields are filterable or sortable; associations included.

```java
@Entity
@FilterPolicy(maxDepth = 4, maxPageSize = 100, defaultPageSize = 20,
        defaultOrderBy = "createdAt desc, id asc")
public class Product {

    @Filterable
    private String name;

    @Filterable(ops = {EQ, GT, GE, LT, LE})
    private BigDecimal price;

    @Filterable(name = "cost", roles = "ROLE_ADMIN")   // own API name, admins only
    private BigDecimal supplierCost;

    private String internalNotes;                      // not annotated -> never filterable

    @Filterable                     // opens Category's own @Filterable fields as "category/code", ...
    @ManyToOne                      // an unannotated association is a wall
    private Category category;
}
```

- **Traversal is opt-in.** Without `@Filterable` on the association, the target's fields stay invisible here.
  `roles` on an association gates the whole subtree.
- **`defaultOrderBy` makes paging correct.** It is **appended** to the caller's `$orderby` (a tie-breaker), so end it
  on a unique column. Without a total order, `$skip=0` and `$skip=20` can repeat or skip rows at production volumes.
  Its paths need no `@Filterable` and are validated against the entity when the policy is first resolved.

### 2. Execute in the repository

```java
@Repository
@RequiredArgsConstructor
class ProductQueryRepositoryImpl implements ProductQueryRepository {

    private static final QProduct PRODUCT = QProduct.product;

    private final ODataQueryExecutor odataExecutor;

    @Override
    public ODataPage<Product> search(ODataQueryOptions options) {
        return odataExecutor.search(Product.class, options, ODataSearch.of(PRODUCT)
                // ANDed into content AND count, so page and total agree - the place for data scope
                .and(dataAccessGuard.predicate("product", DataAction.READ))
                // shapes only the content query (no fetch join on a count)
                .content(query -> query.leftJoin(PRODUCT.category).fetchJoin()));
    }
}
```

One call does predicate, ordering, offset, limit and the count (unless `$count=false`).

**Pass the generated default instance** (`QProduct.product`, alias `product`). A different alias does not fail in
JPQL - it cross-joins the table to itself. The executor refuses a mismatched alias, naming both.

Driving the query yourself (projection, window function): take ordering from
`ODataPaths.orderSpecifiers(Product.class, query.pageable().getSort())`. A `QuerydslPredicateExecutor` repository
also works: `repository.findAll(query.predicate().orElse(null), query.pageable())`.

### 3. Pass options down from the controller

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

`ODataQueryOptions` carries the five raw options; no entity or QueryDSL type appears above the repository
(`architecture-rules`: `web.controllers-do-not-expose-entities`, `layering.controllers-do-not-access-persistence`).
`ODataQueryOptionsOpenApiCustomizer` documents the parameters in OpenAPI when springdoc is present. Its resolver only
fails on a malformed scalar option (400). Aliases without `$` are accepted for gateways that mangle it
(`odata.filter.web.dollar-prefixed-parameters-only=true` disables them).
[`crud-service-example`](../../services/crud-service-example) shows the full arrangement.

`ODataFilterService` (parsing) depends on neither Spring MVC nor JPA, so export can replay saved filters on worker
threads. `ODataQueryExecutor` is conditional on a `JPAQueryFactory`.

**Paging response**: `offset` is authoritative (an unaligned `$skip` has no integer page); `totalElements` and
`totalPages` are absent when `$count=false` (no count query is issued).

### Custom validation

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

Runs after the built-in checks, before the predicate is built.

### Roles

From `SecurityContextHolder` authorities when Spring Security is present; otherwise implement
`FilterPrincipalResolver` as a bean.

### Supported grammar

`and`, `or`, `not`, parentheses; `eq ne gt ge lt le`; `in (v1, ...)`; `contains`, `startswith`, `endswith`; paths
across annotated to-one associations with `/`. Literals: string, integer, decimal, double, boolean, date,
date-time-offset, GUID, enum. Not supported by design: arithmetic, `any`/`all`, `$select`/`$expand`, other functions.
`$count` must be `true` or `false` (anything else is a 400 naming `$count`).

SQL traps: a nested path becomes an inner join, so rows with a null association drop out (also under `ne`/`not`);
`not (status eq 'X')` never matches a null status.

### Discovery endpoint (opt-in)

```yaml
odata:
  filter:
    metadata:
      base-path: /api/v1/filter-metadata   # unset = the endpoint does not exist
```

```java
@FilterPolicy(maxPageSize = 100, defaultOrderBy = "createdAt desc, id asc",
        metadataName = "product")          // unset = this entity publishes nothing (404)
public class ProductEntity { }
```

```
GET /api/v1/filter-metadata/product
```

```json
{
  "entity": "product", "maxDepth": 4, "maxPageSize": 100, "defaultPageSize": 20,
  "maxNestedPropertyDepth": 2, "defaultOrderBy": "createdAt desc, id asc",
  "properties": [
    { "path": "category/code", "type": "string",  "operators": ["contains", "endswith", "eq", "ge", "gt", "in", "le", "lt", "ne", "startswith"], "sortable": true },
    { "path": "name",          "type": "string",  "operators": ["contains", "endswith", "eq", "startswith"], "sortable": true },
    { "path": "price",         "type": "decimal", "operators": ["eq", "ge", "gt", "le", "lt", "ne"], "sortable": true }
  ]
}
```

- Each caller gets its own document: paths it may not use are **absent**; no role names appear.
- Unpublished entity and unknown name both answer 404.
- It is a projection of the resolved policy (a test asserts agreement with the query path), not a second list.
- Not called `$metadata`: it is not an OData CSDL document.
- No cache: the policy is memoized; a role-keyed security cache would cost more than the traversal.

## Configuration

Prefix `odata.filter` (global defaults; `@FilterPolicy` overrides per entity).

| Property | Default | Per-entity override | Meaning |
|---|---|---|---|
| `odata.filter.max-depth` | `4` | `maxDepth` | Filter nesting depth |
| `odata.filter.max-page-size` | `200` | `maxPageSize` | Max `$top` |
| `odata.filter.default-page-size` | `20` | `defaultPageSize` | |
| `odata.filter.max-nested-property-depth` | `2` | `maxNestedPropertyDepth` | Association traversal depth |
| `odata.filter.max-expression-length` | `2048` | - | Raw `$filter` length |
| `odata.filter.page-size-exceeded-strategy` | `REJECT` | - | `REJECT` or `CLAMP` |
| - | - | `defaultOrderBy` | Tie-breaker ordering |
| - | - | `metadataName` | Discovery name |
| `odata.filter.web.dollar-prefixed-parameters-only` | `false` | - | Ignore non-`$` aliases |
| `odata.filter.metrics.enabled` | `true` | - | |
| `odata.filter.metadata.base-path` | unset | - | Discovery endpoint prefix; unset = absent |
| `odata.filter.metadata.enabled` | `true` | - | |

Per field (`@Filterable`): `ops`, `roles`, `sortable`, `name`.

`odata.filter.web.problem-detail-advice-enabled` is still bound by the properties class but has no effect: errors are
always rendered by web-core.

## Deployment

Nothing to deploy. If the discovery endpoint is enabled, decide whether it is public and list it in
`ludwig.security.public-paths` only deliberately.

## Observability

| Metric | Type | Tags | Meaning |
|---|---|---|---|
| `odata.filter.applied` | counter | `entityType` | Filters parsed and translated |
| `odata.filter.rejected` | counter | `entityType`, `reason` (exception simple name) | Rejections. A sustained spike = client bug or probing |
| `odata.filter.parse.duration` | timer | `entityType` | Whole parse, any outcome |
| `odata.filter.metadata.served` | counter | `entityType` | Discovery documents served (tells well-behaved clients from probing) |

**Logs.** `INFO` at startup listing published discovery entities.

**Recommended alerts:** `increase(odata_filter_rejected_total{reason="FilterAccessDeniedException"}[15m])` high for
one caller/entity (probing), and a rejection spike after a client release.

## Audit

Every applied filter is recorded:

| Field | Value |
|---|---|
| category | `query` |
| action | `query.filtered` |
| resource | the entity |
| attributes | `properties` (paths) and `operators` (e.g. `email eq, name contains`) - **never values** |

Values are not hashed or truncated either (a hash is reversible for small value sets; truncation leaks the
identifying prefix); `FilterSummary` is derived from the parsed AST and never reads a literal. The sink is called
without `try`/`catch`: set `ludwig.audit.failure.by-category.query` to `FAIL_OPERATION` if a read must not succeed
without its audit record. The in-process `FilterAppliedEvent` is also published (metrics, cache invalidation).

## Runbook

| Symptom | Likely cause | What to do |
|---|---|---|
| 403 `ludwig.odata.error.field-forbidden` with `property` | Field needs a role the caller lacks | Expected; grant the role or remove the field from the filter |
| 400 `unfilterable-field` | Field or association not `@Filterable` | Annotate it if it should be filterable |
| 400 `depth-exceeded` / `page-size-exceeded` / `syntax` / `invalid-option` | Limits or grammar | Fix the request; adjust `@FilterPolicy` only deliberately |
| Duplicate or missing rows across pages | `defaultOrderBy` missing or not ending on a unique column | Add a unique tie-breaker |
| Rows silently missing when filtering on `association/x` | Inner join drops null associations | Expected SQL behaviour; filter differently |
| Results look multiplied / wrong | Non-default `Q` alias | Use `QEntity.entity` |
| Slow list endpoints | Count query on large tables | Clients send `$count=false` when they do not need totals |

Error codes: `ludwig.odata.error.{syntax, depth-exceeded, field-forbidden, unfilterable-field, page-size-exceeded,
invalid-option, invalid-query, validation, metadata-not-published}`, localized via
`i18n/ludwig-odata-filter-messages`, overridable in the service's bundle.

## Testing

`mvn test`: parser, policy resolution, field/role validation, predicate building. `ODataFilterIntegrationTest`
(Docker, PostgreSQL): HTTP → predicate → database. `NoLocalAdviceTest` fails the build if a `@RestControllerAdvice`
appears in the module.

## Design decisions

- **Deny by default**, including associations and the discovery endpoint.
- **Parse in the repository, not in a controller argument resolver**: no entity in controller signatures, OpenAPI
  documents the parameters, and policy is evaluated after `@PreAuthorize`.
- **One problem pipeline** (web-core) so errors are localized and overridable; no advice in this module.
- **Bounded grammar** for a reviewable surface.
- **Audit records structure, not content.**
