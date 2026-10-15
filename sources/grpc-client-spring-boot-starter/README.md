# grpc-client-spring-boot-starter

Named gRPC clients as configuration. Declare a client under `ludwig.grpc.client.clients.<name>`,
inject a generated stub with `@LudwigGrpcClient("<name>")`, and every call through it carries a
deadline, runs behind a rate limiter, bulkhead and circuit breaker, is retried only where the
contract says a repeat is safe, authenticates as configured, fails with a typed exception, and is
measured, logged without its payload and - if asked - audited. No `@Configuration` of your own.

The contract and the shared vocabulary (metadata keys, the status codec) are in
[grpc-core](../grpc-core/README.md); the serving side is
[grpc-server-spring-boot-starter](../grpc-server-spring-boot-starter/README.md). The shape follows
[rest-client-spring-boot-starter](../rest-client-spring-boot-starter/README.md) wherever gRPC has
the same concept, and only there.

## Quick start

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

```java
@Service
class NotificationSender {
    private final NotificationServiceGrpc.NotificationServiceBlockingStub stub;

    NotificationSender(@LudwigGrpcClient("notifications")
                       NotificationServiceGrpc.NotificationServiceBlockingStub stub) {
        this.stub = stub;
    }
}
```

Three ways to consume a client, one behaviour - all three hand out the same intercepted channel:

1. **Injected stub** - `@LudwigGrpcClient("notifications")` on a field, a constructor parameter or a
   `@Bean` method parameter of a blocking, future or async stub type.
2. **Injected channel** - the `Channel` bean `notificationsGrpcChannel`, qualified `notifications`
   (`@Qualifier("notifications")` or `@LudwigGrpcClient("notifications")`).
3. **Registry** - `GrpcChannelRegistry.channel("notifications")`; an unknown name throws and lists
   the configured ones.

A stub bound to a name that is not configured fails startup naming the injection point.

## Precedence

Built-in values, then `defaults`, then the client. Scalars override when set; nested blocks merge
field by field (a client that sets only `sliding-window-size` keeps the inherited threshold); lists
and maps replace. **`target` and `auth.relay-enabled` are never inherited** - a target in `defaults`
is a startup failure, and relaying a caller's token is decided per client. A `bulkhead` or
`rate-limiter` block that is written anywhere enables that policy; the built-in layer has neither.

## The pipeline, and why it is fixed

```
observation -> correlation -> log/audit -> failure typing
            -> rate limiter -> bulkhead -> circuit breaker -> deadline -> credentials
            [-> gRPC retry -> send, inside the channel]
```

Not configurable, because every other order has a defect. The three Resilience4j policies take one
permit and record one result per **logical** call, around gRPC's own retry: a call that succeeds on
its third attempt is one success, and three failed attempts are one failure. A breaker that counted
attempts would open three times faster during exactly the incident the retries were absorbing -
the same reasoning, and the same order, as `rest-client`. Observation and the log are outermost, so
a call refused by this client's own policy is still measured and audited as refused. Credentials are
innermost, so every attempt carries the current token.

What the breaker counts: `UNAVAILABLE`, `DEADLINE_EXCEEDED`, `INTERNAL`, `UNKNOWN`,
`RESOURCE_EXHAUSTED`, `DATA_LOSS`. Nothing else - a peer that answered `INVALID_ARGUMENT`,
`NOT_FOUND` or `PERMISSION_DENIED` is healthy and the request was wrong, and an authentication
failure is not the dependency's failure. Instances are named `grpc-<client>` in the application's
Resilience4j registries (or private ones when it has none), so a REST client and a gRPC client with
the same name never share a breaker.

## Retry is read from the contract

A method is retried only when its `.proto` declares `idempotency_level = IDEMPOTENT` or
`NO_SIDE_EFFECTS`, and only on `retry.retry-on` (built-in `UNAVAILABLE`). Every other method gets
only gRPC's transparent retry, which repeats a request that provably never left the process. There
is deliberately no call-site switch, nothing like `X-Ludwig-Retry`: whether a repeat is safe is the
server owner's statement, made once in the contract where they can see it. The retry is gRPC's own
(commit-aware, jittered exponential backoff, retry throttling, server pushback honoured) rather
than an interceptor's, because replaying a call from an interceptor is code that works until it
loses a message. The starter finds the contracts by scanning `ru.ludwigandreas` for generated
`<Service>Grpc` classes, which `check_protos.sh` places there.

## Deadlines

Every call gets the earliest of: the client's `deadline` (built-in 5s), a deadline the call site set,
and - applied by gRPC from the current `Context` - what remains of the inbound call it is made
within. A handler with 500 ms left cannot start a 3 s call. The deadline bounds the logical call,
retries included. A client whose deadline is not positive fails startup; there is no way to switch
deadlines off.

## Authentication

| `auth.type` | |
|---|---|
| `none` | built-in |
| `bearer` | a static `auth.token` |
| `oauth2-client-credentials` | a machine token from `auth.registration-id` (a `spring.security.oauth2.client.registration`), cached and renewed `auth.refresh-skew` (30s) before it expires, minted once however many calls ask at the same moment |
| `oauth2-token-relay` | the caller's own bearer token, read on the caller's thread |
| anything else | a `GrpcCallCredentialsProvider` bean whose `type()` matches; it receives `auth.properties` |

On `UNAUTHENTICATED`, a unary call with a machine token is attempted **once** more with a newly
minted token, outside the retry budget and inside the deadline; a second rejection is reported as
`GrpcClientAuthenticationException`.

Token relay keeps three guards: `relay-enabled: true` on the client itself, never inherited; no
inbound token means the call fails before anything is sent - never anonymously; and a service that
is not a resource server (no `JwtDecoder`) cannot configure it at all.

A credential over plaintext to a non-loopback target fails startup.

### Where the tokens come from

The machine-token cache, the client-credentials mint and the reader of the caller's token are
[`outbound-token-core`](../outbound-token-core/README.md), the one implementation this starter shares
with `rest-client-spring-boot-starter`. What is this module's own is how a token reaches the wire: as
gRPC `CallCredentials`, applied per attempt. `TokenOwnershipTest` fails the build if a second cache,
minter or token reader appears here.

## Failures

A stub throws `StatusRuntimeException`, unchanged, with the peer's status and trailers. Its cause is
one of these, each naming the client and the correlation id - read it with
`GrpcClientException.from(e)`:

| Exception | When | Escaping a controller |
|---|---|---|
| `GrpcClientResponseException` | the peer answered with a failure; carries the code and the decoded `ProblemDetail` | 429 for `RESOURCE_EXHAUSTED`; **500** for a caller-side code (`INVALID_ARGUMENT`, `NOT_FOUND`, `FAILED_PRECONDITION`, ...) - this service sent it; otherwise 503 `upstream-unavailable` |
| `GrpcClientTimeoutException` | the deadline passed - the request **may** have been processed | 503 |
| `GrpcClientConnectionException` | no connection, or it broke before an answer | 503 |
| `GrpcClientAuthenticationException` | no credential could be obtained, or the peer rejected it | 500 |
| `GrpcClientCallNotPermittedException` | this client's rate limiter, bulkhead or breaker refused; nothing was sent | 503 |

The rendering is a contributed `ExceptionProblemMapper`: there is no advice in this module, and
web-core's registry reaches the typed cause by walking the cause chain.

## Observability and audit

- `ludwig.grpcclient.requests{client,service,method,status}` - Micrometer's gRPC observation, renamed;
  also the client span.
- `ludwig.grpcclient.retries{client,method}` - counted by a stream tracer per attempt, because
  in-channel retries are invisible to an interceptor.
- `ludwig.grpcclient.calls.notpermitted{client,policy}` and
  `ludwig.grpcclient.auth.token.refreshes{client,type}`.
- One INFO line per call on `ludwig.grpcclient.<client>`: method, status, duration, attempts,
  correlation id. **No payload.** `logging.level: payload` renders messages as JSON through
  audit-core's `Redactor`, capped at `max-payload-chars`, and is refused under a production profile.
- The correlation id goes out as `x-correlation-id` (observability's header name): the inbound call's
  when there is one, otherwise observability's current id; without observability, none.
- `audit.enabled: true` records `GrpcCallAudit` events into the one `AuditSink` under
  `category=outbound-call`: every failure and refusal (a refusal as `DENIED`), successes sampled by
  `audit.success-sampling`. No payload, no metadata.

## Configuration

**Restart** means the value is baked into the channel; **refresh** means it is re-read from the bound
properties (at most once a second), and a call in flight keeps the value it started with.

| Key (under `ludwig.grpc.client.clients.<name>` or `.defaults`) | Built-in | |
|---|---|---|
| `target` | - | restart; never inherited; `dns:///host:port`, `host:port`, or `in-process:<name>` in tests |
| `deadline` | `5s` | refresh |
| `load-balancing` | `round_robin` | restart; `pick_first` is logged at WARN |
| `plaintext` | `false` | restart; needs `ludwig.grpc.allow-plaintext` off loopback, never in production |
| `tls.bundle` | JDK trust store | restart; a Spring Boot SSL bundle, with a key for mTLS |
| `max-inbound-message-size` | `4MB` | restart |
| `idle-timeout` | `30m` | restart |
| `keepalive.time` / `.timeout` / `.without-calls` | `30s` / `20s` / `false` | restart; `time` must not be below the server's `keepalive.permit-time` - move them together |
| `resilience.circuit-breaker.*` | on; 50% of 100 calls, minimum 20, open 30s | restart |
| `resilience.bulkhead.max-concurrent-calls` / `.max-wait` | off; `25` / `0` once declared | restart |
| `resilience.rate-limiter.limit-for-period` / `.refresh-period` / `.timeout` | off; `100` / `1s` / `0` once declared | limit: refresh; the rest: restart |
| `retry.enabled` / `.max-attempts` | `true` / `3` | restart |
| `retry.initial-backoff` / `.max-backoff` / `.backoff-multiplier` | `100ms` / `2s` / `2` | restart |
| `retry.retry-on` | `[UNAVAILABLE]` | restart |
| `retry.throttling-max-tokens` / `.throttling-token-ratio` | `10` / `0.1` | restart |
| `auth.type` / `.token` / `.registration-id` / `.scopes` / `.refresh-skew` / `.properties` | `none`, -, -, -, `30s`, - | restart |
| `auth.relay-enabled` | - | restart; never inherited |
| `logging.level` / `.max-payload-chars` | `basic` / `2048` | refresh |
| `audit.enabled` / `.success-sampling` | `false` / `1.0` | refresh |

Shared with the server starter: `ludwig.grpc.allow-plaintext` (`false`) and
`ludwig.grpc.production-profiles` (`prod, production`).

Everything that is valid key by key and wrong as a whole - no target, a target in defaults, a
non-positive deadline, a credential over plaintext, plaintext without both switches or in
production, an unknown auth type, relay without its switch or without a resource server, a breaker
that cannot open, payload logging in production - fails startup **once**, listing every problem in
every client.

## Testing

```java
@LudwigGrpcClientTest(properties = "ludwig.grpc.client.clients.notifications.target=in-process:peer")
class NotificationSenderTest { ... }
```

`@LudwigGrpcClientTest` boots this starter, web-core's problem registry and audit-core. Point a
client at an in-process server the test starts (`InProcessServerBuilder.forName("peer")`) and every
call runs the production pipeline.

## What it deliberately does not do

- **No call-site retry opt-in**, and no hedging.
- **No per-attempt timeout** - gRPC has no stable one, and the deadline bounds the logical call.
- **No "the mesh encrypts" mode**: a sidecar that is absent is exactly when the token is readable.
- **No behaviour for client-streaming or bidirectional calls** beyond the pipeline; the
  `UNAUTHENTICATED` re-attempt is unary only.
- **No sharing of a channel between clients**, even to the same target.
- **No dependency on `rest-client`**: what both share is `outbound-token-core`, which depends on neither.
