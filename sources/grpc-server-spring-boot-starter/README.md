# grpc-server-spring-boot-starter

***English** · [Русский](README.ru.md)*

A gRPC server as configuration. Extend a generated `ImplBase`, annotate it `@LudwigGrpcService`, and it is
served on its own port behind a fixed interceptor chain that carries the platform's principal, correlation id,
caller preferences and RFC 9457 problem mapping across the transport, with limits that hold without
configuration, TLS unless plaintext is explicitly permitted twice, health bound to readiness, graceful shutdown,
optional idempotent replay, and an in-process test slice.

| | |
|---|---|
| Role | starter |
| Depends on (in-repo) | [`grpc-core`](../grpc-core), [`idempotency`](../idempotency-spring-boot-starter), [`observability`](../observability-spring-boot-starter), [`security`](../security-spring-boot-starter), [`web-core`](../web-core-spring-boot-starter) |
| Used by | `crud-service-example`; `grpc-client` (tests) |
| Surfaces | gRPC (own port, default 9090) |

Built on `grpc-java` directly; properties live under `ludwig.grpc.server.*` (never `spring.grpc.*`, so a later
Spring Boot upgrade cannot bind the same keys with another meaning). Contracts live in `<service>-grpc-api`
modules; see [grpc-core](../grpc-core).

## Usage

```java
@LudwigGrpcService
class CatalogGrpcService extends CatalogServiceGrpc.CatalogServiceImplBase {

    private final ProductService products;          // the application service does the work

    @Override
    public void getProduct(GetProductRequest request, StreamObserver<Product> reply) {
        reply.onNext(mapper.toMessage(products.get(request.getId())));
        reply.onCompleted();
    }
}
```

```yaml
ludwig:
  grpc:
    server:
      port: 9090
      tls:
        bundle: grpc            # a spring.ssl.bundle.* entry
```

With a resource server configured (`spring.security.oauth2.resourceserver.jwt.*`) nothing else is needed. Throw
domain exceptions as in a controller: the `ExceptionProblemMapper`s modules contribute decide the gRPC status.

### The interceptor chain (fixed)

```
observation → correlation → access log → exception→status → admission → authentication
            → context bridge (principal, correlation MDC, preferences) → [idempotent replay]
            → your LudwigGrpcServerInterceptor beans → handler
```

- Observation outermost: a refused call is still measured.
- Exception translation outside authentication: a rejected credential is rendered like any problem.
- Admission before authentication: a saturated server does not validate tokens for calls it will refuse.
- Context bridge inside authentication: the principal is one of the things it binds.
- Custom interceptors are `LudwigGrpcServerInterceptor` beans and always run after the built-in stages; a plain
  `ServerInterceptor` bean is not picked up.

Thread-locals (`SecurityContextHolder`, correlation MDC, `UserPreferences`) are restored **around every
callback**, because a unary handler runs in `onHalfClose`, usually on a different thread.

### The proxy-annotation trap

gRPC dispatches to the object `bindService()` captured, not through the Spring proxy.
`@PreAuthorize`, `@Secured`, `@RolesAllowed`, `@Transactional`, `@Cacheable` and `@Async` on a gRPC service
**do nothing**. Put authorization and transactions on the application service it calls. `architecture-rules`
`RuleGroup.GRPC` fails the build on those annotations and on a repository dependency in a `@LudwigGrpcService`
class. `@Idempotent` is the exception (read reflectively at bind time).

### Authentication

Deny by default. The bearer token in `authorization` metadata is decoded by the application's `JwtDecoder` and
converted by its `Converter<Jwt, AbstractAuthenticationToken>` (`JwtPrincipalConverter`), so the same token gives
the same principal over gRPC and HTTP. Exempt: `grpc.health.v1.Health`, reflection when enabled, and methods in
`security.permit-unauthenticated` (each logged at `WARN` at startup). Identity headers the HTTP chain strips
(`x-ludwig-*`, `x-forwarded-*`, ...) are stripped here too.

A service with a `@LudwigGrpcService` bean and no `JwtDecoder` **does not start**, naming
`ludwig.grpc.server.security.enabled`; setting it `false` is logged at `WARN`.

### Failures

Resolved by web-core's `ProblemMapperRegistry` and rendered as `google.rpc.Status` (code table, `ErrorInfo`,
`LocalizedMessage`, `BadRequest`, `RetryInfo` - see [grpc-core](../grpc-core#problems-as-grpc-status)). An
unmapped exception is `INTERNAL` with generic text and the correlation id. `onError(e)` is translated like a throw.
Failures before the context bridge (authentication, admission) use the default locale.

### Idempotent replay

With `idempotency-spring-boot-starter`, a **unary** method annotated `@Idempotent` runs at most once per
`idempotency-key` and scope (default: the method's full name), using the same `IdempotencyStore` and `STANDALONE`
claim as the HTTP filter. A repeat gets the stored reply with `idempotency-replayed: true`; a reused key with a
different request, or a duplicate while the first runs, gets the idempotency module's problems; failed or
cancelled calls are not stored. `@Idempotent(required = true)` without a key → `INVALID_ARGUMENT`. `@Idempotent`
without an `IdempotencyStore`, or on a streaming method, fails startup.

### Health and shutdown

`grpc.health.v1.Health` is always registered, follows Spring's `ReadinessState` (server-wide and per service) and
is anonymous. On shutdown it turns `NOT_SERVING`, the server stops accepting, in-flight calls get `shutdown-grace`,
then are cancelled.

### Testing slice

```java
@LudwigGrpcServerTest
@Import({CatalogGrpcService.class, TestTokens.class})
class CatalogGrpcServiceTest {
    @Autowired ManagedChannel channel;   // in-process, same chain as production
}
```

Boots this starter plus web-core's problem and preference configuration on the in-process transport. Authentication
is on: declare a `JwtDecoder`, or set `ludwig.grpc.server.security.enabled=false` in `properties`.

## Configuration

All keys require a restart.

| Property | Default | Meaning |
|---|---|---|
| `ludwig.grpc.server.enabled` | `true` | `false` binds nothing |
| `ludwig.grpc.server.port` | `9090` | `0` = free port |
| `ludwig.grpc.server.address` | all interfaces | |
| `ludwig.grpc.server.in-process-name` | - | Serve in-process (tests) |
| `ludwig.grpc.server.max-inbound-message-size` | `4MB` | Larger → `RESOURCE_EXHAUSTED` |
| `ludwig.grpc.server.max-inbound-metadata-size` | `8KB` | Fits a JWT, not a header bomb |
| `ludwig.grpc.server.max-concurrent-calls-per-connection` | `100` | One caller cannot monopolise a pod |
| `ludwig.grpc.server.max-connection-age` | `30m` | Forces clients to re-resolve (rebalancing after scale-out) |
| `ludwig.grpc.server.max-connection-age-grace` | `30s` | Raise for long server-streaming calls |
| `ludwig.grpc.server.handshake-timeout` | `20s` | |
| `ludwig.grpc.server.keepalive.permit-time` | `30s` | ≤ clients' keepalive time; aligned with the client starter's default - change together |
| `ludwig.grpc.server.keepalive.permit-without-calls` | `true` | |
| `ludwig.grpc.server.executor.threads` | `64` | Handler threads |
| `ludwig.grpc.server.executor.queue-capacity` | `256` | Beyond threads + queue → `RESOURCE_EXHAUSTED` |
| `ludwig.grpc.server.shutdown-grace` | `30s` | In-flight calls on shutdown |
| `ludwig.grpc.server.tls.bundle` | - | Spring Boot SSL bundle with key and certificate |
| `ludwig.grpc.server.tls.client-auth` | `none` | `optional` / `require` (mTLS authenticates the transport; the principal still comes from the token) |
| `ludwig.grpc.server.plaintext` | `false` | First plaintext switch |
| `ludwig.grpc.allow-plaintext` | `false` | Second switch, shared with the client starter |
| `ludwig.grpc.production-profiles` | `prod, production` | Plaintext and reflection refused under these |
| `ludwig.grpc.server.security.enabled` | `true` | `false` serves unauthenticated (logged) |
| `ludwig.grpc.server.security.permit-unauthenticated` | `[]` | `pkg.Service/Method` or `pkg.Service/*` |
| `ludwig.grpc.server.reflection.enabled` | `false` | Refused under a production profile |

**Plaintext** is accepted on loopback, otherwise only with both `plaintext: true` and
`ludwig.grpc.allow-plaintext: true`, and never under a production profile (loopback included). There is no "the
mesh encrypts" mode. All refused settings are reported at once, each naming its property.

## Deployment

- **A second port**: expose `9090` (or `port`) in the container and `Service`; network policies can open gRPC
  without the REST surface.
- **A headless `Service`** for callers, so the client starter's `round_robin` balances per call (HTTP/2 puts every
  call on one connection behind a normal `Service`).
- **gRPC readiness probe** (`grpc: { port: 9090 }`) optionally; it agrees with HTTP readiness.
- **TLS**: an SSL bundle (`spring.ssl.bundle.*`) from mounted certificates; rotate by redeploying.

## Observability

| Signal | Meaning |
|---|---|
| `ludwig.grpcserver.requests{service,method,status}` | Micrometer gRPC observation (timer) and server span |
| `ludwig.grpcserver.calls.nodeadline{service,method}` | Calls without a deadline; such callers can hold a handler thread indefinitely |
| `INFO` on logger `ludwig.grpcserver.<service>` | One line per call: method, status, duration ms, correlation id. **No payloads** |
| `INFO gRPC server started on <address> serving <services>` | Startup |
| `ERROR gRPC method <m> failed` | Unmapped exception (rendered `INTERNAL`) |
| `WARN gRPC calls still in flight after <grace>; cancelling them` | Shutdown cut calls short |

Correlation is resolved from `x-correlation-id` with observability's rules and bound to the MDC in the handler.

**Recommended alerts:**

| Condition | Why |
|---|---|
| Rate of `status` ∈ {`INTERNAL`, `UNKNOWN`, `UNAVAILABLE`} > 1% | Server errors |
| `status="RESOURCE_EXHAUSTED"` rising | Executor saturated or oversized messages |
| `ludwig_grpcserver_calls_nodeadline_total` rising | A caller without deadlines (fix the client) |

**Known gaps:** no gauge for executor queue depth or active calls.

## Audit

No events of its own. Authorization denials from `@PreAuthorize` on the application service are audited by the
security starter (`access.denied`).

**Known gap:** rejected authentication (missing/invalid/expired token) on a gRPC call is not recorded in the audit
trail - only rendered as `UNAUTHENTICATED`. The same holds for the HTTP chain.

## Runbook

| Symptom | Likely cause | What to do |
|---|---|---|
| Startup fails naming `ludwig.grpc.server.security.enabled` | No `JwtDecoder` | Configure the resource server, or explicitly disable security (not in production) |
| Startup fails on plaintext / reflection | Production profile or missing second switch | Configure `tls.bundle`; reflection only outside production |
| Clients get `UNAUTHENTICATED` | Missing/expired token, wrong audience, identity headers stripped | Check the client's token and the resource server's audience |
| `@PreAuthorize` on the gRPC service has no effect | Proxy-annotation trap | Move it to the application service |
| `RESOURCE_EXHAUSTED` under load | `executor.threads` + `queue-capacity` exceeded | Scale out or raise threads (watch DB pool size) |
| All traffic on one pod after scale-out | Long-lived connections behind a normal `Service` | Use a headless `Service`; `max-connection-age` forces re-resolution |
| Streaming calls cut on deploy | `max-connection-age-grace` / `shutdown-grace` too short | Raise them (and `terminationGracePeriodSeconds`) |

## Testing

`@LudwigGrpcServerTest` (in-process, same chain). The module's own tests cover chain order, per-callback context
restoration across threads, problem rendering, authentication refusals, plaintext/reflection refusals, limits,
health and shutdown, and idempotent replay; `architecture-rules` `GRPC` group checks services.

## Design decisions

- **Netty on its own port**, not gRPC-over-servlet: independent of Boot's Netty and Tomcat HTTP/2 tuning.
- **Fixed chain order**; no way to place an interceptor before a built-in stage.
- **No payload logging.**
- **Replay is unary only**; no special behaviour for client-streaming or bidirectional calls beyond the chain.
- **No wire-compatibility check of `.proto` changes** (see architecture-rules).
