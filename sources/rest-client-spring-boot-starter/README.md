# rest-client-spring-boot-starter

***English** · [Русский](README.ru.md)*

Outbound HTTP as configuration. A service declares one named client per dependency in `application.yaml`, and each
gets its own connection pool, timeouts, authentication, Resilience4j policy, serialization, logging, metrics and
audit - no client can starve another. Clients are used as declarative `@LudwigRestClient` interfaces, injected
`RestClient`/`WebClient` beans, or through a registry; one pipeline behind all three, identical in sync and async
mode. A startup validator rejects configurations that are valid field by field and wrong as a whole.

| | |
|---|---|
| Role | starter |
| Depends on (in-repo) | [`audit-core`](../audit-core), [`observability`](../observability-spring-boot-starter), [`outbound-token-core`](../outbound-token-core), [`web-core`](../web-core-spring-boot-starter) |
| Used by | `export`, `reconciliation` starters, `test-support-local`; `crud-service-example` |
| Surfaces | outbound HTTP |

```
ru.ludwigandreas.restclient
├── annotation      @LudwigRestClient, @EnableLudwigRestClients
├── spi             every extension point (depends on nothing else in the module - ArchUnit)
├── config          properties, deep merge, validator, auto-configuration
├── core            registry, runtime, both execution pipelines
├── auth            authenticator factory and shipped providers (token cache/mint/relay from outbound-token-core)
├── resilience      policies, retry decision, failure classifier
├── transport       JDK / Apache / Reactor Netty, TLS, proxy, pool gauges
├── observability   metrics, listeners, redaction, exchange logging, audit
├── error           exception hierarchy and translator chain
├── registrar       interface scanning and proxy registration
└── test            @LudwigRestClientTest
```

## Usage

```xml
<dependency>
    <groupId>ru.ludwigandreas</groupId>
    <artifactId>rest-client-spring-boot-starter</artifactId>
</dependency>
```

```yaml
ludwig:
  rest-client:
    defaults:                 # inherited by every client; per-client keys override
      connect-timeout: 2s
      read-timeout: 10s
      resilience:
        retry: { max-attempts: 3, wait-duration: 200ms, exponential-backoff-multiplier: 2.0, randomized-wait-factor: 0.5 }
        circuit-breaker: { failure-rate-threshold: 50, sliding-window-size: 100, wait-duration-in-open-state: 30s }
    clients:
      billing:                # long socket timeout + OAuth2 client credentials
        base-url: https://billing.internal/api/v1
        read-timeout: 120s
        auth:
          type: oauth2-client-credentials
          registration-id: billing-sa
        resilience:
          bulkhead: { max-concurrent-calls: 20 }
      pricing:                # short timeout + static bearer from a secret
        base-url: https://pricing.internal
        read-timeout: 800ms
        auth:
          type: bearer
          token: ${PRICING_TOKEN}
        resilience:
          retry: { max-attempts: 1 }
```

`billing`: 120s socket timeout, cached machine token refreshed early and re-minted once on a 401, three attempts with
jittered backoff, a breaker over 100 calls, at most 20 concurrent calls. `pricing`: 800ms timeout, static token, no
retries. Separate pools, breakers and permits.

### Three programming models

**1. Declarative interfaces (primary):**

```java
@LudwigRestClient("billing")
public interface BillingApi {

    @GetExchange("/invoices/{id}")
    Invoice invoice(@PathVariable UUID id);

    @PostExchange("/invoices")
    Invoice create(@RequestBody NewInvoice invoice);
}
```

Found by scanning below `@SpringBootApplication`; elsewhere with
`@EnableLudwigRestClients(basePackageClasses = BillingApi.class)`. A return type that does not match the client's
mode (e.g. `Mono` on a `sync` client) fails startup naming interface, method and client.

**2. Injected beans:** every `sync` client is a `RestClient` bean `<name>RestClient` qualified `<name>`; every
`async` client is a `WebClient` likewise. `OrderService(@Qualifier("billing") RestClient billing)`. For ad hoc calls
(a `HEAD`, a streaming download).

**3. Registry:** `registry.rest("billing")`, `registry.reactive("pricing")` - for dependencies chosen at runtime. An
unknown name throws listing the configured ones; a blocking view of a reactive client is refused.

**Programmatic overrides:** `LudwigRestClientCustomizer` / `LudwigWebClientCustomizer` beans run after every
YAML-derived setting:

```java
@Bean
LudwigRestClientCustomizer partnerMediaTypeCustomizer() {
    return new LudwigRestClientCustomizer() {
        @Override public boolean supports(String clientName) { return "partner".equals(clientName); }
        @Override public void customize(String clientName, RestClient.Builder builder) {
            builder.messageConverters(converters -> converters.add(0, new PartnerVendorMediaTypeConverter()));
        }
    };
}
```

### Precedence

1. Built-in defaults (`ClientPropertiesMerger.builtInDefaults()`);
2. the `defaults` block;
3. the client's block;
4. customizer beans;
5. per-request headers.

Levels 1-3 are an explicit deep merge:

| Kind | Rule |
|---|---|
| Scalars | Higher layer wins when set |
| Nested blocks | Merged field by field (setting `retry.max-attempts` keeps the inherited backoff) |
| Lists | **Replaced** (`retry-on-status: [503]` means only 503) |
| `additional-*` lists | **Concatenated** |
| A declared `bulkhead` / `rate-limiter` block | **Enables the policy** (`enabled: false` still wins where stated) |
| Maps (`default-headers`) | Merged per key |
| `base-url`, `auth.relay-enabled` | **Never inherited** |

**Per-request headers** (consumed, never sent):

| Header | Effect |
|---|---|
| `X-Ludwig-Retry: true` | Retry a non-idempotent request; the caller asserts it is safe to repeat (idempotency key, unique constraint) |
| `X-Ludwig-Retry: false` | Suppress a retry |
| `X-Ludwig-Max-Attempts: 2` | Lower (never raise) the attempt limit |

### The pipeline

```
RateLimiter → Bulkhead → CircuitBreaker → Retry → TimeLimiter → [authenticate → send]
```

Identical in both modes and not configurable:

- **Retry inside the breaker**: one permit and one recorded result per *logical* call; an open breaker turns three
  attempts into zero instead of amplifying load on a failing dependency.
- **Bulkhead outside retry**: the cap bounds calls in flight, not attempts.
- **Rate limiter outermost**: the partner sees the configured rate, not a multiple.
- **Time limiter innermost**: bounds one attempt; `retry.max-elapsed-time` bounds the call.
- **Authentication inside retry**: makes `401 → refresh → retry` possible.

The breaker records 408, 429, 500, 502, 503, 504; **not** 400, 401, 403, 404, 409, 422 (a client error means the peer
is healthy and this service sent something wrong), nor authentication failures or refused calls.

Breaker, bulkhead, rate limiter and time limiter are Resilience4j objects in the application's Micrometer-bound
registries, named after the client. **Retry is this module's own** (on Resilience4j's `IntervalFunction`): it honours
`Retry-After`, keeps a wall-clock budget, and shares decisions between blocking and reactive paths. So
`resilience4j.retry.*` meters do not exist for these clients; `ludwig.restclient.retries` does.

### Authentication

| `auth.type` | Keys | Notes |
|---|---|---|
| `none` | - | Default |
| `basic` | `username`, `password` | Refused over plain HTTP to a non-loopback host |
| `bearer` | `token` **or** `token-supplier`, `scheme` | Exactly one |
| `api-key` | `key`, `header-name` **or** `query-param-name`, `value-prefix` | Query form warns (URIs reach proxy logs) |
| `oauth2-client-credentials` | `registration-id`, `refresh-skew`, `scopes` | Cached, refreshed `refresh-skew` (30s) early, single-flight, one re-mint on a 401 outside the retry budget |
| `oauth2-token-relay` | `relay-enabled`, `fail-when-no-token` | See below |
| `custom` | `authenticator` | A named `ClientAuthenticator` bean |

Token cache, minting and relay reading are [`outbound-token-core`](../outbound-token-core).

**Token relay** hands callers' credentials to a third party; three guards: `relay-enabled: true` on the client itself
(never inherited); no inbound token → the call fails instead of going out anonymously; a relay client in a service
that is not a resource server fails startup.

**A new authentication type** is one `ClientAuthenticationProvider` bean:

```java
public class HawkAuthenticationProvider implements ClientAuthenticationProvider {

    @Override public String type() { return "hawk"; }

    @Override public Class<? extends ClientAuthProperties> propertiesType() { return HawkProperties.class; }

    @Override public ClientAuthenticator create(String clientName, ClientAuthProperties properties) {
        HawkProperties hawk = (HawkProperties) properties;
        Mac mac = Mac.getInstance("HmacSHA256");   // expensive work here, not per request
        mac.init(new SecretKeySpec(hawk.getKey().getBytes(UTF_8), "HmacSHA256"));
        return new ClientAuthenticator() {
            @Override public void authenticate(AuthRequest request) {
                String nonce = UUID.randomUUID().toString();
                String payload = request.method() + "\n" + request.uri().getPath() + "\n" + nonce;
                String signature = Base64.getEncoder().encodeToString(mac.doFinal(payload.getBytes(UTF_8)));
                request.headers().set("Authorization",
                        "Hawk id=\"" + hawk.getId() + "\", nonce=\"" + nonce + "\", mac=\"" + signature + "\"");
            }
            @Override public String describe() { return "hawk(id=" + hawk.getId() + ")"; }
        };
    }
}
```

```yaml
ludwig:
  rest-client:
    clients:
      partner:
        base-url: https://partner.example.com
        auth: { type: hawk, id: "${HAWK_ID}", key: "${HAWK_KEY}" }
```

The `auth` block binds onto `HawkProperties` from `defaults.auth`, then the client's block. A provider whose `type()`
is a built-in name replaces that built-in. For one client without new properties, use `type: custom` with
`auth.authenticator: <bean>`.

### Errors

| Exception | Meaning | Rendered by web-core if it escapes a controller |
|---|---|---|
| `RestClientResponseException` | Peer answered with a failure status (status, redacted headers, body snippet, parsed `ProblemDetail`) | 429 → 429; **4xx → 500** (this service sent something invalid); 5xx → 503 `upstream-unavailable` |
| `RestClientTimeoutException` | A deadline passed - the request **may** have been processed | 503 |
| `RestClientConnectionException` | Request never reached the peer | 503 |
| `RestClientAuthenticationException` | No usable credential | 500 |
| `RestClientCallNotPermittedException` | This client's own policy refused (breaker, bulkhead, rate limit) | 503 |

Each carries client name and correlation id in the message. Error handling applies to `retrieve()`, not
`exchange()`. A partner with a non-RFC 9457 envelope gets a `ResponseErrorTranslator` bean:

```java
@Component
public class PartnerErrorTranslator implements ResponseErrorTranslator {
    @Override public boolean supports(String clientName) { return "partner".equals(clientName); }
    @Override public RuntimeException translate(ErrorContext context) {
        if (context.statusCode() != 409 || context.body() == null) {
            return null;                                   // defer to the next translator
        }
        return new DuplicateOrderException(readOrderId(context.body()));
    }
}
```

Translators run in `@Order` order; the built-in one runs last.

### Fallbacks

```yaml
ludwig:
  rest-client:
    clients:
      billing:
        resilience:
          fallback:
            handler: cachedInvoiceFallback         # every method
            methods:
              BillingApi#invoice: cachedInvoiceFallback
```

Only for failures meaning *the call could not be made or completed*; never for a 4xx or an authentication failure.
Declarative interfaces only.

### Listeners

`RestClientListener` beans observe `onResponse`, `onRetry` etc. per client. A listener that throws never breaks the
call (logged once per class, counted); never use one to enforce anything; do not block in it.

### Transports and TLS

| `transport` | Pool | HTTP/2 | Notes |
|---|---|---|---|
| `http-client` (default) | not configurable | yes | JDK; a `pool` block is reported as ignored |
| `apache` | configurable | no | Choose when the pool matters |
| `reactor-netty` | configurable | yes | Implied by `mode: async` |

Pools are never shared between clients. `compression: true` behaves the same on all three. TLS is per client
(truststore, keystore for mTLS, protocols TLSv1.3/1.2, cipher suites, hostname verification). `tls.trust-all` and
`tls.hostname-verification: false` need the top-level `ludwig.rest-client.allow-trust-all: true` **and** no active
production profile.

### Testing your clients

```java
@LudwigRestClientTest
@TestPropertySource(properties = {
    "ludwig.rest-client.clients.billing.base-url=http://localhost:${wiremock.server.port}",
    "ludwig.rest-client.clients.billing.resilience.retry.max-attempts=2"
})
class BillingApiTest {
    @Autowired BillingApi billing;
    @Test void retriesOnce() { ... }
}
```

The slice boots only this starter and discovers interfaces as production does.

## Configuration

Durations accept `200ms`, `2s`, `5m`. Keys marked **refresh** are re-read once per second per client (with hot-reload,
an actuator refresh or a Vault renewal); a call in flight keeps its policy; an invalid new value leaves the previous
one in effect with a warning. Everything else needs a restart (baked into pools, `SSLContext`, authenticators,
breaker windows).

**Top level:**

| Property | Default | Meaning |
|---|---|---|
| `ludwig.rest-client.enabled` | `true` | `false` registers nothing |
| `ludwig.rest-client.allow-trust-all` | `false` | Second switch for TLS relaxations |
| `ludwig.rest-client.production-profiles` | `prod,production` | TLS relaxations refused under these |
| `ludwig.rest-client.metrics.enabled` | `true` | |
| `ludwig.rest-client.metrics.max-uri-tags` | `100` | Overflow folds into `OTHER` |
| `ludwig.rest-client.metrics.pool` | `true` | Pool gauges where the engine reports them |

**Per client** (under `ludwig.rest-client.clients.<name>` or `.defaults`):

| Key | Default | Reload | Notes |
|---|---|---|---|
| `base-url` | - | restart | Required; never inherited |
| `mode` | `sync` | restart | `sync` / `async` |
| `transport` | `http-client` | restart | `http-client`, `apache`, `reactor-netty` |
| `connect-timeout` | `2s` | restart | |
| `read-timeout` | `10s` | restart | Longest gap between bytes, not a deadline |
| `connection-request-timeout` | `2s` | restart | Wait for a pooled connection |
| `request-timeout` | - | restart | Deadline for one attempt |
| `redirects` | `NORMAL` | restart | `NORMAL`, `NEVER`, `ALWAYS` |
| `compression` | `true` | restart | |
| `http2` | `false` | restart | |
| `user-agent` | `<app> (ludwig-rest-client; client=<name>)` | restart | |
| `default-headers.*`, `default-query-params.*` | - | restart | Merged per key; call-site query value wins |
| `pool.max-total` / `max-per-route` | `50` / `20` | restart | `apache`/`reactor-netty` |
| `pool.idle-eviction` / `time-to-live` / `validate-after-inactivity` / `keep-alive` | `30s` / `5m` / `2s` / `1m` | restart | |
| `tls.*`, `proxy.*` | - | restart | Per client |
| `auth.*` | `type: none` | restart | |
| `resilience.enabled` | `true` | restart | |
| `resilience.retry.enabled` / `max-attempts` | `true` / `3` | **refresh** | |
| `resilience.retry.wait-duration` / `exponential-backoff-multiplier` / `randomized-wait-factor` | `200ms` / `2.0` / `0.5` | **refresh** | |
| `resilience.retry.max-elapsed-time` | derived | **refresh** | Wall-clock budget (default: every attempt at full timeout plus waits) |
| `resilience.retry.retry-on-status` | `408,425,429,500,502,503,504` | **refresh** | Replaced, not extended |
| `resilience.retry.retry-on-exception` | - | restart | Class names |
| `resilience.retry.idempotent-methods-only` | `true` | **refresh** | |
| `resilience.retry.respect-retry-after` / `max-retry-after` | `true` / `30s` | **refresh** | |
| `resilience.circuit-breaker.*` | 50% of 100, open 30s | restart | |
| `resilience.bulkhead.enabled` | `false`, `true` if the block is configured | restart | |
| `resilience.bulkhead.type` / `max-concurrent-calls` / `max-wait-duration` | `SEMAPHORE` / `25` / `0` | restart | `THREAD_POOL` and non-zero wait refused for `async` |
| `resilience.rate-limiter.enabled` | `false`, `true` if configured | restart | Per process: quota ÷ replicas |
| `resilience.rate-limiter.limit-for-period` / `timeout-duration` | `100` / `0` | **refresh** | |
| `resilience.rate-limiter.limit-refresh-period` | `1s` | restart | |
| `resilience.time-limiter.*` | disabled | restart | `async` only |
| `resilience.fallback.handler` / `.methods` | - | restart | Bean names |
| `logging.level` | `BASIC` | **refresh** | `NONE`, `BASIC`, `HEADERS`, `BODY` |
| `logging.max-body-size` | `2048` | **refresh** | |
| `logging.redacted-headers` / `redacted-fields` | see Observability | **refresh** | Replaced |
| `logging.additional-redacted-headers` / `-fields` | - | **refresh** | Concatenated |
| `logging.log-request-before-call` | `false` | **refresh** | Useful when a call hangs |
| `audit.enabled` | `false` | restart | |
| `audit.sampling-probability` | `1.0` | **refresh** | Successes only |
| `audit.sink` | platform `AuditSink` | restart | Bean name of an `AuditSink`, per client |
| `audit.include-request-headers` / `-response-headers` | - | restart | Allow-list |
| `serialization.*` | application's mapper | restart | Forked only when something is set |

## Deployment

- Secrets (`auth.token`, `auth.password`, API keys, client secrets, keystore passwords) come from the secret store via
  placeholders; never commit them.
- Truststores/keystores mounted per client.
- Rate limits are per process: set `limit-for-period` to the partner's quota divided by replicas, and revisit when
  scaling.
- `base-url` points at a Kubernetes service or gateway; the client does no service discovery.

## Observability

| Metric | Tags | Meaning |
|---|---|---|
| `ludwig.restclient.requests` (timer) | `client`, `method`, `uri`, `status`, `outcome`, `exception` | RED per dependency; `uri` is the **template**, capped at `max-uri-tags` |
| `ludwig.restclient.retries` | `client`, `reason` | Attempts beyond the first; ÷ requests = retry rate, the earliest sign of degradation |
| `ludwig.restclient.circuitbreaker.transitions` | `client`, `from`, `to` | Counted (a 15s gauge misses short open periods) |
| `ludwig.restclient.auth.token.refreshes` | `client`, `type` | Tokens minted; far above one per lifetime = cache not working |
| `ludwig.restclient.calls.notpermitted` | `client`, `policy` | Calls this service refused (load shed) |
| `ludwig.restclient.listener.failures` | `client`, `listener`, `callback` | A listener threw |
| `ludwig.restclient.pool.{leased,pending,available,max}` | `client` | Apache only; `pending` rises before latency does |
| `resilience4j.circuitbreaker.state`, `resilience4j.bulkhead.*`, `resilience4j.ratelimiter.*` | `name` = client | Resilience4j's own meters |
| `ludwig.audit.sink.failures{category="outbound-call"}` | | Audit sink failures (platform metric) |

No credential, token, header value or expanded URI appears in a tag.

**Tracing**: W3C context via Spring's client instrumentation; correlation id via observability's
`CorrelationPropagatingRequestInterceptor` (and an equivalent filter for `WebClient`).

**Logs**: per client on `ludwig.restclient.<client>` (raise one dependency to DEBUG without the others). Completed
calls at `INFO`, failed at `WARN`. `logging.level` decides what is written (data decision); the SLF4J level decides
whether (operational decision). Bodies are materialised only on failure or at `BODY` level **and** with a declared
`Content-Length` under 256 KiB; streaming responses are never buffered. Redacted by default: headers `Authorization`,
`Proxy-Authorization`, `Cookie`, `Set-Cookie`, `X-Api-Key`, `X-Auth-Token`, `Api-Key`; JSON fields `password`,
`secret`, `token`, `access_token`, `refresh_token`, `id_token`, `client_secret`, `pin`, `otp`, `card_number`, `cvv`
(structural walk via audit-core's `Redactor`; per-client lists only widen platform rules).

**Recommended alerts:**

| Condition | Why |
|---|---|
| `increase(ludwig_restclient_circuitbreaker_transitions_total{to="OPEN"}[5m]) > 0` | Dependency failing |
| `rate(ludwig_restclient_retries_total[5m]) / rate(ludwig_restclient_requests_seconds_count[5m]) > 0.05` | Early degradation |
| `ludwig_restclient_pool_pending > 0` for 1 min | Pool saturated |
| `rate(ludwig_restclient_auth_token_refreshes_total[1h])` far above 1/token lifetime | Token cache broken |

## Audit

Opt-in per client (`audit.enabled`). Category `outbound-call`. A record carries client, method, **templated** URI,
status, outcome, duration, attempts, correlation id, trace id, principal, timestamp, allow-listed headers, failure type
- no expanded URI, no bodies. Sampling applies to successes only; failures, refusals and authentication errors are
always recorded. Refusals by this client's own policy are `DENIED`. Destination, failure policy and retention are the
platform's (`ludwig.audit.*`); `audit.sink` can send one partner's trail to a dedicated `AuditSink` bean.

## Runbook

| What happened | Exception | Log | Metrics | What to do |
|---|---|---|---|---|
| Peer returns 503, retries succeed | none | `WARN` per failed attempt | `requests{status=200}`, `retries{reason="status:503"}` | Watch; contact the peer if sustained |
| Peer returns 503, budget spent | `RestClientResponseException` | `WARN` per attempt | `requests{status=503}`, `retries` | Peer incident |
| Breaker opens | `RestClientCallNotPermittedException` | none (no call made) | `calls.notpermitted{policy="circuit-breaker"}`, `transitions{to="OPEN"}` | Peer incident; breaker half-opens after `wait-duration-in-open-state` |
| Bulkhead full / rate limit | `RestClientCallNotPermittedException` | none | `calls.notpermitted{policy=...}` | Load above capacity; scale or raise limits within the partner's quota |
| Read timeout | `RestClientTimeoutException` | `WARN` with duration | `requests{outcome=UNKNOWN}` | Request may have been processed - do not blindly repeat non-idempotent calls |
| Connection refused | `RestClientConnectionException` | `WARN` | as above | Network / DNS / peer down |
| Token endpoint refuses | `RestClientAuthenticationException` | `WARN`, never the credential | `auth.token.refreshes` stops | Check the OAuth2 registration and secret |
| Relay client, no inbound token | `RestClientAuthenticationException` | `WARN` | - | Called from a job/listener: use a machine token |
| Listener throws | none | `WARN` once per class | `listener.failures` | Fix the listener |
| Startup fails listing problems | - | - | - | Fix every listed item (see validator list below) |

**The startup validator refuses**: missing `base-url` or one in `defaults`; credentials over plain HTTP to a
non-loopback host; a retry budget that cannot fit two attempts; `request-timeout` ≤ `connect-timeout`; a time limiter on
`sync`, a thread-pool bulkhead or bulkhead wait on `async`; `trust-all` without the second switch or under a production
profile; a keystore without password; an unknown `auth.type`; `bearer` with token and supplier; relay without
`relay-enabled` or without a resource server; a breaker that can never open; a rate-limiter wait above the read
timeout; a missing engine, exception class or Jackson module. All at once. **Warnings**: pool block on the JDK
transport, `http2` on Apache, API key in a query parameter, 4xx recorded as breaker failures, retrying non-idempotent
methods by default.

## Testing

`@LudwigRestClientTest` slice (above). The module's own suites: merge and validator (`ClientPropertiesMergerTest`,
`ConfigurationValidatorTest`), retry policy and `Retry-After`, redaction, authentication providers, and integration tests
for decorator order, retry, resilience, auth, fallbacks, observability, refresh and reactive parity, plus
`SpiPurityTest` and `TokenOwnershipTest`.

## Design decisions

- **One named client per dependency**: shared timeouts and pools turn one partner's bad afternoon into an outage of
  everything.
- **Resilience is a property of the dependency, not of a method**: no `@Retryable`-style annotations; a call site can
  only lower the attempt limit.
- **No service discovery, response caching, cluster-wide rate limiting, or retry of streaming bodies** (a `sync` body
  is buffered so it can be resent; large uploads should disable retries).
- **Integrations are optional**: observability (correlation, trace ids, URI cap, runtime log levels), web-core (problem
  rendering), security (audited principal, relay), hot-reload (refreshable keys). Without them clients work; audit
  records carry `null` for correlation or principal.
