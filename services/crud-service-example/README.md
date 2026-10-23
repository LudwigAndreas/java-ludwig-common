# crud-service-example

***English** · [Русский](README.ru.md)*

A production-shaped CRUD microservice - a product catalog - assembled from this repository's own
modules. It exists to show how [`db-core`](../../sources/db-core/README.md),
[`odata-filter-spring-boot-starter`](../../sources/odata-filter-spring-boot-starter/README.md),
[`outbox-spring-boot-starter`](../../sources/outbox-spring-boot-starter/README.md),
[`security-spring-boot-starter`](../../sources/security-spring-boot-starter/README.md) and
[`identity-projection-spring-boot-starter`](../../sources/identity-projection-spring-boot-starter/README.md) fit
together in one service, and what a layered, boilerplate-free, compile-time-checked implementation on
top of them looks like.

| | |
|---|---|
| Role | reference service (runnable Spring Boot application) |
| Depends on (in-repo) | `ludwig-service-parent`, db-core, odata-filter, outbox, asyncapi, export, file-action, web-core, observability, rest-client, security, identity-projection, grpc-server, `catalog-grpc-api` |
| Used by | nothing - it is the copy-from example |
| Surfaces | REST `/api/v1/**`, gRPC `CatalogService` (port 9090), Kafka via the outbox, `/server/info`, actuator `health`, `info`, `metrics`, `prometheus` |

## What it demonstrates

| Requirement | How |
|---|---|
| No hand-written boilerplate | Lombok on the entities, MapStruct for every model-to-model conversion, db-core for ids/auditing/versioning |
| Type-safe data access | QueryDSL-JPA against generated Q-types only - no JDBC, no JPQL/SQL strings, no derived query methods |
| Three model layers | `web.dto` (wire) → `service.model` (domain) → `repository.entity` (JPA), each mapped by a generated mapper |
| Localization | One message bundle drives validation messages *and* RFC 9457 error bodies, resolved per request from `Accept-Language` - all of it from the web-core starter, which this service configures rather than implements |
| Safe query API | OData `$filter`/`$orderby`/`$top`/`$skip`, restricted by per-field, per-role policy on the entity |
| Reliable events | Every write records its event in the transactional outbox in the same transaction |
| Resource-level access | `@PreAuthorize` on the application service, so REST and gRPC pass the same check; roles come from the local projection of the OIDC user stream, never from token claims |
| gRPC beside REST | `GetProduct` and `GetProducts` from [`catalog-grpc-api`](../../sources/catalog-grpc-api/src/main/proto/ludwig/catalog/v1/catalog.proto) on port 9090, through the [gRPC server starter](../../sources/grpc-server-spring-boot-starter/README.md), mapped by a MapStruct interface |
| Data-level access | A caller's scope is ANDed into the search query and re-checked on every load by id - one `DataScopeMapping` bean is the only security code this service writes |
| Telling a UI which service answered | `GET /server/info` from the [observability starter](../../sources/observability-spring-boot-starter/README.md) - service, version, environment, short commit and build date, nothing else - published by one line in `ludwig.security.public-paths` |
| A user-submitted file performing an action | One `RowBinding`, one `RowHandler` and a block of YAML; the file-action starter owns the upload edge, the bounded spreadsheet reader, the confirm step, the reject report and the operation envelope |

## Architecture test

The conventions this service demonstrates - three model layers, no entity past the service layer,
QueryDSL-only data access, transactions in the service layer, configuration read in one place - are
not only described here, they are checked. `ArchitectureTest` enables the shared
[`architecture-rules`](../../build/architecture-rules/README.md) library:

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
# The platform pins every image by name, version and sha256 digest, and writes that pin down in
# exactly one place: test-support's images.properties. Read it from there rather than copying the
# digest into this README, which would drift from the image the tests actually run.
POSTGRES_IMAGE=$(sed -n 's/^ludwig\.test\.image\.postgres *= *//p' \
  ../../sources/test-support/src/main/resources/images.properties)

docker run --rm -e POSTGRES_DB=catalog -e POSTGRES_USER=catalog -e POSTGRES_PASSWORD=catalog \
  -p 5432:5432 "$POSTGRES_IMAGE"

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

## The published API document

This service serves its OpenAPI document at `/v3/api-docs` and a Swagger UI at `/swagger-ui.html`.
Whether either is reachable from outside is the deployment's authorization decision, exactly as it is
for `notification-service`.

The document is also committed, at `docs/api/crud-service-example.openapi.yaml`, so that a change to
this service's HTTP surface shows up as a diff rather than only inside a running process.
`ApiDocumentIT` regenerates it at `verify` and fails the build when the committed copy no longer
matches what the service serves; `docs/api/README.md` has the refresh command and explains why the
document is canonicalized before it is compared.

Two things in that document are worth knowing. The `ProblemDetail` schema is declared by hand in
`OpenApiConfig` and attached to every operation as its `default` response, because every error here
is produced by `web-core`'s advice rather than returned from a controller - a document inferred from
return types alone would describe only the happy paths. And the five OData query options on the
search endpoint are contributed by `odata-filter-spring-boot-starter`'s customizer, which loads
because springdoc is on this service's classpath; that starter declares springdoc as `optional`, and
an optional dependency is not transitive, which is why this service has to declare it itself.

Its Kafka surface is published the same way, as `docs/api/crud-service-example.asyncapi.yaml`, by `AsyncApiDocumentIT` through `asyncapi-spring-boot-starter`. `CatalogEventTypesConfig` declares what each outbox event carries, and the test properties set `ludwig.outbox.events.require-declared=true`, so an undeclared event fails this service's tests. `scripts/api-docs.sh` refreshes, checks and publishes both documents; see `docs/api/README.md`.

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

All of it comes from [`web-core-spring-boot-starter`](../../sources/web-core-spring-boot-starter/README.md).
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

So this service re-handles none of the starters' exceptions: there is no `@ControllerAdvice`, no
`LocalizationConfig` and no page DTO here. A service that adds a starter gains its message bundle,
not a handler to write.

Every response is an RFC 9457 `ProblemDetail` with a stable machine-readable `code` alongside the
localized `title`/`detail`, so clients can branch on the code and show the text. It also carries a
`traceId`, which is what makes the 500 message's "quote the trace id" actionable.

## Events

`ProductServiceImpl` publishes to the outbox inside the same transaction as the write, with an
ordering key per product (a product's own events stay in order) and an idempotency key of
`id:eventType:version` (a retried transaction re-uses the row instead of emitting the change twice).
Routing, dispatch, retry/backoff and dead-lettering are the outbox module's job - see its README.

## Importing a spreadsheet

A user drags a product sheet into the browser and the products are created. The whole of what this
service writes for it is a row record, a binding and a handler -
`service/fileaction/ProductImportHandler.java` - plus the `ludwig.file-action.actions.product-import`
block in `application.yml`. There is no controller, no multipart handling, no error-report code and no
polling endpoint, because those belong to
[`file-action-spring-boot-starter`](../../sources/file-action-spring-boot-starter).

```
POST /api/v1/file-actions/product-import   (multipart, CATALOG_ADMIN)
   -> 202 + Operation-Location, state VALIDATED, rowsRead 400, rowsApplied 0
POST /api/v1/file-actions/product-import/{id}/confirm
   -> 200, state APPLIED, rowsApplied 398, rowsRejected 2
GET  /api/v1/file-actions/product-import/{id}/rejects
   -> row 14, column SKU, "A product with SKU A-7 already exists..."
GET  /api/v1/file-actions/product-import/{id}/error-report
   -> the submitted workbook with a Problems column
GET  /api/v1/file-actions/product-import/template
   -> a blank workbook whose headings the reader accepts
```

Three things about this action are worth reading the handler for, because each is a decision a real
import has to make and none of them is obvious.

**It writes through `ProductService`, not into a table of its own.** An import is a different *way in*
to an action the service already performs, not a second implementation of it - so the products it
creates get the same validation, the same outbox event and the same audit trail as one created through
the REST API. There is consequently no new Liquibase changeset for this action.

**It is idempotent per row, deliberately.** `RowHandler.apply`'s javadoc states the rule that nothing
can check: a deferred submission is claimed under a lease, so a pod dying after a batch committed but
before progress was recorded means another instance re-applies that batch. The handler looks the SKU up
first and *skips* a row whose product already exists, so applying a row twice applies it once. A handler
calling `create` unconditionally would create duplicates on the day a node is drained, with the build
green.

**A SKU already in the catalogue is a skip; the same SKU twice in one file is a reject.** The first is
not a mistake the user has to fix - and counting it as a reject would push a re-applied submission over
its reject threshold. The second is two rows claiming to be one product, and only the person who made
the sheet knows which is right.

The action is `CONFIRM` mode and `PER_ROW`, and both are choices rather than defaults.
`commit-policy` has no default at all - the module refuses to start an action without one, because
`PER_ROW` would silently apply two thirds of a journal entry and `ALL_OR_NOTHING` would refuse four
hundred products over one typo, and which of those is wrong depends on the domain. `CONFIRM` is right
for an action that creates catalogue records: `DIRECT` would turn one wrong file into four hundred wrong
products with no undo.

`required-authority: CATALOG_ADMIN` is checked on **every** endpoint of the action, including the reads -
a submission's envelope carries the filename and the row counts, and the rejects resource carries the
contents of the user's own cells.

`ProductImportIntegrationTest` exercises all of it against a real multipart request, a real workbook and
real rows in `catalog_product`. It is the reason the starter ships a reference consumer at all: four
defects that no test inside the module could reach were found by this service standing the module up -
a `char(64)` column Hibernate's schema validation rejected, an unqualified `Clock` bean that collided
with `rest-client`'s, an ambiguous `TaskScheduler`, and a `required-authority` that was configured,
documented and never actually checked.

## gRPC

The catalog is served over gRPC beside its REST API: `GetProduct` and `GetProducts` from
[`catalog-grpc-api`](../../sources/catalog-grpc-api/src/main/proto/ludwig/catalog/v1/catalog.proto), whose
whole POM is `ludwig-grpc-api-parent` and an `artifactId`.

- `web.grpc.CatalogGrpcService` is the adapter: it maps the request, calls `ProductService`, and maps the
  answer. Authorization, transactions and the data guard are on the service (see the security section).
- `web.mapper.ProductMessageMapper` is the mapping - `@Mapper(config = ProtobufMapperConfig.class)` and
  nothing written by hand. The supplier cost is in the model and not in the message, as on REST.
- A missing product is `NOT_FOUND` with `error.product.not-found` in its details, an id that is not a UUID
  is `INVALID_ARGUMENT`, a caller without a catalog role is `PERMISSION_DENIED` - the same problems the
  REST API answers, from the same mappers.

Configuration:

| Where | What |
|---|---|
| `application.yml` | port `${GRPC_PORT:9090}`; TLS from the SSL bundle named by `GRPC_TLS_BUNDLE`, which a deployment supplies under `spring.ssl.bundle.pem.<name>` from its secret store. With none, the server refuses to listen in plaintext on every interface and the service does not start. |
| `application-local.yml` | `127.0.0.1`, plaintext - accepted on loopback. Authentication stays on: the `X-Local-*` backdoor is HTTP-only, so a gRPC call needs a token from the configured issuer. |
| tests | in-process, under a name unique to each test context (`src/test/resources/config/application.yml`) - no port, no certificates, and no existing test had to change |

The image exposes 9090 beside 8080 and 8081. A caller inside the cluster reaches the service through a
headless `Service`, so the gRPC client starter balances per call (see its README).

## Security: two layers, two places

Authorization here is split the way the [security module](../../sources/security-spring-boot-starter/README.md)
argues it has to be, and the split is visible in the file layout:

| Layer | Question | Where in this service |
|---|---|---|
| Resource | may this caller read, change or delete products at all? | `@PreAuthorize` on `ProductServiceImpl` - except `create`, on `ProductController` (below) |
| Data | which products, and may they touch *this* one? | the scoped query in `ProductQueryRepositoryImpl`, the guard in `ProductServiceImpl` |

The resource check moved from the controller to the service when the catalog gained a second way in:
gRPC calls `CatalogGrpcService` directly, not through a Spring proxy, so an annotation on that adapter
would never run, and a check left on the controller would not cover it. On the service, every way in
passes the same check. `create` is the exception: its other way in is the spreadsheet import, which
checks the submitter's authority at submission and applies rows later on a worker with no caller - a
role check on the service would refuse every row of a deferred import.

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
- `observability-spring-boot-starter` is declared in this service's own POM. Nothing brings it in
  transitively - export, file-action and rest-client each declare it optional - so without that
  line their observability integrations stay dormant. With it, the console is structured JSON
  outside the `local` profile (text under `local`), every log event carries the service, version
  and commit, a correlation id crosses inbound HTTP, outbound HTTP and Kafka, and tracing runs at
  the starter's default sampling. Nothing under `ludwig.observability` is set here: the defaults
  are the platform's position, and a reference service that overrides them teaches the override.
- `ludwig.security.public-paths` restates the three actuator entries beside `/server/info`. A list
  in YAML replaces the security starter's default rather than extending it, so naming only the new
  path would put the health probes behind authentication. `ServerInfoIT` asserts both halves.

## Deployment

There is no Dockerfile or Kubernetes manifest for this service; it is an example. Build an image with
Jib from [`ludwig-service-parent`](../../build/ludwig-service-parent/README.md#building-an-image)
(`mvn -pl crud-service-example package jib:dockerBuild`), and use
[notification-service's manifest](../notification-service/deploy/k8s/notification-service.yaml) as the
template for probes, resources, secrets and the disruption budget.

| Environment variable | Purpose |
|---|---|
| `CATALOG_DB_URL`, `CATALOG_DB_USER`, `CATALOG_DB_PASSWORD` | PostgreSQL (secret) |
| `OIDC_ISSUER_URI`, `OIDC_TOKEN_URI`, `CATALOG_CLIENT_ID`, `CATALOG_CLIENT_SECRET` | resource server and outbound client credentials (secret) |
| `KAFKA_BOOTSTRAP_SERVERS`, `OUTBOX_POLLING_ENABLED` | outbox relay to Kafka |
| `IDENTITY_PROJECTION_ENABLED`, `OIDC_USER_TOPIC` | the user projection consumer |
| `GRPC_PORT`, `GRPC_TLS_BUNDLE` | gRPC server; **startup fails without a TLS bundle** |
| `PARTNER_MTLS_ENABLED`, `MESH_PROXY_CIDRS` | partner mTLS and trusted proxies |
| `REPORT_POLLER_ENABLED`, `REPORT_OUTPUT_DIR`, `FILE_ACTION_STORAGE_ROOT` | export and import workers and their storage |
| `CATALOG_NOTIFICATIONS_ENABLED`, `NOTIFICATION_BASE_URL`, `CATALOG_STEWARD_USER_IDS` | export completion notices through notification-service |
| `SUPPLIER_DIRECTORY_BASE_URL` | the `@LudwigRestClient` example |
| `SERVER_PORT` | HTTP port, default 8080 |

Liquibase migrates on startup (this service's tables plus the outbox, export, file-action and identity
projection changelogs). Probes: `/actuator/health/liveness` and `/actuator/health/readiness`.

## Observability

Everything comes from the starters: structured JSON logs with service, version and commit; correlation
across HTTP, outbound HTTP and Kafka; tracing; and the meters of
[outbox](../../sources/outbox-spring-boot-starter/README.md#observability),
[export](../../sources/export-spring-boot-starter/README.md#observability),
[file-action](../../sources/file-action-spring-boot-starter/README.md#observability),
[rest-client](../../sources/rest-client-spring-boot-starter/README.md#observability) and
[grpc-server](../../sources/grpc-server-spring-boot-starter/README.md#observability), plus Boot's
`http.server.requests`. Alerts worth having: the outbox's dead-letter counter, export run failures and
the HTTP 5xx rate.

### Known gaps

- **No readiness group.** `management.endpoint.health.probes` is on but no `group.readiness.include` is
  set, so readiness reports `readinessState` only and stays UP with the database down.
  notification-service includes `db`; copy that.
- File-action has no metrics (see its README), so the spreadsheet import is visible only in logs.

## Audit

The service records no audit events of its own. Export and file-action write theirs through
[audit-core](../../sources/audit-core/README.md), which falls back to its SLF4J sink here, since
`audit-spring-boot-starter` is not packaged. Every entity carries db-core's audit columns
(`created_at`, `created_by`, `updated_at`, `updated_by`).

## Runbook

| Symptom | Likely cause | What to do |
|---|---|---|
| Fails to start, gRPC TLS error | No `GRPC_TLS_BUNDLE` | Supply the bundle under `spring.ssl.bundle.pem.<name>` from the secret store |
| `409` on update | Stale `version` in the request | The client re-reads and retries; this is optimistic locking working |
| `403` reading a product that exists | Data scope (partner or OWN policy) | Expected; see "Security: two layers, two places" |
| Events not reaching Kafka | `OUTBOX_POLLING_ENABLED` off or broker unreachable | See the [outbox runbook](../../sources/outbox-spring-boot-starter/README.md#runbook) |
| Partner products missing from searches | Identity projection not fed | `IDENTITY_PROJECTION_ENABLED`, `OIDC_USER_TOPIC`; see the [projection runbook](../../sources/identity-projection-spring-boot-starter/README.md#runbook) |
| Export or import stuck | Worker disabled or storage unwritable | `REPORT_POLLER_ENABLED`, `REPORT_OUTPUT_DIR`, `FILE_ACTION_STORAGE_ROOT`; see those modules' runbooks |

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
