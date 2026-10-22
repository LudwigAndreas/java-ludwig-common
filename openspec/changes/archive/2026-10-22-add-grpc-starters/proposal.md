## Why

The platform gives a service one governed way to call a partner over HTTP
(`rest-client-spring-boot-starter`) and one governed way to exchange events (outbox and messaging),
and **nothing at all for a typed, synchronous call between two of its own microservices.** There is
no gRPC dependency, no generated contract and no convention anywhere in the reactor; the only trace
is a commented-out `grpc-client` row in the root `README.md`. A team that wants gRPC today would
write its own channel builder, its own interceptors, its own error mapping and its own token
handling - which is exactly the six-copies-of-one-file problem the REST client starter was written
to remove, repeated on a second transport.

Worse, every cross-cutting contract a call passes through is HTTP-shaped and stops at the transport
boundary: the RFC 9457 problem pipeline, the `LudwigPrincipal`, the correlation id, the caller's
locale and zone, the idempotency key, the long-running-operation envelope and the outbound-call
audit record. A hand-rolled gRPC endpoint silently has none of them.

This change adds the starters a service uses to **configure microservice-to-microservice
communication over gRPC**, with those contracts carried across. Which calls a service moves onto
them is the service owner's decision and is not made here.

## What Changes

- **Three new modules**, all libraries in `sources/` parented by the reactor root `common`:
  - `grpc-core` (new, library) - the vocabulary both sides share: metadata key names, the
    problem-to-status codec, the operation message, the call context and the thread-local bridge.
  - `grpc-server-spring-boot-starter` (new, starter) - a gRPC server as configuration: service
    discovery by stereotype, a fixed interceptor chain, deny-by-default authentication, limits,
    health, graceful shutdown, optional idempotent replay.
  - `grpc-client-spring-boot-starter` (new, starter) - outbound gRPC as configuration: N named
    channels under `ludwig.grpc.clients`, each with its own channel, deadline, credentials,
    Resilience4j policy, metrics, logging and audit, consumed as injected stubs, injected channels
    or a registry.
- **Protobuf code generation becomes a build capability.** `build/ludwig-bom` gains the gRPC and
  protobuf versions; the root `pom.xml` gains one managed, opt-in code-generation plugin. A module
  that owns `.proto` files opts in; no other module's build changes.
- **A contract-module convention.** A service publishes its gRPC contract as a
  `<service>-grpc-api` library holding its `.proto` files and the generated stubs. Callers depend
  on that jar instead of keeping a private copy of the DTOs.
- **`scripts/check_protos.sh`**, run by the gate: package and option conventions, a declared
  idempotency level on every method, and no `.proto` outside a contract module or `grpc-core`.
- **`RuleGroup.GRPC`** in `architecture-rules`: no hand-built channel or server outside the two
  starters, a gRPC service is a stereotyped thin adapter, and proxy-based annotations that gRPC
  dispatch silently bypasses are forbidden on it.
- **Documentation**: `README.md` and `README.ru.md` for each new module, the root `README.md`
  module table, a module-conventions bullet in `CLAUDE.md` and the matching block in
  `openspec/config.yaml`.

Nothing existing is removed or changed in behaviour. There is no **BREAKING** change.

## Non-goals

- **Deciding what moves to gRPC.** No call site in `crud-service-example` or
  `notification-service` is migrated, and no existing REST endpoint or `@LudwigRestClient` is
  touched. Partner calls and REST-based enrichment stay on `rest-client-spring-boot-starter`.
- **Changing `rest-client-spring-boot-starter`.** Its token cache and audit record are not
  extracted or moved here; see design D13 for why and for the follow-up it records.
- **Adopting Spring gRPC.** Its 1.0 line targets Spring Boot 4.0 and its 0.9 line Spring Boot 3.5;
  this reactor is on 3.3.5. The starters sit on `grpc-java` directly.
- **A Spring Boot upgrade**, service discovery, an xDS control plane, gRPC-Web, REST transcoding,
  or a gateway.
- **Message-level validation from proto annotations** (protovalidate). Validation stays in the
  service layer and reaches the caller through the problem mapping.
- **Retry for client-streaming and bidirectional calls.** They work; they are not retried.
- **A rule that an internal call must use gRPC.** "Internal" is a fact about a deployment's
  topology that neither bytecode nor source text carries, so it is not written as a check that
  could only pass vacuously.

## Capabilities

### New Capabilities

- `grpc-contract`: what crosses the wire between two platform services regardless of who wrote
  either side - the metadata keys and their relation to the HTTP header names, the mapping between
  a platform problem and a gRPC status with its error details, the protobuf conventions a contract
  module must follow, and the build-time check that enforces them.
- `grpc-server`: the behaviour of a service that exposes gRPC - when the port opens and when it
  does not, which calls are refused before they reach a handler, how identity, correlation and
  caller preferences are present on whichever thread a handler runs, the limits that hold without
  configuration, health and shutdown, and opt-in idempotent replay.
- `grpc-client`: the behaviour of a named gRPC client - configuration inheritance, the mandatory
  deadline and its propagation, the resilience order, retry decided by the contract rather than
  the call site, authentication and its guards, the typed failures a caller sees, isolation
  between clients, and the startup validation that fails the pod instead of the first request.

### Modified Capabilities

- `problem-detail-pipeline`: adds the requirement that a gRPC status is a second *rendering* of
  the one mapper registry, never a second registry, and that a module contributes no gRPC-specific
  exception mapping of its own.
- `long-running-operations`: adds the requirement that the gRPC operation message is a mapping of
  the one envelope, and is the single named exemption from the no-restatement rule.
- `enforcement-triad`: adds `RuleGroup.GRPC` to the ArchUnit side, places the protobuf conventions
  in a gate script because a `.proto` is a resource, and records what in this area cannot be
  mechanised.
- `pom-topology`: adds the requirement that gRPC and protobuf versions live in the BOM, that the
  code generator's version is bound to the runtime's, and that code generation is managed in the
  root POM and opted into per module.

### Unchanged contracts, stated rather than passed over

`audit-envelope`, `cache-purpose`, `i18n-bundles`, `module-dependency-direction`,
`repository-layout`, `starter-conditional-wiring`, `test-layout`, `idempotency-claim` and
`container-image-pinning` are **conformed to, not changed**. The client declares a typed audit
event with a `toAuditEvent()` against the one `AuditSink` and puts no `try`/`catch` around
`record`; both starters ship both locale bundles; the modules sit in `sources/`; tests use the
`{unit,integration,architecture}` layout with integration tests named `...IT`; the server's replay
goes through `IdempotencyStore` and adds no claim mechanism; both starters are inert when present
and unconfigured. No container image is introduced - every test runs on the in-process transport
or loopback.

`data-access` is untouched: none of the three modules has a table, a repository or a changelog.

## Impact

### Modules and the verification gate

| Touched module | POM tier | In-repo dependents the gate must run |
|---|---|---|
| `grpc-core` (new) | library, parent `common` | `grpc-server-spring-boot-starter`, `grpc-client-spring-boot-starter` |
| `grpc-server-spring-boot-starter` (new) | library (starter), parent `common` | none |
| `grpc-client-spring-boot-starter` (new) | library (starter), parent `common` | none |
| `architecture-rules` | library (rules), parent `common` | `crud-service-example`, `file-action-spring-boot-starter`, `file-ingest-spring-boot-starter`, `messaging-spring-boot-starter`, `notification-service`, `object-storage-spring-boot-starter`, `odata-filter-spring-boot-starter`, `pat-spring-boot-starter`, `user-settings-spring-boot-starter` |
| `ludwig-bom` | bom | every module imports it |
| `pom.xml` (root, `common`) | parent of every library | all of them |

`build/jacoco-aggregate/pom.xml` gains the three new modules as dependencies;
`scripts/check_aggregate_report.py` fails the build if a jar module is missing from the report.

Because `ludwig-bom` and the root POM both change, there is no narrower honest gate than
`scripts/manifest.sh build` followed by `mvn clean install`, then `openspec validate
add-grpc-starters`. `ludwig-service-parent` is **not** changed: a service consumes generated stubs
from a contract jar and generates nothing itself.

### Dependencies

New third-party artifacts, all versioned in `build/ludwig-bom/pom.xml`: `grpc-java` (API, stub,
protobuf, services, shaded Netty transport, in-process transport), `protobuf-java` and the common
Google API protos that carry `google.rpc.Status`. One new build plugin, versioned in the root POM.
Spring Boot 3.3.5 manages neither gRPC nor protobuf, so nothing is overridden.

### Shared contracts in `openspec/specs/`

Four are extended (`problem-detail-pipeline`, `long-running-operations`, `enforcement-triad`,
`pom-topology`), each by an added requirement; no existing requirement is modified or removed.

### Interaction with changes in flight

- `add-api-compatibility-and-release-governance` binds a revapi gate to every library parented by
  `common`. The three new modules and every future contract module fall under it, generated
  classes included.
- `add-sonarcloud-analysis` has uncommitted edits to the root `pom.xml` and
  `build/jacoco-aggregate`. This change edits the same two files and must be applied on top of it.

### Deployment

A service that adds the server starter listens on a second port (default 9090) that its
`Deployment`, `Service` and network policy must expose. Callers balance per call only against a
target that resolves to every pod - a headless `Service` - which is a deployment prerequisite the
client's startup validation can warn about but cannot verify.
