# grpc-server-spring-boot-starter

A gRPC server as configuration. Add the starter, extend a generated `ImplBase`, put
`@LudwigGrpcService` on it, and the service is served on its own port behind a fixed interceptor
chain that carries the platform's principal, correlation id, caller preferences and RFC 9457
problem mapping across the transport. No `@Configuration` class of your own.

Built on `grpc-java` directly (Spring gRPC needs Boot 3.5+). Properties live under
`ludwig.grpc.server.*`, never `spring.grpc.*`, so a later Boot upgrade cannot bind the same keys with
another meaning. The contract (`.proto`) lives in a `<service>-grpc-api` module; see
[grpc-core](../grpc-core/README.md) for the proto conventions, the metadata keys and the status code
table.

## Quick start

```java
@LudwigGrpcService
class CatalogGrpcService extends CatalogServiceGrpc.CatalogServiceImplBase {

    private final ProductService products;          // the application service does the work

    @Override
    public void getProduct(GetProductRequest request, StreamObserver<ProductReply> reply) {
        reply.onNext(mapper.toReply(products.get(request.getId())));
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

With a resource server configured (`spring.security.oauth2.resourceserver.jwt.*`) that is all.
Throw your domain exceptions as you do in a controller: the `ExceptionProblemMapper`s your modules
already contribute decide the gRPC status too.

## The chain, and why it is fixed

```
observation → correlation → access log → exception→status → admission → authentication
            → context bridge (principal, correlation MDC, preferences) → [idempotent replay]
            → your LudwigGrpcServerInterceptor beans → handler
```

The order is the declaration order of `BuiltInServerInterceptor.Stage` and nothing else. Every other
order has a concrete defect: observation outermost, so a refused call is still measured; the
exception translator outside authentication, so a rejected credential is rendered like any other
problem; admission before authentication, so a saturated server does not validate a token for a call
it is about to refuse; the context bridge inside authentication, because the principal is one of the
things it binds. Your own interceptors are `LudwigGrpcServerInterceptor` beans and always run after
the built-in stages. A plain `ServerInterceptor` bean is deliberately not picked up.

**Thread-locals are restored on every callback**, not once per call. gRPC delivers a call's events on
whichever executor thread is free, and the handler of a unary call runs in `onHalfClose`, usually on
a different thread from the interceptors. `SecurityContextHolder`, the correlation MDC entry and the
bound `UserPreferences` are therefore set around each callback and put back afterwards - so a handler
sees its own caller and a pooled thread never carries the previous caller's identity.

## The proxy-annotation trap

gRPC dispatches to the object `bindService()` captured, not through the Spring proxy around your
bean. `@PreAuthorize`, `@Secured`, `@RolesAllowed`, `@Transactional`, `@Cacheable` and `@Async` on a
gRPC service compile, start, and **do nothing**. A gRPC service is an adapter: authorization and
transactions belong on the application service it calls. `architecture-rules`' `RuleGroup.GRPC`
fails the build on any of those annotations, and on a repository dependency, in a class carrying
`@LudwigGrpcService`.

`@Idempotent` is the exception: the server reads it reflectively when it binds the service.

## Authentication

Deny by default. A bearer token in `authorization` metadata is decoded by the application's own
`JwtDecoder` and converted by its `Converter<Jwt, AbstractAuthenticationToken>` (the platform's
`JwtPrincipalConverter`), so a gRPC caller and an HTTP caller holding the same token are the same
principal with the same authorities. Exempt: `grpc.health.v1.Health`, reflection when enabled, and
the methods in `security.permit-unauthenticated` (each logged at WARN at startup). The identity
headers the HTTP chain strips (`x-ludwig-*`, `x-forwarded-*`, ...) are stripped here too.

**A service with no way to authenticate does not start.** With a `@LudwigGrpcService` bean and no
`JwtDecoder`, startup fails naming `ludwig.grpc.server.security.enabled`. Setting it to `false` is a
decision you write down; it is logged at WARN.

## Failures

An exception is resolved by `web-core`'s `ProblemMapperRegistry`, exactly as for HTTP, and rendered
as `google.rpc.Status`: the code from the table in `grpc-core`, `ErrorInfo` carrying the problem code,
`LocalizedMessage` in the caller's locale, `BadRequest` for violations, `RetryInfo` where there is a
retry-after. An exception no mapper claims is `INTERNAL` with the generic text and the correlation
id, never its own message. A handler that calls `onError(e)` is translated the same way as one that
throws. A failure raised before the context bridge (authentication, admission) is rendered in the
platform's default locale, because the caller's has not been resolved yet.

## Idempotent replay

With `idempotency-spring-boot-starter` present, a **unary** method carrying `@Idempotent` runs at most
once per `idempotency-key` and scope (default scope: the method's full name). The claim, the store
and the problems are the idempotency module's own - same `IdempotencyStore`, same `STANDALONE` claim
mode as `IdempotencyFilter`; no table or SQL is added here. A repeat with the same request gets the
stored reply with `idempotency-replayed: true`; a reused key with a different request, and a
duplicate while the first is running, get the idempotency module's problems; a call that fails or is
cancelled is not stored. `@Idempotent(required = true)` without a key is `INVALID_ARGUMENT`. An
`@Idempotent` method with no `IdempotencyStore` bean, or on a streaming method, fails startup naming
the method.

## Observability

- `ludwig.grpcserver.requests{service,method,status}` - Micrometer's gRPC observation, renamed; also
  the server span.
- `ludwig.grpcserver.calls.nodeadline{service,method}` - calls that arrived with no deadline. Such a
  caller can hold a handler thread for as long as the handler takes.
- One INFO line per call on logger `ludwig.grpcserver.<service>`: method, status, duration,
  correlation id. **No payload, ever.**
- The correlation id is resolved with observability's own resolver and acceptance rules from
  `x-correlation-id` (or the configured header) and bound to the MDC in the handler. Without
  `observability-spring-boot-starter` calls carry none.

## Configuration

Every key needs a restart: the server, its transport and its chain are built once, at startup.

| Key | Default | |
|---|---|---|
| `ludwig.grpc.server.enabled` | `true` | `false` binds nothing whatever services exist |
| `ludwig.grpc.server.port` | `9090` | `0` picks a free port |
| `ludwig.grpc.server.address` | all interfaces | |
| `ludwig.grpc.server.in-process-name` | - | serve in-process instead of on a port (tests) |
| `ludwig.grpc.server.max-inbound-message-size` | `4MB` | larger is `RESOURCE_EXHAUSTED` |
| `ludwig.grpc.server.max-inbound-metadata-size` | `8KB` | a JWT fits; a header bomb does not |
| `ludwig.grpc.server.max-concurrent-calls-per-connection` | `100` | one caller cannot monopolise a pod |
| `ludwig.grpc.server.max-connection-age` | `30m` | makes callers re-resolve, which is what rebalances after a scale-out |
| `ludwig.grpc.server.max-connection-age-grace` | `30s` | raise it for long server-streaming calls, or they are cancelled |
| `ludwig.grpc.server.handshake-timeout` | `20s` | |
| `ludwig.grpc.server.keepalive.permit-time` | `30s` | must not exceed the clients' keepalive time; the client starter's default is aligned - move them together |
| `ludwig.grpc.server.keepalive.permit-without-calls` | `true` | |
| `ludwig.grpc.server.executor.threads` | `64` | handler threads |
| `ludwig.grpc.server.executor.queue-capacity` | `256` | beyond threads + queue a call is `RESOURCE_EXHAUSTED`, not queued without bound |
| `ludwig.grpc.server.shutdown-grace` | `30s` | in-flight calls get this long on shutdown, then are cancelled |
| `ludwig.grpc.server.tls.bundle` | - | a Spring Boot SSL bundle holding key and certificate |
| `ludwig.grpc.server.tls.client-auth` | `none` | `optional` / `require` - mTLS authenticates the transport; the principal still comes from the token |
| `ludwig.grpc.server.plaintext` | `false` | first of two plaintext switches |
| `ludwig.grpc.allow-plaintext` | `false` | second switch, shared with the client starter |
| `ludwig.grpc.production-profiles` | `prod, production` | plaintext and reflection are refused under these |
| `ludwig.grpc.server.security.enabled` | `true` | `false` serves unauthenticated, and must be written down |
| `ludwig.grpc.server.security.permit-unauthenticated` | `[]` | `pkg.Service/Method` or `pkg.Service/*` |
| `ludwig.grpc.server.reflection.enabled` | `false` | refused under a production profile |

**Plaintext.** With no `tls.bundle` the server would listen in plaintext. That is accepted on a
loopback address, otherwise only with both `plaintext: true` and `ludwig.grpc.allow-plaintext: true`,
and never under a production profile - loopback included. There is deliberately no "the mesh
encrypts" mode: a sidecar that is absent or misconfigured is exactly when the token is readable on
the wire. Every refused configuration is reported at once, each naming its property.

## Health and shutdown

`grpc.health.v1.Health` is always registered, follows Spring's `ReadinessState` for the server as a
whole and for each service by name, and is open to anonymous callers. On shutdown it turns
`NOT_SERVING` first, then the server stops accepting, in-flight calls get `shutdown-grace`, the rest
are cancelled.

## Deployment prerequisites

- **A second port.** Expose `9090` (or your `port`) in the container and the `Service`; a network
  policy can then open gRPC to peers without opening the REST surface.
- **A headless `Service`** for callers, so the client starter's `round_robin` balances per call. A
  normal `Service` balances per connection, and HTTP/2 puts every call on one connection.
- **A gRPC readiness probe** (`grpc: { port: 9090 }`) if you want Kubernetes to use the health
  service; it agrees with the HTTP readiness endpoint.

## Testing

```java
@LudwigGrpcServerTest
@Import({CatalogGrpcService.class, TestTokens.class})
class CatalogGrpcServiceTest {
    @Autowired ManagedChannel channel;   // in-process, same chain as production
}
```

`@LudwigGrpcServerTest` boots this starter's auto-configurations and web-core's problem and
preference configuration, nothing else, on the in-process transport under a random name.
Authentication is on, as in production: declare a `JwtDecoder`, or set
`ludwig.grpc.server.security.enabled=false` in `properties`. Add further auto-configurations with
`@ImportAutoConfiguration`.

## What it deliberately does not do

- **No gRPC-over-servlet.** Shaded Netty on its own port, so gRPC's Netty is independent of the one
  Boot manages and HTTP/2 tuning stays out of Tomcat.
- **No configurable chain order** and no way to put an interceptor before a built-in stage.
- **No behaviour for client-streaming or bidirectional calls** beyond the chain; replay is unary only.
- **No message payload logging.**
- **No wire-compatibility check of `.proto` changes** - see the architecture-rules README.
