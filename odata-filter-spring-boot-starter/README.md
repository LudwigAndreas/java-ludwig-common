# odata-filter-spring-boot-starter

Enterprise-ready OData `$filter`/`$top`/`$skip`/`$orderby`/`$count` support for Spring Boot + Spring
Data JPA REST APIs. Parses OData query options into type-safe QueryDSL predicates against your
JPA entities, with the guardrails a production API needs: filter nesting-depth limits, a
deny-by-default field allow-list, role-based field access, max page size, and a hook for
programmatic validation. Add it as a dependency, annotate your entities, done.

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

Annotate the entity fields you want filterable/sortable. Everything else is unreachable by
default:

```java
@Entity
@FilterPolicy(maxDepth = 4, maxPageSize = 100, defaultPageSize = 20)
public class Employee {

    @Filterable
    private String name;

    @Filterable
    private Integer age;

    @Filterable(roles = "ROLE_ADMIN")   // only admins may filter/sort on this
    private BigDecimal salary;

    private String internalNotes;       // not annotated -> never filterable

    @ManyToOne
    private Department department;      // Department's own @Filterable fields become
                                         // reachable as "department/name" etc.
}
```

Add an `ODataQuery<T>` parameter to a controller method - it is resolved straight from the
request's query string:

```java
@RestController
@RequestMapping("/employees")
class EmployeeController {

    private final EmployeeRepository repository; // extends JpaRepository<Employee, Long>,
                                                   // QuerydslPredicateExecutor<Employee>

    @GetMapping
    Page<Employee> search(ODataQuery<Employee> query) {
        return repository.findAll(query.predicate(), query.pageable());
    }
}
```

```
GET /employees?$filter=age gt 30 and department/name eq 'Engineering'&$top=20&$orderby=name desc
```

No `QEmployee` generation needed: `query.predicate()` is built dynamically via QueryDSL's
`PathBuilder`, so `QuerydslPredicateExecutor` works out of the box against Spring Data's default
`SimpleEntityPathResolver` alias convention (uncapitalized simple class name).

Prefer not to use the argument resolver? Call the same service directly - it has no dependency on
Spring MVC:

```java
ODataQuery<Employee> query = filterService.parse(Employee.class, filterParam, top, skip, orderBy, count);
```

## What's enforced, and where it's configured

| Concern | Global default (`odata.filter.*`) | Per-entity override (`@FilterPolicy`) |
|---|---|---|
| Filter nesting depth | `max-depth` (4) | `maxDepth` |
| Max page size (`$top`) | `max-page-size` (200) | `maxPageSize` |
| Default page size | `default-page-size` (20) | `defaultPageSize` |
| Association traversal depth | `max-nested-property-depth` (2) | `maxNestedPropertyDepth` |
| Raw `$filter` string length | `max-expression-length` (2048) | - |
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
string functions `contains`/`startswith`/`endswith`; property paths traversing to-one associations
with `/` (e.g. `department/manager/name`). String, integer, decimal, double, boolean, date,
date-time-offset, GUID and enum literals. Not supported (by design, for a bounded, reviewable
surface): arithmetic, `any`/`all`, `$select`/`$expand`, and other OData functions.

## Error responses

Exceptions map to RFC 7807 `ProblemDetail` (400 for malformed/disallowed filters, 403 for
role-restricted fields) via an auto-registered `@RestControllerAdvice`. Disable it with
`odata.filter.web.problem-detail-advice-enabled=false` to handle these exceptions yourself; all of
them extend `ODataFilterException`.

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
