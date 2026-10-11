## Context

See `proposal.md` for motivation. The constraints that shape the approach:

- **Spring Boot 3.3.5, Java 17.** Boot 3.3.5 manages neither gRPC nor protobuf (its only mention of
  `protobuf-java` is an exclusion), and it manages Netty 4.1.114.
- **Every cross-cutting contract is ambient and HTTP-fed.** `ProblemMapperRegistry.resolve(Throwable)`
  and `ProblemDetailFactory.create(LocalizedException, String)` take no servlet type;
  `UserPreferenceSource.locale()/zone()` and `UserPreferenceResolver.resolve()` take no argument at
  all; `CorrelationContext.open(String)` returns a scope; `IdempotencyStore` is a plain interface.
  All of them can be driven from a gRPC call without a change to the module that owns them.
- **`rest-client-spring-boot-starter` is the precedent for the client's shape**: named clients under
  a `defaults` block with an explicit deep merge, one pipeline behind three programming models, a
  non-configurable decorator order, a startup validator that reports every problem at once, and an
  `spi` package that depends on nothing else in the module.
- **A revapi gate covers every library parented by `common`**, so what these modules publish is a
  promise from the first release.
- gRPC delivers a call's events on whichever executor thread is free. Anything this platform keeps
  in a thread-local - `SecurityContext`, MDC, the bound `UserPreferences` - is absent by default on
  the thread a handler runs on.

## Goals / Non-Goals

**Goals:**

- A service exposes or calls a gRPC service by adding a starter and writing configuration, with no
  `@Configuration` class of its own.
- A call that crosses the transport keeps its principal, correlation id, locale, zone, idempotency
  key, problem semantics and audit record.
- The safe behaviour is the default behaviour: authenticated, deadline-bound, size-limited,
  encrypted, and not retried unless the contract says a repeat is safe.
- Every new convention has a check, or a recorded reason why none is possible.

**Non-Goals:**

- Parity with every `rest-client` key. A key exists here only where gRPC has the concept.
- Sharing code with `rest-client-spring-boot-starter` in this change (D13).
- Any behaviour for client-streaming or bidirectional calls beyond passing them through the
  interceptor chain.

## Decisions

### D1. `grpc-java` directly, with the platform's own auto-configuration

Spring gRPC 1.0 targets Boot 4.0 and 0.9 targets Boot 3.5. The community `net.devh` starter runs on
3.3 but brings its own property namespace, its own security adapter and its own interceptor
ordering, each of which would have to be overridden to reach the behaviour below - the starter
would be a layer of exclusions. The BOM already prefers individual Resilience4j modules over
`resilience4j-spring-boot3` for the same reason.

Properties live under `ludwig.grpc.*`, never `spring.grpc.*`, so a later Boot upgrade that brings
Spring gRPC onto the classpath cannot bind the same keys with a different meaning. The transport is
built in one class per side, which is what a future move onto Spring gRPC's server factory replaces.

*Alternative considered:* upgrade Boot first. Rejected as a prerequisite: it is a reactor-wide
change with its own risk, and nothing here depends on it.

### D2. Three modules, and `grpc-core` carries no transport

```
grpc-core ◀── grpc-server-spring-boot-starter
    ▲
    └──────── grpc-client-spring-boot-starter
```

A service that only calls others must not open a port or pull a server; that is the same split as
`web-core` and `rest-client`. `grpc-core` exists because both sides need the same metadata keys and
the same codec, and a constant declared twice is two constants. It depends on `grpc-api`,
`grpc-protobuf` and `web-core-spring-boot-starter`, and on **no Netty artifact** - a
`ModuleIndependenceTest` asserts that, so the codec stays usable from a test or a tool.

Package roots: `ru.ludwigandreas.grpc.core`, `ru.ludwigandreas.grpc.server`,
`ru.ludwigandreas.grpc.client`.

### D3. Contracts are `<service>-grpc-api` libraries; generation is opt-in

A `.proto` lives in `src/main/proto` of a module named `<service>-grpc-api`, in `sources/`, parented
by `common`. It holds the proto files and the generated messages and stubs, and nothing else. The
existing `repository-layout` rule already places it: it is a library.

- proto `package ludwig.<service>.v<N>`; `java_package = "ru.ludwigandreas.<service>.grpc.v<N>"`;
  `java_multiple_files = true`. The version is in the package so `v2` can be served beside `v1`.
- Every `rpc` declares `option idempotency_level`. `IDEMPOTENCY_UNKNOWN` is allowed but must be
  written, because the client's retry decision reads it (D12) and "nobody thought about it" must
  not be indistinguishable from "it is not safe".
- Generation uses `io.github.ascopes:protobuf-maven-plugin`, declared in the root POM's
  `pluginManagement` and bound in a module only when that module declares it. It resolves `protoc`
  and the gRPC generator as Maven artifacts, so the build needs no system `protoc` and works behind
  the corporate mirror. `protoc`'s version is the `protobuf.version` property the BOM pins for the
  runtime; generated code and runtime cannot drift.
- Checkstyle already scans `src/main/java` only (root `pom.xml`, `sourceDirectories`), so generated
  sources are not style-checked. They are excluded from JaCoCo and Sonar by package pattern
  (`**/grpc/v*/**`), because a generated builder's coverage measures nothing.

*Alternative considered:* `org.xolstice` plugin, which the already-present `os-maven-plugin`
extension would support. Rejected: unmaintained, and it needs the extension that the chosen plugin
does not.

*Alternative considered:* protos inside the owning service. Rejected: a caller would depend on a
service's jar, which `ludwig-service-parent` builds as an executable image.

`grpc-core` owns one proto of its own (D10) and each starter keeps a test-only echo service in
`src/test/proto`. Those are the only `.proto` files outside a contract module, and
`check_protos.sh` names them.

### D4. The server is shaded Netty on its own port

`grpc-netty-shaded` on `ludwig.grpc.server.port` (default 9090), not gRPC-over-servlet on Tomcat.
Shading keeps gRPC's Netty independent of the 4.1.114 that Boot manages for Reactor Netty, so
neither can break the other on an upgrade. A separate port keeps HTTP/2 flow control, keepalive and
message limits out of Tomcat's configuration, and lets a network policy expose gRPC to peers
without exposing the REST surface.

The server is a `SmartLifecycle`. With no stereotyped service bean it does not start and binds
nothing. On shutdown: health turns `NOT_SERVING`, the server stops accepting, in-flight calls get
`ludwig.grpc.server.shutdown-grace` (default 30s), then are cancelled.

Defaults that hold without configuration:

| Key | Default | Why |
|---|---|---|
| `max-inbound-message-size` | 4 MiB | gRPC's own default, stated rather than inherited |
| `max-inbound-metadata-size` | 8 KiB | a JWT fits; a header bomb does not |
| `max-connection-age` / `-grace` | 30m / 30s | forces callers to re-resolve, which is what rebalances after a scale-out |
| `max-concurrent-calls-per-connection` | 100 | one caller cannot monopolise a pod |
| `keepalive.permit-time` | 30s | must not exceed the client's default keepalive, or the server sends `GOAWAY` for too many pings |
| `executor.threads` / `.queue-capacity` | 64 / 256 | bounded; saturation answers `RESOURCE_EXHAUSTED` instead of queueing without limit |
| `reflection.enabled` | `false` | and refused outright under a production profile |

`grpc.health.v1.Health` is always registered and follows Spring's `ReadinessState`, so a Kubernetes
gRPC probe and the HTTP readiness endpoint cannot disagree.

### D5. A fixed server interceptor chain

```
observation → access log → exception→status → authentication
            → context bridge (correlation, principal, preferences) → [idempotent replay] → handler
```

Not configurable, for the reason `rest-client`'s decorator order is not: every other order has a
concrete defect. The exception translator is outside authentication so a rejected credential is
rendered through the same pipeline as everything else; observation is outermost so a refused call
is still measured. A service adds its own interceptors as `LudwigGrpcServerInterceptor` beans, which
run **after** the built-in chain and cannot be ordered before it.

### D6. Thread-locals are restored on every callback, from the gRPC `Context`

The principal, correlation id and resolved preferences are stored under `Context` keys in
`grpc-core`. A listener wrapper re-establishes `SecurityContextHolder`, the MDC scope from
`CorrelationContext.open` and `UserPreferences.bind()` around **each** of `onMessage`,
`onHalfClose`, `onCancel` and `onComplete`, and clears them afterwards.

Setting them once in `interceptCall` is the common implementation and is wrong: `interceptCall`
returns before the request message arrives, and the handler runs later on a thread that may differ.
It passes every single-threaded test. The integration test for this decision therefore runs with a
multi-thread executor and asserts on the handler's thread, not the interceptor's.

Preferences reach the handler through the existing resolver: the starter contributes a
`UserPreferenceSource` that reads the locale and zone metadata from the current `Context`. No second
type holding the pair is declared, so `RuleGroup.PRESENTATION` stays satisfied.

### D7. Authentication is deny-by-default and reuses the resource server's beans

Every method requires an authenticated caller except `grpc.health.v1.Health` and, where enabled,
reflection. A bearer token in `authorization` metadata is decoded by the application's `JwtDecoder`
and converted by the same `Converter<Jwt, AbstractAuthenticationToken>` the HTTP chain uses
(`JwtPrincipalConverter`), so a gRPC caller and an HTTP caller with the same token are the same
`LudwigAuthentication` with the same authorities from the same `AuthorityCaches`. A PAT is not
accepted directly, consistent with `add-personal-access-tokens` D1: it is exchanged at the edge.

Identity-bearing metadata a caller could forge is stripped before the handler, mirroring
`IdentityHeaderStrippingFilter`.

**Fail closed when security is absent.** With the server starter present, at least one service bean
and no `JwtDecoder`, the application refuses to start unless
`ludwig.grpc.server.security.enabled=false` is set explicitly. A server that silently serves
unauthenticated because a dependency was dropped is the failure this prevents.

`ludwig.grpc.server.security.permit-unauthenticated` lists full method names. It exists for a
deliberate public method and is reported at startup at WARN, one line per entry.

### D8. A gRPC service is a thin adapter, and proxy annotations on it are forbidden

A service is a bean annotated `@LudwigGrpcService` extending its generated base class. It converts
messages and delegates to an application service bean; authorization (`@PreAuthorize`,
`DataAccessGuard`) and transactions live there, where they already do.

This is not a style preference. gRPC dispatches through the `ServerServiceDefinition` that
`bindService()` builds, whose handlers hold the **target** object. A CGLIB proxy around the service
bean is never on the call path, so `@PreAuthorize` or `@Transactional` on a gRPC method compiles,
starts, and does nothing. `RuleGroup.GRPC` fails the build on either annotation on a stereotyped
service (and on a direct repository dependency), because a silently ignored authorization rule is
the worst outcome available here.

### D9. One problem registry, a second rendering

Server side, an exception is resolved by `ProblemMapperRegistry` exactly as for HTTP, and the
resulting problem is rendered as `google.rpc.Status`:

| Problem status | gRPC code | | Problem status | gRPC code |
|---|---|---|---|---|
| 400, 405, 406, 415 | `INVALID_ARGUMENT` | | 429 | `RESOURCE_EXHAUSTED` |
| 401 | `UNAUTHENTICATED` | | 500 | `INTERNAL` |
| 403 | `PERMISSION_DENIED` | | 501 | `UNIMPLEMENTED` |
| 404, 410 | `NOT_FOUND` | | 502, 503 | `UNAVAILABLE` |
| 409, 423 | `ABORTED` | | 504 | `DEADLINE_EXCEEDED` |
| 412, 422 | `FAILED_PRECONDITION` | | 413 | `RESOURCE_EXHAUSTED` |

The table is total over `ProblemStatus` and a unit test iterates the enum, so a status added to
`web-core` without a row here fails the build rather than falling through to `UNKNOWN`.

Details: `ErrorInfo` carries the problem code as `reason`, the service as `domain`, and the original
HTTP status, problem type URI and correlation id as metadata - which is what lets the client rebuild
the exact `ProblemDetail`. `BadRequest.field_violations` carries `Violation`s; `LocalizedMessage`
carries the detail in the caller's locale; `RetryInfo` carries a retry-after where the problem has
one. An exception no mapper claims becomes `INTERNAL` with the correlation id and **no message
text**, matching the pipeline's existing no-leak requirement.

Client side the codec runs in reverse into a typed exception (D11) that carries the parsed
`ProblemDetail`. A module contributes exception mappers to `web-core` as before and never writes a
gRPC-specific one.

### D10. The operation message is a mapping, and the one exemption

`grpc-core` ships `ludwig/operation/v1/operation.proto`: an `Operation` message with status, detail,
progress, result reference, failure and a `retry_after` duration, mapped to and from
`OperationResponse` by one mapper. Its status enum necessarily restates the six states plus
proto3's mandatory zero value. `OperationVocabularyRules` gains a by-name exemption for that single
generated type, in the style of `CachingRules.EXEMPT_TYPES`, with javadoc saying why: a wire format
cannot import a Java enum, so the alternative is each contract module restating it, which is the
thing the rule exists to stop. Every contract module imports this proto instead of declaring its
own.

There is still no shared operation table and `grpc-core` has no persistence dependency.

### D11. Named clients, one pipeline, three programming models

```yaml
ludwig:
  grpc:
    client:
      defaults:
        deadline: 5s
        resilience:
          circuit-breaker: { failure-rate-threshold: 50, sliding-window-size: 100 }
      clients:
        notifications:
          target: dns:///notification-service-headless:9090
          deadline: 3s
          auth: { type: oauth2-client-credentials, registration-id: catalog-sa }
```

Merge rules are `rest-client`'s, restated in this module's own merger: scalars override when
non-null, nested blocks merge field by field, lists replace, a declared `bulkhead` or `rate-limiter`
block enables that policy, and `target` and `auth.relay-enabled` are **never inherited**.

One `ManagedChannel` per named client, never shared - two clients to the same host get two channels
for the reason two REST clients get two pools. Consumption:

1. **Injected stub** (primary): a field or constructor parameter of a generated blocking, future or
   async stub type annotated `@LudwigGrpcClient("notifications")`.
2. **Injected channel**: a `Channel` bean named `<name>GrpcChannel`, qualified `<name>`. It is the
   intercepted channel, so a hand-built stub still runs the whole pipeline.
3. **Registry**: `GrpcChannelRegistry.channel("notifications")`; an unknown name throws and lists
   the configured ones.

Failures surface as one hierarchy, each carrying the client name and correlation id in its message:
`GrpcClientResponseException` (the peer answered with a failure; carries code, parsed
`ProblemDetail`, details), `GrpcClientTimeoutException` (deadline passed; the request **may** have
been processed), `GrpcClientConnectionException`, `GrpcClientAuthenticationException`,
`GrpcClientCallNotPermittedException`. With `web-core` present, one escaping a controller renders as
`upstream-unavailable` (503) for a peer or transport failure, 429 for `RESOURCE_EXHAUSTED`, and
**500 for a caller-side code** such as `INVALID_ARGUMENT` - this service sent the peer something
invalid, and passing it through would blame our own caller. Same reasoning as `rest-client`.

### D12. Deadlines are mandatory; retry is decided by the contract

**Deadline.** Every call gets `min(configured deadline, remaining inbound deadline)`. gRPC's
`Context` already carries an inbound deadline to outbound calls; the starter adds the configured
ceiling (built-in default 5s) and refuses, at startup, a client whose deadline is not positive. A deadline bounds the
**logical call including retries** - gRPC has no stable per-attempt timeout, and this design does
not invent one.

**Order**, outermost first, not configurable:

```
RateLimiter → Bulkhead → CircuitBreaker → [deadline] → retry (in-channel) → [authenticate → send]
```

The first three are Resilience4j objects in the shared Micrometer-bound registries, named after the
client, applied in one `ClientInterceptor` that takes a permit at call start and records one result
at `onClose`. That works identically for blocking, future and async stubs, and makes the breaker
count logical calls - retry inside the breaker, as in `rest-client`, for the same amplification
argument.

**Retry is gRPC's own**, through a service config the starter generates per client: retry policy
with jittered exponential backoff, retry throttling, and server pushback honoured. It is preferred
over a hand-written interceptor because it is commit-aware (it stops once response headers arrive),
and replaying a stream correctly from an interceptor is the kind of code that works until it loses a
message.

**Which methods are retried is read from the proto**, not from the call site. For a stub bound to a
named client the starter reads each method's `idempotency_level` from the service descriptor:
`NO_SIDE_EFFECTS` and `IDEMPOTENT` methods get the retry policy on `UNAVAILABLE` (plus
`retry-on-status` if configured); any other method gets none, leaving only gRPC's transparent retry
of a request that provably never left the process. There is no equivalent of `X-Ludwig-Retry`: a
method that is safe to repeat because it carries an idempotency key says so once, in the contract,
where the server's owner can see it.

**What the breaker counts**: `UNAVAILABLE`, `DEADLINE_EXCEEDED`, `INTERNAL`, `UNKNOWN`,
`RESOURCE_EXHAUSTED`, `DATA_LOSS`. Not `INVALID_ARGUMENT`, `NOT_FOUND`, `ALREADY_EXISTS`,
`ABORTED`, `FAILED_PRECONDITION`, `OUT_OF_RANGE`, `PERMISSION_DENIED`, `UNAUTHENTICATED`,
`UNIMPLEMENTED` or `CANCELLED` - the peer answered and is healthy. A refused call and an
authentication failure are not the dependency's failure and are not recorded.

Refreshable without a restart (re-read from the bound properties as `rest-client` does): `deadline`,
logging level, rate-limiter limits, audit sampling. Everything baked into the channel - target,
credentials, TLS, service config, breaker window - needs a restart, and the README table says which.

### D13. Authentication types, and a deliberate, recorded duplicate

`auth.type`: `none`, `bearer`, `oauth2-client-credentials`, `oauth2-token-relay`, `custom`, plus any
type a service adds by publishing a provider bean - the same SPI shape as `rest-client`. Credentials
are attached as gRPC `CallCredentials`. Token relay keeps all three guards: `relay-enabled` on the
client itself and never inherited, fail rather than call anonymously when there is no inbound token,
and startup refusal in a service that is not a resource server. On `UNAUTHENTICATED` a unary call
discards the cached token and is attempted **once** more, outside the retry budget.

The machine-token cache needs the same properties as `rest-client`'s `CachingTokenSource`: early
refresh by a skew, single-flight minting. That class is 152 lines with no dependency on the rest of
its module, and this change **writes the equivalent again in `grpc-client` rather than moving it.**

- Moving it changes a published type's package in `rest-client-spring-boot-starter`, which the
  revapi gate classifies as binary-breaking and which needs the deprecation cycle
  `add-api-compatibility-and-release-governance` defines.
- Depending on `rest-client-spring-boot-starter` from a gRPC client drags three HTTP engines onto
  the classpath of a service that makes no HTTP call.

The duplicate is recorded, not hidden: a comment at the class names its twin, the README states it,
and the follow-up - extract both into one small library under the deprecation policy - is listed in
this module's README as owed work. The audit record is **not** a duplicate: `GrpcCallAudit` is this
module's own typed event with a `toAuditEvent()` into the one `AuditSink` under
`category=outbound-call`, which is the convention, not a second mechanism.

*Alternative considered:* extract now. Rejected for this change only; it would add a refactor of a
starter with four dependents to a change whose subject is a different transport.

### D14. TLS by default; there is no "trust the mesh" mode

`ludwig.grpc.client.clients.<name>.tls` and `ludwig.grpc.server.tls` configure truststore, keystore
(mTLS), protocols and, on the server, `client-auth: none | optional | require`. Plaintext is
accepted to or on a loopback address, and otherwise only with `plaintext: true` on the client or
server **and** top-level `ludwig.grpc.allow-plaintext: true`, and never with one of
`ludwig.grpc.production-profiles` active - the two-switch shape `rest-client` uses for `trust-all`.
A credential over plaintext to a non-loopback target is a startup failure.

There is deliberately no mode that sends plaintext because a sidecar is expected to encrypt. That
position is already stated in this repository, in `crud-service-example`'s `application.yml`: a
sidecar that is misconfigured or absent is exactly when the token is readable on the wire, and that
is the case a validator has to assume. mTLS here authenticates the transport; the principal still
comes from the token.

### D15. Per-call balancing is the default

`load-balancing: round_robin` with a `dns:///` target. HTTP/2 multiplexes every call onto one
long-lived connection, so the per-connection balancing a Kubernetes `Service` does - sufficient for
`rest-client`, as its README says - pins a gRPC caller to one pod. `round_robin` over a headless
`Service` balances per call, and the server's `max-connection-age` (D4) makes callers re-resolve so
new pods receive traffic. `pick_first` is accepted and logged at WARN at startup, because whether it
is correct depends on what the target resolves to, which the process cannot know.

### D16. Idempotent replay reuses the store and the annotation

With `idempotency-spring-boot-starter` present, a **unary** method carrying the existing
`@Idempotent` annotation is claimed through `IdempotencyStore` using the key from metadata, with the
method's full name as the default scope and a digest of the request message as the fingerprint. The
annotation is read reflectively from the service class at bind time, so D8's proxy problem does not
apply. A completed claim replays the stored response message and marks the reply with the replayed
metadata key; a claim in progress and a key reused with a different request are rendered through the
idempotency module's existing problems; a failed call is not stored. `required = true` with no key
is `INVALID_ARGUMENT`.

The stored response uses the existing `StoredResponse` (status 200, content type
`application/grpc+proto`, body the serialized message) and the claim mode `IdempotencyFilter` uses.
No table, no claim statement and no SQL is added; the two carve-outs stay at two.

### D17. Observability and audit

- **Tracing and RED metrics** from Micrometer's own gRPC observation interceptors, already on the
  classpath with Micrometer 1.13, retagged to `ludwig.grpcclient.requests{client,service,method,status}`
  and `ludwig.grpcserver.requests{service,method,status}`. Service and method names come from the
  contract, so cardinality is bounded by construction and no URI-style cap is needed.
- **Client counters**: `ludwig.grpcclient.retries{client,method}` from a per-attempt stream tracer
  (in-channel retries are invisible to an interceptor), `ludwig.grpcclient.calls.notpermitted{client,policy}`,
  `ludwig.grpcclient.auth.token.refreshes{client,type}`.
- **Server counter** `ludwig.grpcserver.calls.nodeadline{service,method}`: a caller that sends no
  deadline is a caller that can hold a thread forever, and this is how its owner finds out.
- **Logging** per client on `ludwig.grpcclient.<client>` and per service on
  `ludwig.grpcserver.<service>`: method, status, duration, attempt count. **Message payloads are
  never logged by default**; `logging.level: payload` renders through `audit-core`'s `Redactor`
  with a size cap and is refused under a production profile.
- **Correlation**: the id from `CorrelationContext` goes out as metadata and is opened on the server
  (D6). With `observability-spring-boot-starter` absent, calls work and carry none.
- **Audit**: opt-in per client, sampling successes only, always recording failures, refusals and
  authentication errors. `NOT_PERMITTED` is outcome `DENIED`. No payloads and no metadata values
  that were not allow-listed.

### D18. Metadata keys are the HTTP header names, lower-cased

`idempotency-key`, the correlation id key, the locale and zone keys and `retry-after` are derived in
one `GrpcMetadataKeys` class from the constants their owning modules already declare
(`IdempotencyHeaders.KEY`, the preference and correlation header names). One name per concept across
both transports means a gateway that transcodes needs no mapping table, and a rename in the owning
module moves both.

### Dependency-direction check

The question, for each in-repo dependency this change adds: *does the target already depend on the
source, directly or transitively?*

| New edge | `inRepoDependents` of the source today | Cycle? |
|---|---|---|
| `grpc-core` → `web-core-spring-boot-starter` | `grpc-core` is new and absent from `project-index.json`; nothing depends on it | no |
| `grpc-server-spring-boot-starter` → `grpc-core`, `web-core-spring-boot-starter`, `security-spring-boot-starter` (optional), `observability-spring-boot-starter` (optional), `idempotency-spring-boot-starter` (optional), `test-support` (test), `test-support-security` (test), `architecture-rules` (test) | new module, no dependents | no |
| `grpc-client-spring-boot-starter` → `grpc-core`, `web-core-spring-boot-starter`, `audit-core`, `observability-spring-boot-starter` (optional), `security-spring-boot-starter` (optional), `test-support` (test), `architecture-rules` (test) | new module, no dependents | no |
| `jacoco-aggregate` → the three new modules | nothing depends on `jacoco-aggregate` | no |

A cycle needs a path from a target back to a source. All three sources are new, so their
`inRepoDependents` are empty except for `grpc-core`, whose dependents are the two starters, and
`grpc-core` depends on neither. `web-core-spring-boot-starter`, `audit-core` and `test-support` have
no in-repo dependencies at all, per `PROJECT_INDEX.md`. An `optional` dependency is still a reactor
edge and is counted above.

### POM changes

- **`build/ludwig-bom/pom.xml`** - adds `grpc.version` and `protobuf.version`, imports `grpc-bom`
  and `protobuf-bom`. Versions only, as the tier requires.
- **`pom.xml` (root)** - three `<module>` entries; the code-generation plugin in `pluginManagement`
  with its version pinned and `protoc` bound to `${protobuf.version}`; JaCoCo and Sonar exclusions
  for generated packages.
- **`build/jacoco-aggregate/pom.xml`** - three dependencies.
- **`ludwig-service-parent`** - no change. A service generates nothing.
- Three new module POMs, each declaring `<relativePath>../../pom.xml</relativePath>` and importing
  `ludwig-bom`.

Exact versions are chosen at implementation from the current stable `grpc-java` line and recorded in
`state.json` `decisions`; the constraint is that `protobuf.version` is the one that `grpc-protobuf`
release was built against.

### Enforcement - every rule twice

| Rule | Owner | Check |
|---|---|---|
| No `ManagedChannelBuilder`, `NettyChannelBuilder`, `ServerBuilder` or `NettyServerBuilder` outside the two starters | ArchUnit | `RuleGroup.GRPC` |
| A type extending a generated service base carries `@LudwigGrpcService` | ArchUnit | `RuleGroup.GRPC` |
| No `@PreAuthorize`, `@Secured`, `@Transactional` on a stereotyped service; no repository dependency from it | ArchUnit | `RuleGroup.GRPC` |
| `spi` packages of both starters depend on nothing else in their module | ArchUnit | module-local test, as in `rest-client` |
| `grpc-core` reaches no Netty type | ArchUnit | `ModuleIndependenceTest` in `grpc-core` |
| The operation proto enum is the only restatement of the six states | ArchUnit | named exemption in `OperationVocabularyRules`, with a fixture proving a second one still fails |
| Status table is total over `ProblemStatus` | unit test | iterates the enum |
| Proto package, `java_package`, `java_multiple_files`, declared `idempotency_level`, no `.proto` outside a contract module or the named exceptions | gate script | `scripts/check_protos.sh` - a `.proto` is a resource, which ArchUnit and Checkstyle both cannot see; the same reasoning as `check_migrations.sh` |
| Generated code is not hand-edited | existing restriction | `generated-sources/` is already off-limits |

Not mechanisable, recorded in `build/architecture-rules/README.md` and at the point of the rule:

1. *A proto change is wire-compatible.* revapi sees the generated Java, where renumbering a field is
   invisible and renaming one is a false alarm. A wire-level breaking check needs a baseline
   descriptor set per release and is deferred to a named follow-up rather than approximated.
2. *An `idempotency_level` is truthful.* The script checks it is declared; only the method's author
   knows whether it is right.
3. *A client's target resolves to every pod.* A fact about a cluster.
4. *A client's keepalive is no shorter than its server's `permit-time`.* A fact about two
   deployments' configuration; the defaults are aligned and the README says to move them together.
5. *An internal call uses gRPC rather than REST.* See the proposal's non-goals.

## Risks / Trade-offs

- **A hand-written starter must be maintained until Spring gRPC is reachable** → the transport is
  confined to one class per side (D1); the published surface is the configuration, the annotations,
  the `spi` packages and the exceptions, none of which names a Netty type.
- **The duplicated token cache drifts from its twin** (D13) → named at the class, in the README and
  as owed work; both copies are small and have the same test cases.
- **`protoc` is downloaded as a Maven artifact** → a mirror that does not proxy `protoc`'s
  platform-classified executables fails the build of the modules that generate, with a resolution
  error naming the artifact. Task 3.1 proves resolution on a clean local repository before anything depends on it.
- **Shaded Netty adds roughly 9 MB per service image** → accepted; the alternative couples gRPC's
  transport to Boot's Netty line.
- **In-channel retry is harder to observe than an interceptor's** → counted through a stream tracer
  (D17) and covered by an integration test that asserts the counter against a server failing a fixed
  number of times.
- **`max-connection-age` cancels a call that outlives the grace period** → 30s grace by default; a
  service with long server-streaming calls raises it, and the README says so beside the key.
- **Deny-by-default breaks a service that adds the starter without a resource server** → that is the
  intent; the startup failure names the property that opts out (D7).
- **`ABORTED` for 409 loses the distinction between "exists" and "stale"** → the problem code in
  `ErrorInfo.reason` keeps it; the gRPC code is the coarse signal, as the HTTP status was.
- **Root POM and `jacoco-aggregate` are being edited by `add-sonarcloud-analysis`** → apply this
  change after that one lands, or on top of its working tree; never in parallel.

## Migration Plan

Additive. No existing module's behaviour changes, so there is nothing to migrate and rollback is
reverting the change. Order of work, each step leaving a green build:

1. Build support and enforcement (BOM, root POM plugin management, `check_protos.sh`,
   `RuleGroup.GRPC`).
2. `grpc-core`.
3. `grpc-server-spring-boot-starter`.
4. `grpc-client-spring-boot-starter`, whose integration tests call a server built from step 3 over
   the in-process transport.
5. Documentation, manifest regeneration, the full gate.

A service adopts the starters in its own later change: publish a `<service>-grpc-api` module, add
the server starter and expose port 9090, then move callers one named client at a time.

## Open Questions

- Whether a wire-level breaking check should compare against the previous release's descriptor set
  or run `buf breaking`. It changes nothing built here; it is the follow-up named above.
- Whether the token-cache extraction (D13) lands as its own library or inside a future
  `client-core`. Deferred to that follow-up.
