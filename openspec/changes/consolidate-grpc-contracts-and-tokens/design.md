## Context

See `proposal.md` - Why. The state this design starts from, all from `add-grpc-starters` (implemented,
not yet archived, not yet committed):

- A contract module is created by `scripts/new_grpc_api.sh`, which writes a ~60-line POM parented by
  `common` and registers it in three files. Nothing checks a hand-made one except
  `check_protos.sh` (placement and naming of the `.proto`) and, after a full build,
  `check_aggregate_report.py`.
- `grpc-client-spring-boot-starter` holds `auth.CachedToken`, `auth.CachingTokenSource`,
  `auth.ClientCredentialsMinter` and `auth.InboundToken`; `rest-client-spring-boot-starter` holds
  `auth.CachedToken`, `auth.CachingTokenSource`, and inline equivalents of the other two in
  `ClientCredentialsAuthenticationProvider` and `TokenRelayAuthenticationProvider`. No other module
  references any of them.
- MapStruct 1.6.3 is on `ludwig-service-parent`'s processor path (Lombok → binding → MapStruct →
  QueryDSL, versions from the BOM via `annotationProcessorPathsUseDepMgmt`). It cannot map a
  protobuf message: it does not know `setX`/`build()` on a builder, `getXList`/`addAllX`, or `hasX`.
- The API gate (`revapi`, root POM) fails a removal that has no `revapi.differences` entry, whatever
  the version increment; the api-evolution capability requires the baseline release to have
  deprecated what is removed.

## Goals / Non-Goals

**Goals:** a contract module that is one parent reference plus `.proto` files; a gate that catches
the silent mistakes of a hand-made one; protobuf mapping that is a MapStruct interface; one token
implementation. **Non-goals:** see the proposal; additionally, no change to how either client
starter attaches a credential to its transport, and no change to any client's configuration keys.

## Decisions

### D1. `ludwig-grpc-api-parent` is a pom-packaged module in `build/`, parented by `common`

It holds: `ludwig-bom` imported in `dependencyManagement`; dependencies `grpc-protobuf`, `grpc-stub`,
`protobuf-java` and `grpc-core`; and `protobuf-maven-plugin` in `<build><plugins>` (version and
configuration still from the root POM's `pluginManagement`). A contract module's POM is then the
parent with `relativePath ../../build/ludwig-grpc-api-parent/pom.xml`, `artifactId`, `name` and
`description`.

`grpc-core` is always a dependency, not opt-in: it is what puts `ludwig/operation/v1/operation.proto`
on the generator's import path (its jar ships the file) and its generated `Operation` on the compile
path, and every consumer of a contract already depends on `grpc-core` through either starter.

Parented by `common` so that a contract inherits exactly what a library inherits - Checkstyle (which
finds no Java), JaCoCo (which finds nothing to measure, and the aggregate check already accepts a
codeless module), revapi (contracts are published API), enforcer - and nothing a service has.

*Alternative considered:* the root POM detects contract modules by a profile activated on
`src/main/proto`. Rejected: implicit, invisible in the module's own POM, and a profile cannot add
dependencies selectively without the same kind of activation trick.

### D2. The gate check lives in `scripts/project_index.py`, run by `manifest.sh layout`

`classify()` gains two roles, tested before the name buckets: `contract-parent` for
`ludwig-grpc-api-parent` (layout `build/`) and `contract` for any `*-grpc-api` (layout `sources/`).
`layout` additionally fails, naming the module and the missing thing, when a contract:

1. is not parented by `ludwig-grpc-api-parent`;
2. has no entry in `build/ludwig-bom/pom.xml`;
3. is not a dependency of `build/jacoco-aggregate/pom.xml`;
4. has any `.java` under `src/main/java`;

and when a `sources/*-grpc-api/pom.xml` exists with no `<module>` in the root POM.

Why here and not ArchUnit or Checkstyle: every one of these is a fact about POMs and directories,
which bytecode and source-text analysis cannot see - the reasoning `repository-layout` already
records for placement. `gate.sh` already runs `manifest.sh layout`, so no gate change is needed.
`GATE_BY_ROLE` gives `contract` the library gate plus its dependents and `contract-parent` the full
build.

`scripts/new_grpc_api.sh`/`.py` are deleted. Writing ten lines by hand with a check behind them
beats generating sixty with nothing behind them: the check also catches a module made by copying an
existing one, which a generator never sees.

### D3. Protobuf mapping is `no.entur.mapstruct.spi:protobuf-spi-impl` on the services' processor path

Added to `ludwig-service-parent`'s `annotationProcessorPaths` after `mapstruct-processor` (version
1.47, current on Maven Central, as a property in `ludwig-bom`). It is a MapStruct SPI - an accessor
naming strategy and enum-mapping strategy - that teaches MapStruct protobuf builders, repeated
fields, `has` presence checks and the `UNSPECIFIED`/`UNRECOGNIZED` enum constants.

In the service parent, not the contract parent: mappers are written in services, where both the
message and the model are visible; a contract module has no Java. Not in the root POM: no library
in this repository maps protobuf messages, and the SPI is not added where nothing uses it.

The SPI applies to every mapper a service compiles, so its non-interference is proved rather than
assumed: the generated mapper sources of both services are captured before the change and compared
after it (task 6.2). Any difference stops the task.

*Alternative considered:* per-service opt-in. Rejected - MapStruct itself is not opt-in in a
service, and a convention a service must remember to switch on is one a service forgets.

### D4. The worked example is a real gRPC endpoint in `crud-service-example`

New `sources/catalog-grpc-api` (`ludwig.catalog.v1`, `CatalogService.GetProduct`,
`NO_SIDE_EFFECTS`). In `crud-service-example`: the server starter; `web.grpc.CatalogGrpcService`, a
`@LudwigGrpcService` that maps the request, calls the existing product service, and maps the answer
with `web.grpc.ProductMessageMapper` (a MapStruct interface). A missing product is the existing
`ProductNotFoundException`, rendered `NOT_FOUND` by the existing mapper - nothing gRPC-specific.

**Authorization moves to the application service** (owner's decision, taken during task 7.2). The
reference service checked roles with `@PreAuthorize` on `ProductController` and kept only the
data-level `DataAccessGuard` in `ProductServiceImpl`; a gRPC adapter delegating to the service would
have bypassed the role check, and `@PreAuthorize` on the adapter is never applied (D8 of
`add-grpc-starters`). So the role checks of `get`, `search`, `update` and `delete` move onto
`ProductServiceImpl`, where every way in passes them. `create` keeps its check on the controller:
the service's other way in to `create` is the product-import file action, which checks the
submitter's authority when the file is submitted and applies its rows later on a worker with no
caller, and a role check on the service would reject every row of a deferred import. REST
behaviour is unchanged - the same roles, the same 403.

Configuration - a decision because it is the one place this can fail at startup:
- `application.yml`: `ludwig.grpc.server.tls.bundle: grpc`, with `spring.ssl.bundle.pem.grpc` read
  from environment-provided paths - the production shape, TLS and nothing else.
- `application-local.yml`: `address: 127.0.0.1` and no bundle - loopback plaintext, which the server
  accepts without either switch.
- Tests: the gRPC server runs on the in-process transport for the whole test run, so no test needs
  certificates and none opens a port. Where that property is set is decided against the module's
  existing test configuration in task 7.4; the requirement is that no test is changed to accommodate
  the server.
- The image exposes 9090 by overriding jib's `ports` in `crud-service-example`'s own POM (8080, 8081,
  9090), not in `ludwig-service-parent`, which would advertise a gRPC port on every service.

### D5. `sources/outbound-token-core`: what both client starters share, and only that

Package `ru.ludwigandreas.outboundtoken`, zero in-repo dependencies:

| Type | From |
|---|---|
| `CachedToken` | both copies (identical) |
| `CachingTokenSource` - blocking `get(force)` and `getAsync(force)` | rest-client's (the superset) |
| `ClientCredentialsTokenMinter` - a `Supplier<CachedToken>` over a `ClientRegistration`, scopes narrowed when given; failures as `TokenMintingException` naming the registration and the error code | grpc-client's class and rest-client's inline code |
| `InboundBearerToken.current()` - the security context's bearer token, or `null` | grpc-client's class and rest-client's inline code |

`spring-security-oauth2-client` and `spring-security-oauth2-resource-server` are `optional`: a
service using only bearer or no authentication pulls in neither. `slf4j-api` is the only required
dependency.

Each starter keeps its own SPI, its own way of attaching a token (headers for REST, `CallCredentials`
for gRPC), its own counter and its own error types: those are transport-specific, and moving them
would be a second consolidation this change does not need. rest-client keeps its existing messages
by translating `TokenMintingException` where it threw `IllegalStateException` before.

grpc-client's relay keeps reading `GrpcCallContext.INBOUND_BEARER_TOKEN` first and falls back to
`InboundBearerToken.current()`; the library cannot read a gRPC `Context` without depending on
`grpc-api`, and the gRPC half is not shared.

*Enforcement:* an ArchUnit test in each client starter (module-local, the `ModuleIndependenceTest`
precedent) fails when a class in that module depends on
`DefaultClientCredentialsTokenResponseClient` or `AbstractOAuth2Token`, or is named `*TokenSource` or
`CachedToken`. Module-local rather than a `RuleGroup`, because the rule is about two specific modules,
not a platform-wide shape.

### D6. The removal is recorded, not hidden

Two entries in the root POM's `revapi.differences`, code `java.class.removed`, one per removed
rest-client class, each with a justification naming this change, the owner's decision to make 3.0.0
a breaking release without a deprecation cycle, and the replacement type. Every other difference
revapi reports for `rest-client-spring-boot-starter` stays armed; if moving the minter changes a
public signature of `ClientCredentialsAuthenticationProvider` or `TokenRelayAuthenticationProvider`,
that difference gets its own entry with its own justification, not a widened one.

The CHANGELOG (both locales, which `-Prelease` requires) gets a 3.0.0 heading listing the breaking
removal and the replacement import.

## Dependency-direction check

The question for each new edge: does the target already depend on the source, directly or
transitively?

| New edge | Answer |
|---|---|
| `rest-client-spring-boot-starter` → `outbound-token-core` | `outbound-token-core` is new and has no in-repo dependencies, so it cannot reach `rest-client`. No cycle. |
| `grpc-client-spring-boot-starter` → `outbound-token-core` | Same: the target depends on nothing in the reactor. No cycle. |
| `ludwig-grpc-api-parent` → `grpc-core` (and therefore every contract) | `grpc-core`'s in-repo dependents are `grpc-client-spring-boot-starter` and `grpc-server-spring-boot-starter`; `grpc-core` depends only on `web-core-spring-boot-starter`, which has no in-repo dependencies. Neither reaches a contract module. No cycle. |
| `crud-service-example` → `catalog-grpc-api`, `grpc-server-spring-boot-starter` | `crud-service-example`'s `inRepoDependents` is empty: nothing depends on a service. No cycle. |
| `jacoco-aggregate` → `outbound-token-core`, `catalog-grpc-api` | Nothing depends on `jacoco-aggregate`. No cycle. |

`ludwig-grpc-api-parent` → `common` and `catalog-grpc-api` → `ludwig-grpc-api-parent` are parent
relations, not dependencies, and point the same way every parent relation in the reactor does.

## POM changes

- **Root `pom.xml`** - three `<module>` entries (`outbound-token-core`, `ludwig-grpc-api-parent`,
  `catalog-grpc-api`) and the `revapi.differences` entries of D6. No plugin version changes.
- **`ludwig-bom`** - the three new modules and `protobuf-spi.version` (third-party version, so here).
- **`ludwig-service-parent`** - the SPI on the processor path (a services' build decision, so here).
- **New:** `build/ludwig-grpc-api-parent/pom.xml`, `sources/outbound-token-core/pom.xml`,
  `sources/catalog-grpc-api/pom.xml` (the parent, its `artifactId`, name, description).
- **Changed module POMs:** both client starters (dependency on `outbound-token-core`;
  `rest-client` loses nothing else), `crud-service-example` (server starter, `catalog-grpc-api`, jib
  ports), `build/jacoco-aggregate` (two jar modules; the parent is pom-packaged and is not listed).

## Enforcement - every rule twice

| Rule | Check | Owner |
|---|---|---|
| A contract module inherits the contract parent and is registered in reactor, BOM, aggregate | `manifest.sh layout` (D2) | gate script |
| A contract module has no Java | `manifest.sh layout` (D2) | gate script |
| `ludwig-grpc-api-parent` lives in `build/`, contracts in `sources/` | `manifest.sh layout` | gate script |
| No client starter declares its own token cache, minter or inbound-token reader | module-local ArchUnit test in each client starter (D5) | `architecture-rules`' domain, module-local |
| The SPI changes no existing mapper | before/after comparison of generated sources, task 6.2 - a one-time proof, not a standing check: there is no baseline of generated code to compare a future build against, and a standing check would have to commit generated code | recorded here |
| A removal without deprecation is visible | `revapi.differences` entries with justification (D6) | revapi |

## Risks / Trade-offs

- **The API gate cannot run from this machine** (the releases repository is unreachable - recorded in
  `add-grpc-starters`) → the revapi entries are written against what revapi is expected to report and
  proved on the first CI build. A difference not anticipated fails that build loudly, which is the
  gate working; it is not hidden here.
- **The SPI rewrites accessor naming for the whole compilation** → the before/after comparison of
  every generated mapper (D3); if it differs, the SPI moves behind a per-service property instead.
- **Every `crud-service-example` test now starts a gRPC server** → in-process, no port, no TLS; the
  test suite's duration is compared before and after, and a material increase is reported.
- **External users of `restclient.auth.CachedToken`/`CachingTokenSource` break on upgrade** → the
  owner's stated intent for 3.0.0; the CHANGELOG names the one-line import change.

## Migration Plan

Order, each step leaving a green build: the token library and both starters moved onto it; the
contract parent, the layout checks and the script's removal; the processor SPI with its proof; the
worked example; documentation; the full gate. Release: tag `v3.0.0` on `master` once
`add-grpc-starters` and this change are merged; `.mvn/maven.config` is not edited. Rollback is a
revert of this change; nothing persistent is migrated.
