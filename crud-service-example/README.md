# crud-service-example

A production-shaped CRUD microservice - a product catalog - assembled from this repository's own
modules. It exists to show how [`db-core`](../db-core/README.md),
[`odata-filter-spring-boot-starter`](../odata-filter-spring-boot-starter/README.md),
[`outbox-spring-boot-starter`](../outbox-spring-boot-starter/README.md),
[`security-spring-boot-starter`](../security-spring-boot-starter/README.md) and
[`identity-projection-spring-boot-starter`](../identity-projection-spring-boot-starter/README.md) fit
together in one service, and what a layered, boilerplate-free, compile-time-checked implementation on
top of them looks like.

## What it demonstrates

| Requirement | How |
|---|---|
| No hand-written boilerplate | Lombok on the entities, MapStruct for every model-to-model conversion, db-core for ids/auditing/versioning |
| Type-safe data access | QueryDSL-JPA against generated Q-types only - no JDBC, no JPQL/SQL strings, no derived query methods |
| Three model layers | `web.dto` (wire) → `service.model` (domain) → `repository.entity` (JPA), each mapped by a generated mapper |
| Localization | One message bundle drives validation messages *and* RFC 9457 error bodies, resolved per request from `Accept-Language` - all of it from the web-core starter, which this service configures rather than implements |
| Safe query API | OData `$filter`/`$orderby`/`$top`/`$skip`, restricted by per-field, per-role policy on the entity |
| Reliable events | Every write records its event in the transactional outbox in the same transaction |
| Resource-level access | `@PreAuthorize` per endpoint; roles come from the local projection of the OIDC user stream, never from token claims |
| Data-level access | A caller's scope is ANDed into the search query and re-checked on every load by id - one `DataScopeMapping` bean is the only security code this service writes |

## Architecture test

The conventions this service demonstrates - three model layers, no entity past the service layer,
QueryDSL-only data access, transactions in the service layer, configuration read in one place - are
not only described here, they are checked. `ArchitectureTest` enables the shared
[`architecture-rules`](../architecture-rules/README.md) library:

```java
@AnalyzeArchitecture(
        packagesOf = CatalogApplication.class,
        enable = "domain-isolation",
        disable = {"kafka", "storage"})
class ArchitectureTest extends ArchitectureRulesTest {
}
```

39 rules run against this module's bytecode on every build, each as its own named test. Kafka and
object storage are switched off because this service has neither. The names only a service can supply
- the shared base entity from `db-core`, the shared base exception from `web-core` - are declared in
`src/test/resources/architecture-rules.properties`, and every run leaves a console summary plus
`target/architecture-report.json` behind.


## Running it

```bash
docker run --rm -e POSTGRES_DB=catalog -e POSTGRES_USER=catalog -e POSTGRES_PASSWORD=catalog \
  -p 5432:5432 postgres:16-alpine@sha256:cf78e76683b9ca8c5733cbbdce6c9262b45b6767934dd0a95e671f9a0fc20685

mvn install -DskipTests                      # once: publishes the sibling modules locally
mvn -pl crud-service-example spring-boot:run \
  -Dspring-boot.run.profiles=local
```

Liquibase creates the schema - this service's tables, the outbox module's and the identity
projection's - on startup, including three reference categories used by the examples below.

The `local` profile stands in for the things that are not on a laptop: no identity provider, no session
layer, no Kafka broker. Outbox events accumulate as `PENDING` rows instead of being dispatched, the user
projection is not fed, and **the caller comes from request headers** - see `LocalAuthenticationConfig`,
which is gated on both the profile and an explicit switch because it is exactly the backdoor it looks
like. Authorization itself is not bypassed: the principal those headers build goes through the same
`@PreAuthorize` checks and the same data scopes as one minted from a real token, which is what makes the
role experiment below worth running.

```bash
ADMIN=(-H 'X-Local-Subject: alice' -H 'X-Local-Roles: ROLE_CATALOG_ADMIN')
EDITOR=(-H 'X-Local-Subject: bob'  -H 'X-Local-Roles: ROLE_CATALOG_EDITOR')
PARTNER=(-H 'X-Local-Subject: acme' -H 'X-Local-Roles: ROLE_CATALOG_PARTNER' -H 'X-Local-Type: PARTNER')

# create
curl -sS -X POST localhost:8080/api/v1/products "${ADMIN[@]}" -H 'Content-Type: application/json' -d '{
  "sku": "HAMMER-1", "name": "Hammer", "description": "Claw hammer, 450g",
  "price": 19.99, "supplierCost": 9.00, "status": "ACTIVE", "stockQuantity": 10,
  "categoryId": "11111111-1111-1111-1111-111111111111"}'

# search
curl -sS -G localhost:8080/api/v1/products "${ADMIN[@]}" \
  --data-urlencode '$filter=price lt 100 and category/code eq '"'"'TOOLS'"'"'' \
  --data-urlencode '$orderby=price desc' --data-urlencode '$top=20'

# update (version = the one you last read; anything else is a 409)
curl -sS -X PUT localhost:8080/api/v1/products/$ID "${ADMIN[@]}" -H 'Content-Type: application/json' -d '{
  "name": "Hammer Pro", "price": 42.50, "status": "ACTIVE", "stockQuantity": 3,
  "categoryId": "11111111-1111-1111-1111-111111111111", "version": 0}'

# the same error, in Russian
curl -sS localhost:8080/api/v1/products/00000000-0000-0000-0000-000000000000 "${ADMIN[@]}" \
  -H 'Accept-Language: ru'
```

Now change only the caller and watch the same endpoints answer differently:

```bash
# no caller at all -> 401, as an RFC 9457 problem in the caller's language
curl -sS localhost:8080/api/v1/products

# a partner creating a product: the row is stamped with its partner code, taken from the principal
# and never from the body
curl -sS -X POST localhost:8080/api/v1/products "${PARTNER[@]}" -H 'Content-Type: application/json' -d '{
  "sku": "ACME-1", "name": "Partner drill", "price": 120.00, "status": "ACTIVE",
  "stockQuantity": 4, "categoryId": "11111111-1111-1111-1111-111111111111"}'

# ...and that is the only product it can see. The total is right too, because the scope is part of
# the WHERE clause rather than a filter applied to the page afterwards.
curl -sS localhost:8080/api/v1/products "${PARTNER[@]}"

# reading the admin's product by id -> 403, saying nothing about whether it exists
curl -sS localhost:8080/api/v1/products/$ID "${PARTNER[@]}"

# an editor reads the whole catalog but writes only what it created (policy: read ALL, write OWN)
curl -sS localhost:8080/api/v1/products/$ID "${EDITOR[@]}"                     # 200
curl -sS -X DELETE localhost:8080/api/v1/products/$ID "${EDITOR[@]}"           # 403: delete is admin-only
```

## Three models, and why

```
CreateProductRequest / ProductResponse      web.dto           what the API promises
            │  ProductDtoMapper (MapStruct)
NewProduct / ProductUpdate / Product        service.model     what the business logic works with
            │  ProductEntityMapper (MapStruct)
ProductEntity                               repository.entity what the database stores
```

Each boundary is crossed by a generated mapper, and the build runs MapStruct with
`unmappedTargetPolicy=ERROR`: if a field is added to one model and not carried across, the build
fails instead of silently returning `null`. The same applies to the three `ProductStatus`/
`ProductState`/`ProductStatusDto` enums - dropping a constant from one of them is a compile error.

The split earns its keep in concrete ways: `supplierCost` is stored and filterable but has no field
on `ProductResponse`, so it cannot leak; the SKU is immutable, so `ProductUpdate` has no field for
it and the mapper explicitly ignores it; and `Page` never reaches a client, so Spring Data's JSON
shape is not part of this API's contract.

## Compile-time-checked queries only

Every query lives in `ProductQueryRepositoryImpl` and is written with `JPAQueryFactory` against the
generated `QProductEntity`:

```java
queryFactory.selectOne()
        .from(PRODUCT)
        .where(Predicates.allOf(
                PRODUCT.sku.equalsIgnoreCase(sku),
                Predicates.whenNotNull(excludedId, PRODUCT.id::ne)))
        .fetchFirst() != null;
```

There is deliberately no `findBySkuIgnoreCase`-style derived method and no `@Query` string anywhere
in the service: those are parsed from a name or a string, so a renamed or retyped field surfaces as
a startup or runtime failure. Rename `sku` in the entity and this code stops compiling instead.
`Predicates.allOf`/`whenNotNull` come from db-core and keep optional criteria null-safe.

The one runtime-dynamic input is the client's `$filter`, and it is bounded before it reaches a
query: `ODataFilterService` only produces paths the entity's `@Filterable` annotations allow (with
per-field operators, roles and sortability), within the depth and page-size limits of
`@FilterPolicy`/`odata.filter.*`. Anything else is rejected - 400 for an unknown or unfilterable
field, 403 for one the caller's roles don't cover.

## Localization

All of it comes from [`web-core-spring-boot-starter`](../web-core-spring-boot-starter/README.md).
This service has no exception advice, no `MessageSource` configuration and no locale resolver of its
own - it contributes only its own vocabulary:

- four `LocalizedException` subclasses, each naming an outcome (`ProblemStatus.CONFLICT`) and a
  message code plus arguments, never a formatted string;
- `i18n/messages[_ru].properties`, holding *only* this service's codes (`error.product.*`,
  `error.category.*`) and its Bean Validation keys (`{catalog.validation.product.sku.required}`).

Everything else is resolved by the starter's pipeline. The generic HTTP errors (validation, 401, 403,
404, conflict, 500) ship with web-core, the query errors with the OData starter and the 401/403
wording with the security starter - each contributes a message bundle, and resolution consults this
service's bundle *first*, so any of their messages can be reworded by adding that key here. Nothing
has to be configured and nothing has to be re-handled.

That last point is the whole reason the starter exists, and this service is where the problem showed
up. Its `ApiExceptionHandler` used to carry a comment explaining that
`odata.filter.web.problem-detail-advice-enabled` was set to `false` because that starter's messages
were English-only - so this service re-handled all seven of its exception types by hand to keep one
localized error shape. That flag is gone from `application.yml`, along with the advice, the
`LocalizationConfig` and the `PageResponse` DTO: four files of boilerplate that every service copied.

Every response is an RFC 9457 `ProblemDetail` with a stable machine-readable `code` alongside the
localized `title`/`detail`, so clients can branch on the code and show the text. It also carries a
`traceId`, which is what makes the 500 message's "quote the trace id" actionable.

## Events

`ProductServiceImpl` publishes to the outbox inside the same transaction as the write, with an
ordering key per product (a product's own events stay in order) and an idempotency key of
`id:eventType:version` (a retried transaction re-uses the row instead of emitting the change twice).
Routing, dispatch, retry/backoff and dead-lettering are the outbox module's job - see its README.

## Security: two layers, two places

Authorization here is split the way the [security module](../security-spring-boot-starter/README.md)
argues it has to be, and the split is visible in the file layout:

| Layer | Question | Where in this service |
|---|---|---|
| Resource | may this caller use this endpoint? | `@PreAuthorize` on `ProductController` |
| Data | which products, and may they touch *this* one? | the scoped query in `ProductQueryRepositoryImpl`, the guard in `ProductServiceImpl` |

Row rules deliberately do **not** live in controller annotations. Put them there and they end up
enforced on one endpoint and forgotten on the next; put them where the rows are reached and every path
to those rows passes through them.

The only security code this service writes lives in `SecurityConfig`, and it covers all three shapes of
rule a real catalog has:

```java
@Bean
DataScopeMapping<ProductEntity> productDataScopeMapping() {
    QProductEntity product = QProductEntity.productEntity;
    return DataScopeMapping.forResource("product", ProductEntity.class)
            // ownership: one column, one value
            .owner(product.createdBy, ProductEntity::getCreatedBy)
            .partner(product.supplierPartnerId, ProductEntity::getSupplierPartnerId)
            // membership: one product, many watchers - "everything I am named on"
            .bindCollection(WATCHING, product.watcherSubjects.any(), ProductEntity::getWatcherSubjects)
            .build();
}
```

`watcherSubjects` is an `@ElementCollection`, so `any()` is QueryDSL's to-many traversal and JPA renders
it as a correlated `EXISTS`. A join would multiply one product by its watchers and inflate both the page
and its total; the `EXISTS` leaves paging and counting correct. On the load side the check is an
intersection — the product matches when *any* of its watchers is the caller.

The value for that axis cannot come from `application.yml`: the configured policy language compares a
dimension against something the principal already carries (its subject, tenant, partner), and this rule
needs the caller's own subject as the value of a *custom* axis. That is what a `DataScopeProvider` is
for, and it is ten lines:

```java
@Bean
DataScopeProvider watcherDataScopeProvider() {
    return (principal, resourceType, action) ->
            "product".equals(resourceType)
                    && DataAction.READ.equals(action)
                    && principal.hasRole("ROLE_CATALOG_WATCHER")
                    ? DataScope.restrictedTo(WATCHING, principal.subject())
                    : DataScope.none();   // contributes nothing; the configured policies still apply
}
```

Providers are unioned, so a user who is both an editor and a watcher sees the editor's products *and*
the ones they watch. A provider can only ever widen access — denial is expressed by no provider
granting, which is what keeps the empty configuration safe.

`createdBy` needs no column of its own: db-core's auditing already fills it from the authenticated
principal's subject, which is exactly what an `OWN` policy compares against. The policy itself is
configuration (`ludwig.security.data.policies` in `application.yml`), so operations can read and change
it during an incident without a release:

```yaml
product:
  read:
    ROLE_CATALOG_ADMIN: ALL
    ROLE_CATALOG_EDITOR: ALL
    ROLE_CATALOG_PARTNER: PARTNER   # only rows filed under its own partner code
  write:
    ROLE_CATALOG_ADMIN: ALL
    ROLE_CATALOG_EDITOR: OWN        # editors read everything, write only what they created
    ROLE_CATALOG_PARTNER: PARTNER
  delete:
    ROLE_CATALOG_ADMIN: ALL
```

Read and write scopes differ for the same role on purpose. Collapsing them into one "access" concept is
what makes people grant the wider of the two.

Misconfiguration is caught at startup, not on the first request that exercises it: a policy naming a
resource with no mapping, a dimension the mapping does not bind, a misspelled grant (`OWNN`), or a
resource listed as both unscoped and policed all fail the context. Try it — change `OWN` to `OWNN` in
`application.yml` and the service refuses to start, quoting the offending key.

Three details worth copying:

- **`supplier_partner_id` is stamped from the principal, never from the request body.** The mapper is
  explicitly forbidden from filling it. A partner-supplied value would let one partner file products
  under another's id - and then read and edit them, since that column is what their scope matches on.
- **It is not `@Filterable`.** A client able to filter on a scope column can enumerate which values
  return rows.
- **`lookupBySku` stays unscoped.** It is the uniqueness check behind the SKU constraint, not a read of
  someone's data; scoping it would let a caller create a product colliding with one they cannot see, and
  the insert would then fail on a database constraint with an error nobody can explain.
- **Watchers are not settable through create or update.** The mapper is explicitly forbidden from
  filling `watcherSubjects`, because a client able to add itself as a watcher could grant itself read
  access to any product — the column is precisely what its data scope matches on.

Roles come from `identity-projection-spring-boot-starter`: the `security_user` table, fed from the OIDC
provider's Kafka topic, whose changelog this service includes in its own master changelog next to the
outbox module's. `ludwig.security.authorities.require-resolver: true` makes starting *without* that
projection a failure rather than a service where nobody holds any role.

Denials come back as localized RFC 9457 problems under `ludwig.security.error.*`. `@PreAuthorize` and
the data guard throw inside MVC dispatch, after the security filter chain has handed the request over,
so the module's own `AccessDeniedHandler` never sees them - without something handling them there they
would surface as 500s. The web-core starter's advice does, and the security module contributes a mapper
so an in-dispatch denial reports the same code its filter-chain handlers write for the same condition:
a client never has to know which layer denied it.

## Notes on wiring

- `JpaConfig` declares `@EnableJpaRepositories(repositoryBaseClass = BaseRepositoryImpl.class)`,
  which is what gives repositories a working `getByIdOrThrow`.
- It also declares an explicit `@EntityScan`. That is required, not decorative: the outbox starter
  declares an `@EntityScan` for its own entities, and once any `@EntityScan` exists Spring Boot
  stops falling back to auto-configuration packages for entity scanning.
- The Maven build sets `-parameters` (spring-boot-starter-parent would normally do this), without
  which `@PathVariable UUID id` cannot resolve its name.

## Testing

```bash
mvn -pl crud-service-example test
```

`ProductServiceImplTest` covers the business rules with mocks and the real generated mapper, no
Spring context. `CatalogIntegrationTest` runs the whole stack - HTTP through QueryDSL to a real
PostgreSQL container - with the real Liquibase migrations applied and Hibernate validating its
mappings against them (`ddl-auto=validate`), and asserts the CRUD flow, optimistic locking, OData
filtering and its policy rejections, outbox rows, audit columns and both languages. It needs a
working Docker daemon.

The container image is pinned by name, version *and* digest. If your Docker daemon rejects the API
version Testcontainers 1.21.4 defaults to (Docker 29 dropped API versions below 1.40), run the
tests with `-DargLine="-Dapi.version=1.44"`.
