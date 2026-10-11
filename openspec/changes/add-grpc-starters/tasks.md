## 1. Build support - versions and code generation

Apply on top of `add-sonarcloud-analysis`, which edits the same root `pom.xml` and
`build/jacoco-aggregate`. Every task in this group changes the BOM or the root POM, so each one's
proof is the full build.

- [ ] 1.1 In `build/ludwig-bom/pom.xml` add `grpc.version` and `protobuf.version` and import
  `io.grpc:grpc-bom` and `com.google.protobuf:protobuf-bom`. Choose the current stable `grpc-java`
  release and the protobuf version that release's `grpc-protobuf` was built against; append both and
  the reason to `state.json` `decisions`.
  Proves it: `mvn -q -pl :ludwig-bom help:effective-pom | grep -c 'grpc-netty-shaded'` prints a
  non-zero count, then `mvn clean install`
- [ ] 1.2 In the root `pom.xml` add `io.github.ascopes:protobuf-maven-plugin` to `pluginManagement`
  with a pinned version, `protoc` bound to `${protobuf.version}`, the gRPC Java generator bound to
  `${grpc.version}`, and main and test generation goals. Do **not** add it to `<build><plugins>` -
  a module opts in. Do not touch `build/ludwig-service-parent/pom.xml`.
  Proves it: `mvn -q validate` (the enforcer rejects an unpinned plugin), then `mvn clean install`
- [ ] 1.3 In the root `pom.xml` exclude generated protobuf packages (`**/grpc/v*/**` and
  `ru/ludwigandreas/grpc/core/proto/**`) from JaCoCo instrumentation and add them to
  `sonar.exclusions`, with a comment at the exclusion saying what it covers and why.
  Proves it: `mvn clean install && python3 scripts/check_aggregate_report.py`

## 2. Enforcement first, so the rules exist before the code they govern

Written before the starters deliberately: a rule added after the implementation is shaped to pass
the code that already exists.

- [ ] 2.1 Add `scripts/check_protos.sh` and `scripts/check_protos.py` in the shape of
  `check_migrations`: `git ls-files` rather than `find`, stable `E_*` codes, `--list` and
  `--self-test`, a header comment saying why this is a gate script and not an ArchUnit or Checkstyle
  rule. Rules: versioned package `ludwig.<service>.v<N>`; matching `java_package`;
  `java_multiple_files = true`; every `rpc` declares `idempotency_level`; no `.proto` under
  `src/main` outside a `*-grpc-api` module or `sources/grpc-core`; `src/test/proto` exempt from
  placement and naming but not from the idempotency rule. Fixtures under
  `scripts/testdata/check_protos/` make every code both fire and not fire.
  Proves it: `scripts/check_protos.sh --self-test && scripts/check_protos.sh`
- [ ] 2.2 Add `scripts/check_protos.sh` to `COMMANDS` in `scripts/gate.sh` and extend the comment
  block above it with the one-paragraph reason, as the four existing checks have.
  Proves it: `scripts/gate.sh --list grpc-core | grep check_protos`
- [ ] 2.3 Add `GRPC("grpc", true)` to `RuleGroup` in `architecture-rules`, with javadoc stating the
  distinction the group turns on: a gRPC *service* is an adapter and a gRPC *channel* is
  configuration, and neither is something a module builds by hand.
  Proves it: `mvn -pl :architecture-rules -am verify`
- [ ] 2.4 Implement `GrpcRules` and register it in `BuiltInRuleSets`: no call to
  `ManagedChannelBuilder`, `NettyChannelBuilder`, `ServerBuilder` or `NettyServerBuilder` (shaded and
  unshaded names) outside `ru.ludwigandreas.grpc.server..` and `ru.ludwigandreas.grpc.client..`; a
  type assignable to `io.grpc.BindableService` is annotated `@LudwigGrpcService`; a stereotyped
  service carries no `@PreAuthorize`, `@PostAuthorize`, `@Secured`, `@RolesAllowed` or
  `@Transactional` and depends on no repository type. Match annotations by name so
  `architecture-rules` gains no dependency on the starters. Add `GrpcRulesTest` asserting each rule
  fires on a violating fixture and passes on a module with no gRPC type.
  Proves it: `mvn -pl :architecture-rules -am verify -Dtest=GrpcRulesTest`
- [ ] 2.5 In `OperationVocabularyRules`, recognise an enum whose constants restate the six states
  behind a common protobuf prefix (`<ENUM>_PENDING`, ...) and ignore a zero-value `_UNSPECIFIED`
  constant, then add a by-name exemption for `grpc-core`'s generated operation status enum in the
  style of `CachingRules.EXEMPT_TYPES`, with javadoc giving the reason. Extend the rule's test with
  two fixtures: the exempted name passes; a second prefixed restatement beside it fails and is named.
  Proves it: `mvn -pl :architecture-rules -am verify`
- [ ] 2.6 Record the five unmechanisable gRPC conventions (design, "Enforcement") in
  `build/architecture-rules/README.md` and `README.ru.md` under the new group, each with its reason.
  Proves it: `openspec validate add-grpc-starters`

## 3. `grpc-core`

- [ ] 3.1 Create `sources/grpc-core` (artifact `grpc-core`, parent `common` with
  `<relativePath>../../pom.xml</relativePath>`, imports `ludwig-bom`, package root
  `ru.ludwigandreas.grpc.core`), opt into the protobuf plugin, depend on `grpc-api`, `grpc-protobuf`,
  `grpc-stub` and `web-core-spring-boot-starter`. Add the `<module>` to the root POM and the
  dependency to `build/jacoco-aggregate/pom.xml`.
  The first build must also prove `protoc` resolves from the configured repositories on a clean
  local repository; if it does not, stop and record it in `state.json` `failures` - do not switch
  to a system `protoc`.
  Proves it: `scripts/manifest.sh build && scripts/manifest.sh layout && mvn -pl :grpc-core -am -Dmaven.repo.local=$(mktemp -d) verify`
- [ ] 3.2 Add `GrpcMetadataKeys`: correlation id, idempotency key, replayed marker, locale, zone and
  retry-after, each the lower-cased form of the header constant its owning module declares, with a
  comment at each key naming that constant. Where the owning module is not a dependency of
  `grpc-core`, the literal is repeated and a unit test in the starter that does depend on both
  asserts they are equal.
  Proves it: `mvn -pl :grpc-core -am verify -Dtest=GrpcMetadataKeysTest`
- [ ] 3.3 Add the status codec: problem to `google.rpc.Status` (code table from design D9,
  `ErrorInfo`, `BadRequest`, `LocalizedMessage`, `RetryInfo`) and back to a `ProblemDetail`. Unit
  tests: the table is total over every `ProblemStatus` constant (iterate the enum); a round trip
  preserves code, status, type, correlation id and violations; an unmapped failure yields `INTERNAL`
  with no message text.
  Proves it: `mvn -pl :grpc-core -am verify -Dtest='GrpcStatusCodec*Test'`
- [ ] 3.4 Add `src/main/proto/ludwig/operation/v1/operation.proto` and the mapper to and from
  `OperationResponse`. Unit tests: every `OperationStatus` constant maps to a distinct wire value
  (iterate the enum); status, detail, progress, result reference, failure and retry-after survive a
  round trip.
  Proves it: `mvn -pl :grpc-core -am verify -Dtest='OperationMessageMapper*Test' && scripts/check_protos.sh`
- [ ] 3.5 Add the call-context keys (principal, correlation id, locale, zone) and the listener
  wrapper that restores and clears the thread-locals around each callback. It takes the three
  restorers as functional parameters so `grpc-core` needs no dependency on `security` or
  `observability`. Unit test with a two-thread executor: a value set for call A is visible in A's
  callbacks and absent on the same thread during call B.
  Proves it: `mvn -pl :grpc-core -am verify -Dtest=ContextBridgeTest`
- [ ] 3.6 Add `ModuleIndependenceTest` under `architecture`: no class in `grpc-core` depends on
  `io.grpc.netty..`, `io.netty..`, a servlet type or any `ru.ludwigandreas` module other than
  `webcore`.
  Proves it: `mvn -pl :grpc-core -am verify -Dtest=ModuleIndependenceTest`
- [ ] 3.7 Write `sources/grpc-core/README.md` and `README.ru.md`: what the module is, the code
  table, the metadata keys, the contract-module convention and the proto conventions.
  Proves it: `openspec validate add-grpc-starters`

## 4. `grpc-server-spring-boot-starter`

- [ ] 4.1 Create `sources/grpc-server-spring-boot-starter` (package root
  `ru.ludwigandreas.grpc.server`, parent `common` with the `relativePath`, imports `ludwig-bom`),
  with `grpc-netty-shaded`, `grpc-services`, `grpc-core`; `security-`, `observability-` and
  `idempotency-spring-boot-starter` optional; `grpc-inprocess`, `test-support`,
  `test-support-security` and `architecture-rules` at test scope. Register the module in the root
  POM and `build/jacoco-aggregate/pom.xml`; add a test-only echo service in `src/test/proto` with
  unary and server-streaming methods of each idempotency level.
  Proves it: `scripts/manifest.sh build && mvn -pl :grpc-server-spring-boot-starter -am verify && scripts/check_protos.sh`
- [ ] 4.2 Add `@LudwigGrpcService`, `GrpcServerProperties` under `ludwig.grpc.server` with the
  defaults of design D4, and the `SmartLifecycle` server: no stereotyped bean means no bind;
  `enabled=false` means no bind; shutdown drains for `shutdown-grace` then cancels. Register the
  auto-configuration in `AutoConfiguration.imports`.
  Proves it: `mvn -pl :grpc-server-spring-boot-starter -am verify -Dit.test=GrpcServerLifecycleIT`
- [ ] 4.3 Add the fixed interceptor chain in the order of design D5, the `LudwigGrpcServerInterceptor`
  SPI for a service's own interceptors (always after the built-in chain), and the exception-to-status
  interceptor over `ProblemMapperRegistry` and the `grpc-core` codec. IT: a mapped exception arrives
  as its mapped code with the problem code in `ErrorInfo`; a wrapped one resolves through the cause
  chain; an unmapped one is `INTERNAL` without its message.
  Proves it: `mvn -pl :grpc-server-spring-boot-starter -am verify -Dit.test=GrpcServerProblemIT`
- [ ] 4.4 Add authentication: bearer token from `authorization` metadata through the application's
  `JwtDecoder` and JWT converter; deny by default; health, enabled reflection and
  `security.permit-unauthenticated` exempt, the last logged at WARN per entry; forgeable identity
  metadata stripped; startup failure when a service exists, no decoder does and
  `security.enabled=false` is not set. IT covers each branch, using `test-support-security`.
  Proves it: `mvn -pl :grpc-server-spring-boot-starter -am verify -Dit.test=GrpcServerSecurityIT`
- [ ] 4.5 Wire the context bridge: `SecurityContextHolder`, `CorrelationContext.open` and
  `UserPreferences.bind()` restored on every callback; contribute a `UserPreferenceSource` reading
  locale and zone from the call context. IT on a multi-thread executor asserting, from inside the
  handler, the caller's principal, correlation id and preferences, and their absence when the same
  thread serves a different caller.
  Proves it: `mvn -pl :grpc-server-spring-boot-starter -am verify -Dit.test=GrpcServerContextIT`
- [ ] 4.6 Add limits and transport security: message, metadata, per-connection, connection-age and
  executor bounds; TLS with `client-auth`; the two-switch plaintext rule and the production-profile
  refusal; reflection off by default and refused in production. IT: an oversized message and a
  saturated executor both answer `RESOURCE_EXHAUSTED`; each refused configuration fails startup with
  the property named.
  Proves it: `mvn -pl :grpc-server-spring-boot-starter -am verify -Dit.test='GrpcServerLimitsIT,GrpcServerTransportSecurityIT'`
- [ ] 4.7 Register `grpc.health.v1.Health` bound to Spring's readiness state, and turn it
  `NOT_SERVING` as the first step of shutdown.
  Proves it: `mvn -pl :grpc-server-spring-boot-starter -am verify -Dit.test=GrpcServerHealthIT`
- [ ] 4.8 Add opt-in idempotent replay for unary methods carrying `@Idempotent`, conditional on
  `IdempotencyStore`: claim with the mode `IdempotencyFilter` uses, scope defaulting to the full
  method name, fingerprint from the request bytes, `StoredResponse` as in design D16; startup failure
  when a method opts in and no store bean exists. IT against Postgres through `@LudwigPostgresTest`:
  replay, key reuse with a different request, a concurrent duplicate, a missing required key.
  Proves it: `mvn -pl :grpc-server-spring-boot-starter -am verify -Dit.test=GrpcServerIdempotencyIT`
- [ ] 4.9 Add observability: Micrometer's server observation interceptor retagged to
  `ludwig.grpcserver.requests`, the `ludwig.grpcserver.calls.nodeadline` counter, and the per-service
  access log with no payload.
  Proves it: `mvn -pl :grpc-server-spring-boot-starter -am verify -Dit.test=GrpcServerObservabilityIT`
- [ ] 4.10 Add `@LudwigGrpcServerTest` (in-process transport, this starter's auto-configurations and
  nothing else) with its own `.imports` file, and the module's `architecture` test enabling
  `RuleGroup.GRPC` plus the `spi`-depends-on-nothing rule.
  Proves it: `mvn -pl :grpc-server-spring-boot-starter -am verify`
- [ ] 4.11 Add `i18n/ludwig-grpc-server-messages.properties` and `_ru.properties` for every startup
  and refusal message, and write `README.md` and `README.ru.md`: quick start, the chain and why it
  is fixed, the proxy-annotation trap, every key with its default and whether it needs a restart,
  the deployment prerequisites (second port, headless `Service`), what it deliberately does not do.
  Proves it: `mvn -q validate` (bundle key-set parity and `NonAsciiSourceText`) and
  `openspec validate add-grpc-starters`

## 5. `grpc-client-spring-boot-starter`

- [ ] 5.1 Create `sources/grpc-client-spring-boot-starter` (package root
  `ru.ludwigandreas.grpc.client`, parent `common` with the `relativePath`, imports `ludwig-bom`),
  with `grpc-netty-shaded`, `grpc-core`, `audit-core`, the individual Resilience4j modules;
  `observability-` and `security-spring-boot-starter` optional; `grpc-inprocess`, `test-support` and
  `architecture-rules` at test scope; the same test echo proto as 4.1. Register it in the root POM
  and `build/jacoco-aggregate/pom.xml`.
  Proves it: `scripts/manifest.sh build && mvn -pl :grpc-client-spring-boot-starter -am verify && scripts/check_protos.sh`
- [ ] 5.2 Add `GrpcClientProperties` under `ludwig.grpc.client` and the deep merger with the rules of
  design D11 (`target` and `auth.relay-enabled` never inherited; a declared `bulkhead` or
  `rate-limiter` block enables it). Unit tests one per merge rule.
  Proves it: `mvn -pl :grpc-client-spring-boot-starter -am verify -Dtest='GrpcClientPropertiesMerger*Test'`
- [ ] 5.3 Add channel construction - one channel per client, `round_robin` default, keepalive, idle
  timeout, message limit, TLS, graceful close on shutdown - and the three consumption models:
  `@LudwigGrpcClient` stub injection, the qualified `Channel` bean, `GrpcChannelRegistry`. IT: the
  same method called three ways carries the same credential and deadline and is counted once each;
  an unknown name fails listing the configured ones; no configured client creates no channel.
  Proves it: `mvn -pl :grpc-client-spring-boot-starter -am verify -Dit.test=GrpcClientModelsIT`
- [ ] 5.4 Add the deadline interceptor: `min(configured, inbound remaining)`, built-in default 5s,
  startup failure on a non-positive value, and the timeout exception.
  Proves it: `mvn -pl :grpc-client-spring-boot-starter -am verify -Dit.test=GrpcClientDeadlineIT`
- [ ] 5.5 Add the resilience interceptor - rate limiter, bulkhead, circuit breaker in that order,
  one permit and one result per logical call, in the shared Micrometer-bound registries - with the
  failure classification of design D12. IT: a call that succeeds on its third attempt records one
  success; an open breaker sends nothing; caller-side codes never open the breaker; one client's
  saturation leaves another client untouched.
  Proves it: `mvn -pl :grpc-client-spring-boot-starter -am verify -Dit.test=GrpcClientResilienceIT`
- [ ] 5.6 Generate the per-client service config: retry policy with jittered backoff and retry
  throttling for methods whose proto `idempotency_level` is `IDEMPOTENT` or `NO_SIDE_EFFECTS`, none
  for the rest; count attempts through a stream tracer into `ludwig.grpcclient.retries`. IT against
  a server that fails a fixed number of times: an idempotent method is retried and counted, an
  unknown-idempotency method is not, server pushback delays the next attempt.
  Proves it: `mvn -pl :grpc-client-spring-boot-starter -am verify -Dit.test=GrpcClientRetryIT`
- [ ] 5.7 Add authentication as `CallCredentials` behind the provider SPI: `none`, `bearer`,
  `oauth2-client-credentials`, `oauth2-token-relay`, `custom`; the single-flight, skew-refreshed
  token cache with a comment naming `rest-client`'s `CachingTokenSource` as its twin and the owed
  extraction; one re-attempt on `UNAUTHENTICATED` for unary calls; the three relay guards. IT: 200
  concurrent calls at expiry mint one token; a rejected token is re-minted once; relay with no
  inbound token sends nothing.
  Proves it: `mvn -pl :grpc-client-spring-boot-starter -am verify -Dit.test=GrpcClientAuthIT`
- [ ] 5.8 Add the exception hierarchy and the reverse codec, and contribute an
  `ExceptionProblemMapper` to `web-core` (no advice of its own): peer and transport failures to
  `upstream-unavailable` 503, `RESOURCE_EXHAUSTED` to 429, caller-side codes to 500.
  Proves it: `mvn -pl :grpc-client-spring-boot-starter -am verify -Dit.test=GrpcClientErrorIT`
- [ ] 5.9 Add observability and audit: the retagged request timer, not-permitted and token-refresh
  counters, the per-client logger with payload logging off by default and refused in production,
  correlation metadata from `CorrelationContext`, and `GrpcCallAudit` with `toAuditEvent()` into the
  one `AuditSink` under `category=outbound-call` - sampling successes only, `DENIED` for a refused
  call, and no `try`/`catch` around `record`.
  Proves it: `mvn -pl :grpc-client-spring-boot-starter -am verify -Dit.test='GrpcClientObservabilityIT,GrpcClientAuditIT'`
- [ ] 5.10 Add the startup validator reporting every problem together (design D11-D15: missing
  target, target in defaults, non-positive deadline, credential over plaintext, plaintext without
  both switches or in production, unknown auth type, relay without its switch or without a resource
  server, a breaker that cannot open, payload logging in production) and the WARN for `pick_first`.
  Unit test: three independent mistakes produce one failure listing three.
  Proves it: `mvn -pl :grpc-client-spring-boot-starter -am verify -Dtest=GrpcClientConfigurationValidatorTest`
- [ ] 5.11 Make `deadline`, logging level, rate-limiter limits and audit sampling refreshable by
  re-reading the bound properties, leaving a call in flight on the policy it started with.
  Proves it: `mvn -pl :grpc-client-spring-boot-starter -am verify -Dit.test=GrpcClientRefreshIT`
- [ ] 5.12 Add `@LudwigGrpcClientTest` with its own `.imports` file, and the module's `architecture`
  test enabling `RuleGroup.GRPC` plus the `spi`-depends-on-nothing rule.
  Proves it: `mvn -pl :grpc-client-spring-boot-starter -am verify`
- [ ] 5.13 Add `i18n/ludwig-grpc-client-messages.properties` and `_ru.properties`, and write
  `README.md` and `README.ru.md`: quick start, precedence, the order and why retry sits inside the
  breaker, why retry is read from the contract, the deadline rule, authentication and the relay
  guards, failure modes with their exception, log and metric, every key with its default and whether
  it needs a restart, the token-cache duplicate as owed work, what it deliberately does not do.
  Proves it: `mvn -q validate` and `openspec validate add-grpc-starters`

## 6. Repository documentation

- [ ] 6.1 Add the three modules to the root `README.md` module table and remove the commented-out
  `grpc-client` placeholder row.
  Proves it: `openspec validate add-grpc-starters`
- [ ] 6.2 Add a "gRPC between platform services goes through the two starters" bullet to
  `CLAUDE.md` module conventions and the condensed equivalent to `openspec/config.yaml` `context`,
  naming the contract-module convention, the thin-adapter rule and its reason, `RuleGroup.GRPC` and
  `check_protos.sh`.
  Proves it: `openspec validate add-grpc-starters`

## 7. Verification gate, in full

- [ ] 7.1 Regenerate the manifest and confirm it is current and the layout holds.
  Proves it: `scripts/manifest.sh build && scripts/manifest.sh stale && scripts/manifest.sh layout`
- [ ] 7.2 Run the file-only checks.
  Proves it: `scripts/check_protos.sh && scripts/check_migrations.sh && scripts/check_image_pins.sh && scripts/check_api_baseline.sh && scripts/check_build_metadata.sh`
- [ ] 7.3 Run the full build - there is no narrower gate, because `ludwig-bom` and the root POM
  changed - and confirm the aggregate report contains the three new modules.
  Proves it: `mvn -q validate && mvn clean install && python3 scripts/check_aggregate_report.py`
- [ ] 7.4 Validate the change.
  Proves it: `openspec validate add-grpc-starters`
