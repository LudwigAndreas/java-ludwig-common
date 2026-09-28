# rest-client-spring-boot-starter

***English** · [Русский](README.ru.md)*

Outbound HTTP as configuration. A service declares as many independent HTTP clients as it has
dependencies, in `application.yaml`, and each one gets its own connection pool, timeouts,
authentication, Resilience4j policy, serialization, logging, metrics and audit. Nothing about a
client requires a `@Configuration` class in the consuming service.

The clients are then used in whichever of three ways fits the call: a declarative interface, an
injected `RestClient`/`WebClient`, or a registry lookup. All three run through one pipeline, so they
behave identically.

## Quick start

```xml
<dependency>
    <groupId>ru.ludwigandreas</groupId>
    <artifactId>rest-client-spring-boot-starter</artifactId>
    <version>1.0.0</version>
</dependency>
```

Two dependencies with genuinely different needs - one slow and machine-authenticated, one fast and
key-authenticated - and nothing else:

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

`billing` now has a 120-second socket timeout, a machine token that is cached, refreshed early and
re-minted once on a 401, three attempts with jittered backoff, a breaker over a 100-call window and
a cap of 20 concurrent calls. `pricing` has an 800-millisecond timeout, a static token, and no
retries at all. Neither can affect the other: separate pools, separate breakers, separate permits.

Calling them:

```java
@LudwigRestClient("billing")
public interface BillingApi {

    @GetExchange("/invoices/{id}")
    Invoice invoice(@PathVariable UUID id);
}
```

```java
@Service
class OrderService {

    private final BillingApi billing;                 // declarative interface
    private final RestClient pricing;                 // injected by qualifier

    OrderService(BillingApi billing, @Qualifier("pricing") RestClient pricing) { ... }
}
```

and, when the dependency is chosen at runtime:

```java
registry.rest("billing").get().uri("/invoices/{id}", id).retrieve().body(Invoice.class);
registry.reactive("pricing").get().uri("/prices/{sku}", sku).retrieve().bodyToMono(Price.class);
```

## The problem this starter exists to remove

Every service in the estate talks to several others, and each of them ends up with some version of
the same file: a `@Configuration` class building a `RestTemplate` or a `RestClient`, a
`ClientHttpRequestFactory` with timeouts somebody chose in 2023, an interceptor that adds a token, a
`@Retryable` somewhere, and - if the service was written after an incident - a circuit breaker.

Six copies of that file in six services are six different answers to the same questions, and the
differences are invisible until they matter:

- **Timeouts are shared where they should not be.** One `RestTemplate` bean, or one set of JVM-wide
  defaults, means the slow dependency's timeout is also the fast one's. The usual outcome is a
  120-second read timeout everywhere, and a service that hangs for two minutes on a health check.
- **Pools are shared where they should not be.** A saturated dependency consumes the connections the
  other five need, so one partner's bad afternoon becomes an outage of everything.
- **Retry is configured without a circuit breaker.** Three attempts each, from every caller, against
  a service that is already failing, is three times the load at the worst possible moment.
- **Retry is applied to POSTs.** A read timeout says the answer did not arrive; it says nothing
  about whether the peer processed the request. The duplicate payment is found weeks later.
- **A credential ends up in a log.** Body logging is added during an incident, and nobody removes it.
- **Metrics are tagged with expanded URIs.** `/invoices/7f3a…` becomes a new time series per
  customer, and the registry grows until the process dies.

None of these is hard to get right once. The problem is getting them right six times, and keeping
them right as the six services diverge. This starter makes them properties of a deployment rather
than of a source file.

## What you get, out of the box

| | |
|---|---|
| Named clients | One pool, one policy, one identity per dependency; nothing shared between them |
| Three programming models | Declarative interfaces, injected `RestClient`/`WebClient`, registry lookup |
| Sync and async | `mode: sync` (default) or `mode: async`; identical behaviour, identical configuration |
| Transports | JDK `HttpClient` (default), Apache HttpClient 5, Reactor Netty |
| Authentication | `none`, `basic`, `bearer`, `api-key`, `oauth2-client-credentials`, `oauth2-token-relay`, `custom` - and any type a service adds by publishing one bean |
| Resilience | Retry, circuit breaker, bulkhead, rate limiter, time limiter, fallback - Resilience4j, in the shared Micrometer-bound registries |
| Observability | `ludwig.restclient.requests` timer with bounded URI cardinality, client spans, retry/breaker/token/refusal counters, pool gauges |
| Typed errors | An exception hierarchy that knows which dependency failed, with RFC 9457 parsing and a translator SPI |
| Audit | An opt-in, sampling-aware record of outbound calls with allow-listed headers and no payloads |
| Startup validation | A configuration that is individually valid and jointly wrong fails the pod, not the first request |

## The three programming models

### 1. Declarative interfaces - the primary model

An interface with Spring's HTTP interface annotations, bound to a named client:

```java
@LudwigRestClient("billing")
public interface BillingApi {

    @GetExchange("/invoices/{id}")
    Invoice invoice(@PathVariable UUID id);

    @PostExchange("/invoices")
    Invoice create(@RequestBody NewInvoice invoice);
}
```

A proxy is registered as a bean, so the interface is injected like any other collaborator. It is
found without any annotation on the application: the scan covers the packages below
`@SpringBootApplication`. Interfaces that live elsewhere - a shared contracts artifact, say - are
reached with `@EnableLudwigRestClients(basePackageClasses = BillingApi.class)`.

Return types must match the client's mode, and the mismatch is a **startup** failure naming the
interface, the method and the client:

```
ru.example.BillingApi does not match its client's mode:
  - invoice returns Mono, but client 'billing' is mode=sync. Set mode: async on the client, or
    return a value type.
```

### 2. Injected `RestClient` / `WebClient`

Every `sync` client is also a `RestClient` bean named `<name>RestClient` and qualified `<name>`;
every `async` client is a `WebClient` the same way:

```java
OrderService(@Qualifier("billing") RestClient billing) { ... }
```

This is the right model for a call that is genuinely ad hoc - a one-off `HEAD`, a streaming
download, a request whose shape does not fit an interface method.

### 3. Registry lookup

```java
RestClient billing = registry.rest("billing");
WebClient pricing = registry.reactive("pricing");
```

For code that chooses its dependency at runtime, and for tests. Asking for a client that is not
configured throws immediately, listing the ones that are; asking for a blocking view of a reactive
client is refused rather than quietly blocking an event loop.

### Programmatic overrides

Anything the properties cannot express is a `LudwigRestClientCustomizer` (or
`LudwigWebClientCustomizer`) bean. They run **after** every YAML-derived setting, which is what makes
them an override:

```java
@Bean
LudwigRestClientCustomizer partnerMediaTypeCustomizer() {
    return new LudwigRestClientCustomizer() {
        @Override public boolean supports(String clientName) { return "partner".equals(clientName); }

        @Override public void customize(String clientName, RestClient.Builder builder) {
            builder.messageConverters(converters ->
                converters.add(0, new PartnerVendorMediaTypeConverter()));
        }
    };
}
```

## Precedence

Five levels, lowest first. It is documented here because it is the thing people get wrong when a
setting "does not take effect":

1. **Built-in defaults** - `ClientPropertiesMerger.builtInDefaults()`, the only place the starter's
   own opinions are written down.
2. **The `defaults` block** - what this deployment wants for every client.
3. **The client's own block** - what this dependency needs.
4. **Customizer beans** - what the properties cannot express.
5. **Per-request headers** - `X-Ludwig-Retry`, `X-Ludwig-Max-Attempts` (see below).

Levels 1-3 are an explicit deep merge, not relaxed binding. The rules are not uniform, and the
non-uniformity is deliberate:

| Kind | Rule | Why |
|---|---|---|
| Scalars | Higher layer wins when non-null | Every scalar is a wrapper type, so "not mentioned" is expressible |
| Nested blocks | Merged field by field | Setting `retry.max-attempts` keeps the inherited backoff |
| Lists | **Replaced** | `retry-on-status: [503]` means 503 and nothing else |
| `additional-*` lists | **Concatenated** | "the defaults plus mine" needs its own key so both intentions exist |
| A declared `bulkhead` / `rate-limiter` block | **Enables the policy** | Writing `max-concurrent-calls: 20` and getting no bulkhead is a silent no-op; `enabled: false` still wins where it is stated |
| Maps (`default-headers`) | Merged per key | "the platform's headers plus mine" is the only thing anyone means |
| `base-url` | **Never inherited** | A shared base URL is wrong for every client but one |
| `auth.relay-enabled` | **Never inherited** | Relaying a user's credential is a per-dependency decision |

## Per-request overrides

Two headers, consumed by the pipeline and never sent to the peer:

| Header | Effect |
|---|---|
| `X-Ludwig-Retry: true` | Retry this request even though its method is not idempotent. The caller is asserting it has made the request safe to repeat - an idempotency key, a unique constraint. |
| `X-Ludwig-Retry: false` | Suppress a retry that would otherwise happen. |
| `X-Ludwig-Max-Attempts: 2` | Lower the attempt limit for this request. It can only lower it: a call site must not be able to override a capacity decision taken for the deployment. |

## Decorator order, and why retry sits inside the breaker

```
RateLimiter → Bulkhead → CircuitBreaker → Retry → TimeLimiter → [authenticate → send]
```

Identical for `sync` and `async`, and not configurable. Every other order has a concrete failure:

- **Retry inside the breaker.** The alternative - the breaker inside the retry - means every
  attempt takes a permit and records a result, so a logical call with three attempts spends three of
  the breaker's window on one caller's experience. Worse, it does not stop the amplification: retry
  multiplies load on a dependency precisely when it can least afford it, and the only thing that can
  stop that is a breaker *above* the retry, which refuses the call before any attempt is made. Once
  the breaker is open, the three attempts become zero.
- **Bulkhead outside the retry.** Inside it, one logical call would acquire and release a permit per
  attempt, so the concurrency cap would stop bounding the number of calls in flight - which is the
  only thing it is for.
- **RateLimiter outermost.** Anywhere else it spends permits on attempts, and on calls the breaker
  was going to refuse anyway, so the rate the partner sees is some multiple of the configured one.
- **TimeLimiter innermost.** It bounds one attempt. Around the retry it would bound the whole call,
  which is what `retry.max-elapsed-time` already does, and the first attempt's cancellation would
  look like the call's deadline.

The breaker takes one permit and records one result per **logical** call, so its window counts what
the caller experienced and its slow-call detection measures the time the caller actually waited.
Authentication is inside the retry, which is what makes `401 → refresh → retry` possible at all.

### What counts as a failure

The breaker records 408, 429, 500, 502, 503 and 504 by default. It deliberately does **not** record
400, 401, 403, 404, 409 or 422: a client error means this service sent something the peer rejected,
so the peer is healthy, and opening the breaker on it takes a working dependency out of service
because of a bug in the caller. That is the single most common way a circuit breaker makes an
incident worse.

An authentication failure and a refused call are likewise not the dependency's failure and are not
recorded.

### A note on Resilience4j

The circuit breaker, bulkhead, rate limiter and time limiter are Resilience4j's own objects, created
in the shared, Micrometer-bound registries and named after the client - so
`resilience4j.circuitbreaker.state{name="billing"}` sits next to
`ludwig.restclient.requests{client="billing"}`.

**Retry is this module's own**, built on Resilience4j's `IntervalFunction` for the jittered
exponential backoff. Its `Retry` decorator cannot express the three things this policy needs at
once: a wait taken from a `Retry-After` header, a wall-clock budget for the whole call, and a
reactive path that shares its decisions with the blocking one rather than reimplementing them. The
visible consequence is that `resilience4j.retry.*` meters are not published for these clients;
`ludwig.restclient.retries{client,reason}` is, identically in both modes.

## Authentication

`auth.type` selects a strategy. Seven ship; an eighth is a bean.

| Type | Keys | Notes |
|---|---|---|
| `none` | - | The default |
| `basic` | `username`, `password` | Refused over plain HTTP to a non-loopback host |
| `bearer` | `token` **or** `token-supplier`, `scheme` | Exactly one of the two; both set is a startup error |
| `api-key` | `key`, `header-name` **or** `query-param-name`, `value-prefix` | The query form warns: a credential in a URI reaches every proxy's access log |
| `oauth2-client-credentials` | `registration-id`, `refresh-skew`, `scopes` | Cached, refreshed early, single-flight, re-minted once on a 401 |
| `oauth2-token-relay` | `relay-enabled`, `fail-when-no-token` | See below |
| `custom` | `authenticator` | Delegates to a named `ClientAuthenticator` bean |

### Token caching

The `oauth2-client-credentials` token is cached and refreshed `refresh-skew` (30s) before its stated
expiry - without the skew, a token that expires in 200ms is used for a call that takes 300ms, and
the failure looks like an intermittent 401 with no pattern.

Refresh is **single-flight**. The naive cache behaves fine under test and badly in production: at the
instant a token expires, every in-flight request discovers it is stale simultaneously, and a service
handling 200 concurrent calls sends 200 token requests to the authorization server at the same
millisecond, once per token lifetime. Here the first caller mints and the rest attach to that one
result.

A 401 discards the cached token and grants **one** extra attempt, outside the retry budget - an
expired-early token is not a transport failure and should not spend a budget meant for one. A second
401 is a real authorization failure and is reported as the 401 it is.

### Token relay is the dangerous one

`oauth2-token-relay` hands this service's callers' credentials to a third party. Sometimes that is
exactly right - a gateway calling a resource server on the user's behalf - and sometimes it is a
partner holding a usable user token it was never meant to see. Three guards, none of them
configurable away:

1. `auth.relay-enabled: true` must be set **on the client**. It is the one key the merger refuses to
   inherit from `defaults`, so nine clients cannot acquire it by accident.
2. With no inbound token the call **fails** rather than going out anonymously. Calling anonymously
   turns "this user was not authenticated" into "the call succeeded with whatever the partner grants
   anonymous callers" - a privilege change that produces no error anywhere.
3. A relay client in a service that is not configured as a resource server is refused at startup,
   because it could only ever fail closed.

### Recipe: adding a new authentication method

Publish one bean. Nothing in this starter changes.

```java
/** HMAC request signing, as one partner insists on. */
public class HawkAuthenticationProvider implements ClientAuthenticationProvider {

    @Override public String type() { return "hawk"; }

    @Override public Class<? extends ClientAuthProperties> propertiesType() {
        return HawkProperties.class;               // a mutable JavaBean of your own
    }

    @Override public ClientAuthenticator create(String clientName, ClientAuthProperties properties) {
        HawkProperties hawk = (HawkProperties) properties;
        Mac mac = Mac.getInstance("HmacSHA256");   // expensive work belongs here, not per request
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

```java
@Bean
ClientAuthenticationProvider hawkAuthProvider() { return new HawkAuthenticationProvider(); }
```

```yaml
ludwig:
  rest-client:
    clients:
      partner:
        base-url: https://partner.example.com
        auth:
          type: hawk
          id: ${HAWK_ID}
          key: ${HAWK_KEY}
```

The `auth` block is bound to `HawkProperties` twice onto one instance - first from
`ludwig.rest-client.defaults.auth`, then from the client's own block - so a custom type inherits from
the defaults block exactly like a built-in one.

Publishing a provider whose `type()` is one of the built-in names **replaces** that built-in, which
is how a service changes what "bearer" means without working around this module.

Where a whole provider is too much - one client, one authenticator, no new properties - use
`type: custom` with `auth.authenticator: mySigner` and publish a `ClientAuthenticator` bean.

## Observability

### Metrics

| Meter | Tags | What it tells you |
|---|---|---|
| `ludwig.restclient.requests` (timer) | `client`, `method`, `uri`, `status`, `outcome`, `exception` | RED metrics per dependency. `uri` is the **template**, capped at `metrics.max-uri-tags` |
| `ludwig.restclient.retries` | `client`, `reason` | Attempts beyond the first. Divided by the request count it is the retry rate - the earliest sign of degradation, because the retries are still succeeding |
| `ludwig.restclient.circuitbreaker.transitions` | `client`, `from`, `to` | Counted as well as gauged, because a gauge scraped every 15s misses a breaker that opened and closed in between |
| `ludwig.restclient.auth.token.refreshes` | `client`, `type` | Tokens actually minted. Far above one per token lifetime means the cache is not working |
| `ludwig.restclient.calls.notpermitted` | `client`, `policy` | Calls this service refused to make. Load shed, not errors |
| `ludwig.restclient.listener.failures` | `client`, `listener`, `callback` | A listener threw and was swallowed |
| `ludwig.restclient.audit.failures` | `client` | An audit sink threw and was swallowed |
| `ludwig.restclient.pool.{leased,pending,available,max}` | `client` | Apache transport only. `pending` is the one that matters: zero when healthy, rising seconds before latency does |

Resilience4j's own meters - `resilience4j.circuitbreaker.state`, `resilience4j.bulkhead.available.concurrent.calls`, `resilience4j.ratelimiter.available.permissions` and the rest - are bound to the application's `MeterRegistry` by this module, tagged with the client name. A breaker that opens with no meter behind it is a state change nobody can alert on.

The timer comes from Spring's own client observation, retagged - so the metric and the client span
describe one exchange and carry the same URI template. URI cardinality is capped by
observability-spring-boot-starter's `UriCardinalityLimitingMeterFilter`, the same implementation
that protects `http.server.requests`.

No credential, token, header value or expanded URI appears in any tag.

### Tracing and correlation

W3C trace context is propagated by Spring's own client instrumentation. The platform correlation id
is propagated by observability-spring-boot-starter's own
`CorrelationPropagatingRequestInterceptor` - this module wires it, it does not reimplement it. For
reactive clients, where that module has no equivalent, a small filter sends the same id from the
same source under the same header name.

### Structured logging

Per client, on the logger `ludwig.restclient.<client>`, so one dependency can be raised to DEBUG
during an incident without the other nine following - and with observability-spring-boot-starter
present, without a restart.

| `logging.level` | Written |
|---|---|
| `none` | Nothing. Metrics and traces still record the call |
| `basic` (default) | Method, templated URI, status, duration, attempt |
| `headers` | The above plus headers, redacted |
| `body` | The above plus bodies, redacted and size-capped |

Two independent gates, deliberately: `logging.level` decides *what* is written and is a data
decision; the SLF4J level decides *whether* and is an operational one. Collapsing them would mean
that turning on debugging for a client also turns on body logging for it.

Completed calls log at INFO, failed ones at WARN.

**Bodies are never streamed into the log.** A body is materialized only when the response failed or
the client asked for `body` level, **and** the declared `Content-Length` is under 256 KiB. A response
with no `Content-Length` - what a streaming response looks like - is never buffered. The consequence
is stated rather than hidden: a client streaming large responses gets no body in its logs.

Redaction defaults: headers `Authorization`, `Proxy-Authorization`, `Cookie`, `Set-Cookie`,
`X-Api-Key`, `X-Auth-Token`, `Api-Key`; JSON fields `password`, `secret`, `token`, `access_token`,
`refresh_token`, `id_token`, `client_secret`, `pin`, `otp`, `card_number`, `cvv`. Field redaction
walks the parsed document, so `"name":"password"` survives and `"password":"hunter2"` does not - a
regular expression gets that backwards. Use `additional-redacted-headers` / `additional-redacted-fields`
to add to the defaults; setting `redacted-headers` replaces them.

The masking itself is now `audit-core`'s `Redactor`, shared with the whole platform: the structural JSON
walk, the wholesale replacement of a form-encoded body and the truncation marker moved there unchanged,
along with the reasoning and the tests that proved them. What stayed here is `ClientRedactor`, the
per-client binding - *which* names this client masks, which is per-partner configuration and belongs next
to the client.

A per-client list can only **widen** what is masked, never narrow it: it is composed with the
deployment-wide `ludwig.audit.redaction` rules rather than replacing them, so a body field called
`client_secret` is masked whether or not this client's list happens to name it. Narrowing is the operation
that leaks, and a module must not be able to undo a platform rule.

### Recipe: adding a listener

```java
/** Records a partner's remaining quota, so the dashboard shows it before we are throttled. */
@Component
public class QuotaListener implements RestClientListener {

    private final AtomicInteger remaining = new AtomicInteger(-1);

    @Override public boolean supports(String clientName) { return "partner".equals(clientName); }

    @Override public void onResponse(OutboundRequest request, OutboundResponse response) {
        List<String> header = response.headers().get("X-RateLimit-Remaining");
        if (header != null && !header.isEmpty()) {
            remaining.set(Integer.parseInt(header.get(0)));
        }
    }

    @Override public void onRetry(OutboundRequest request, int nextAttempt, long waitMillis, String cause) {
        log.info("retrying {} {} (attempt {}, waiting {}ms): {}",
                request.method(), request.uriTemplate(), nextAttempt, waitMillis, cause);
    }

    @Override public int getOrder() { return 0; }
}
```

A listener that throws **never breaks the call**: the exception is logged once per listener class and
counted as `ludwig.restclient.listener.failures`. Letting a dashboard integration fail a payment is
not a trade this platform makes - and the consequence is that a listener must not be used to enforce
anything. Callbacks run on the calling thread (`sync`) or a Reactor thread (`async`), so a listener
that blocks makes every call slower; hand the work to an executor.

### Audit

Opt-in per client. Distinct from logging and not a louder version of it: an audit record is retained,
is read by people who are not operators, and must never contain a credential or a payload.

Whether a partner's calls are audited at all, at what sampling rate, and which of its headers are
allow-listed stay here, per named client, because those are facts about the relationship with that partner.
What moved to `ludwig.audit` is the destination, the failure policy and the retention.

```yaml
ludwig:
  rest-client:
    clients:
      billing:
        audit:
          enabled: true
          sampling-probability: 0.1
          include-response-headers: [X-Request-Id]
          sink: mySiemAuditSink        # optional; a bean name, per client
```

A record carries: client, method, **templated** URI, status, outcome, duration, attempts, correlation
id, trace id, principal, timestamp, allow-listed headers, failure type. No expanded URI, no bodies,
and only headers that were named - an allow-list, because a deny-list is only as good as the last
person who remembered to extend it.

Sampling applies to **successful calls only**. Failures, refused calls and authentication errors are
always recorded: the rare events are what an audit exists for.

Records go to the platform's single `AuditSink` with `category=outbound-call` - a log, the append-only
`audit_event` table, a SIEM through the transactional outbox, or several at once. `NOT_PERMITTED` is
recorded as outcome `DENIED` rather than as a failure: the call was refused by this service's own policy
and nothing broke, which is the distinction an incident responder needs first.

**`AuditEventEmitter` and `LoggingAuditEventEmitter` are gone, and `audit.emitter` is now `audit.sink`.**
They were two of the nine audit mechanisms this platform had collected; see
[`audit-core`](../audit-core). The property is renamed rather than aliased on purpose: an alias would let a
property naming a bean of a type no longer on the classpath look like it was honoured. `audit.sink` stays
**per client**, because one service can legitimately have to send one partner's trail to a vendor's API and
the rest to the platform's sink.

`OutboundCallAudit` stays as the authoring surface and gained a `toAuditEvent()`; everything it said about
what an outbound-call record may contain still holds and is still where it was.

"An audit sink must not be able to fail a payment" is still true for this category and is still enforced -
by `FailurePolicyAuditSink`, which resolves `ludwig.audit.failure.by-category` and logs-and-continues for
`outbound-call`. What is gone is the `catch` in `AuditRecorder`: keeping one there as well would override
whatever a deployment configured with a decision hard-coded in a library, which is precisely what nine
separate mechanisms did. The counter that made a silently-dropping sink visible is now platform-wide and
tagged by category - `ludwig.audit.sink.failures{category="outbound-call"}` - rather than
`RestClientMeters.auditFailure`, which remains as published API but is no longer incremented here.

**What a deployment notices:** the `ludwig.restclient.audit` logger no longer exists; records are on
`ru.ludwigandreas.audit` with `category=outbound-call`.

## Errors

| Exception | Meaning | Who fixes it |
|---|---|---|
| `RestClientResponseException` | The peer answered with a failure status | Depends on the status |
| `RestClientTimeoutException` | A deadline passed. The request **may** have been processed | Us or them |
| `RestClientConnectionException` | The request never reached them | The network |
| `RestClientAuthenticationException` | We could not obtain or use a credential | Us |
| `RestClientCallNotPermittedException` | Our own policy refused to make the call | Us - shed load |

Every one carries the client name and the correlation id, in the message as well as in fields,
because the message is what a log appender writes and what an alert quotes.

`RestClientResponseException` carries the status, the redacted headers, a truncated body snippet and
- for `application/problem+json` - a parsed `ProblemDetail` with its extension members intact.

A partner whose error envelope is not RFC 9457 is handled by publishing a translator:

```java
@Component
public class PartnerErrorTranslator implements ResponseErrorTranslator {

    @Override public boolean supports(String clientName) { return "partner".equals(clientName); }

    @Override public RuntimeException translate(ErrorContext context) {
        if (context.statusCode() != 409 || context.body() == null) {
            return null;                                   // not mine - defer to the next translator
        }
        return new DuplicateOrderException(readOrderId(context.body()));
    }
}
```

Translators run in `@Order` order and the first non-null answer wins; the built-in one runs last, so
a service's translator always gets first refusal.

With `web-core-spring-boot-starter` present, an exception that escapes a controller is rendered as
the platform's own problem document: a 5xx, timeout, connection failure or refused call becomes
`upstream-unavailable` (503); a 429 becomes a 429; a **4xx becomes a 500**, because a 422 from
billing means this service sent billing something invalid and passing it through would blame the
caller for a defect it did not cause.

Error handling applies to `retrieve()` and deliberately not to `exchange()`: a caller that asked for
the raw exchange wants the raw response, including a 404 it intends to treat as an empty result.

## Fallbacks

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

A fallback runs only for a failure that means *the call could not be made or completed*. It does
**not** run for a 4xx - the peer answered, and substituting cached data for a 403 is how an
authorization defect ships - nor for an authentication failure, which would make a broken deployment
look healthy for as long as the stale data lasts. Fallbacks apply to declarative interfaces; an
ad-hoc `RestClient` call gets the exception.

## Transports

| `transport` | Pool | HTTP/2 | Notes |
|---|---|---|---|
| `http-client` (default) | Not configurable | Yes | The JDK's own. No pool knobs exist; the `pool` block is reported as ignored rather than looking effective |
| `apache` | Fully configurable | No | Choose it when the pool matters. The classic client is HTTP/1.1 only |
| `reactor-netty` | Configurable | Yes | Implied by `mode: async`; never selectable for `sync` |

Pools are never shared between named clients - two clients pointing at the same host still get two
pools, because the point of separating them is that a saturated dependency must not consume the
permits another needs.

`compression: true` means the same thing on all three: Apache and Reactor Netty decode transparently,
and for the JDK engine the starter advertises and decodes gzip/deflate itself.

### TLS

Per client: truststore, keystore (mTLS), protocols (TLSv1.3 and TLSv1.2 by default), cipher suites,
hostname verification. A process-wide `javax.net.ssl.trustStore` cannot express two clients that
trust different internal CAs, which is the normal case with more than one partner.

`tls.trust-all: true` and `tls.hostname-verification: false` need **two** switches and a profile
check:

```yaml
ludwig:
  rest-client:
    allow-trust-all: true                  # top level, separate from the client
    production-profiles: [prod, production]
    clients:
      sandbox:
        tls: { trust-all: true }
```

and the context still refuses to start if any of `production-profiles` is active. One switch is a
switch somebody flips during an incident and never flips back; a second, differently-named,
top-level one means a development YAML cannot be copied into a deployment and keep working.

## Configuration reference

Everything below may be set under `defaults` or under any client. Durations accept `200ms`, `2s`,
`5m`.

### Top level

| Key | Default | |
|---|---|---|
| `ludwig.rest-client.enabled` | `true` | `false` registers nothing at all |
| `ludwig.rest-client.allow-trust-all` | `false` | The second switch TLS relaxations need |
| `ludwig.rest-client.production-profiles` | `prod,production` | Profiles where TLS relaxations are refused outright |
| `ludwig.rest-client.metrics.enabled` | `true` | |
| `ludwig.rest-client.metrics.max-uri-tags` | `100` | Overflow folds into `OTHER` |
| `ludwig.rest-client.metrics.pool` | `true` | Pool gauges where the engine reports them |

### Per client

| Key | Default | Restart? | |
|---|---|---|---|
| `base-url` | - | yes | Required; never inherited |
| `mode` | `sync` | yes | `sync` or `async` |
| `transport` | `http-client` | yes | `http-client`, `apache`, `reactor-netty` |
| `connect-timeout` | `2s` | yes | |
| `read-timeout` | `10s` | yes | Longest gap between bytes, not a deadline |
| `connection-request-timeout` | `2s` | yes | Wait for a pooled connection |
| `request-timeout` | - | yes | The deadline for one attempt |
| `redirects` | `NORMAL` | yes | `NORMAL`, `NEVER`, `ALWAYS` |
| `compression` | `true` | yes | |
| `http2` | `false` | yes | |
| `user-agent` | `<app> (ludwig-rest-client; client=<name>)` | yes | |
| `default-headers.*` | - | yes | Merged per key |
| `default-query-params.*` | - | yes | A call-site value wins |
| `pool.max-total` | `50` | yes | `apache`/`reactor-netty` only |
| `pool.max-per-route` | `20` | yes | Equal it to `max-total` for a single-host client |
| `pool.idle-eviction` | `30s` | yes | Shorter than the shortest idle timeout on the path |
| `pool.time-to-live` | `5m` | yes | |
| `pool.validate-after-inactivity` | `2s` | yes | The defence against a half-open connection |
| `pool.keep-alive` | `1m` | yes | Used only when the server sends no header |
| `tls.*` | - | yes | See above |
| `proxy.host` / `.port` / `.non-proxy-hosts` / `.username` / `.password` | - | yes | Per client, not per JVM |
| `auth.*` | `type: none` | yes | See above |
| `resilience.enabled` | `true` | yes | |
| `resilience.retry.enabled` | `true` | **no** | |
| `resilience.retry.max-attempts` | `3` | **no** | |
| `resilience.retry.wait-duration` | `200ms` | **no** | |
| `resilience.retry.exponential-backoff-multiplier` | `2.0` | **no** | |
| `resilience.retry.randomized-wait-factor` | `0.5` | **no** | Without jitter, everyone retries at the same instant |
| `resilience.retry.max-elapsed-time` | derived | **no** | Wall-clock budget; Resilience4j has none. The default is every attempt at its full timeout plus the jittered waits - a fixed number would make a three-attempt policy on a 120s call a lie |
| `resilience.retry.retry-on-status` | `408,425,429,500,502,503,504` | **no** | Replaced, not extended |
| `resilience.retry.retry-on-exception` | - | yes | Class names, resolved at startup |
| `resilience.retry.idempotent-methods-only` | `true` | **no** | |
| `resilience.retry.respect-retry-after` | `true` | **no** | |
| `resilience.retry.max-retry-after` | `30s` | **no** | Caps a hostile or broken header |
| `resilience.circuit-breaker.*` | see above | yes | |
| `resilience.bulkhead.enabled` | `false`, or `true` if the block is configured | yes | |
| `resilience.bulkhead.type` | `SEMAPHORE` | yes | `THREAD_POOL` is refused for `async` |
| `resilience.bulkhead.max-concurrent-calls` | `25` | yes | |
| `resilience.bulkhead.max-wait-duration` | `0` | yes | Must be 0 for `async` |
| `resilience.rate-limiter.enabled` | `false`, or `true` if the block is configured | yes | Per process, so revisit it when scaling |
| `resilience.rate-limiter.limit-for-period` | `100` | **no** | |
| `resilience.rate-limiter.limit-refresh-period` | `1s` | **no** | |
| `resilience.rate-limiter.timeout-duration` | `0` | **no** | Must be below the read timeout |
| `resilience.time-limiter.*` | disabled | yes | `async` only; refused for `sync` |
| `resilience.fallback.handler` / `.methods` | - | yes | Bean names |
| `logging.level` | `BASIC` | **no** | |
| `logging.max-body-size` | `2048` | **no** | |
| `logging.redacted-headers` / `.redacted-fields` | see above | **no** | Replaced |
| `logging.additional-redacted-headers` / `-fields` | - | **no** | Concatenated |
| `logging.log-request-before-call` | `false` | **no** | Earns its noise once: when a call hangs |
| `audit.enabled` | `false` | yes | |
| `audit.sampling-probability` | `1.0` | **no** | Successes only |
| `audit.emitter` | - | yes | Bean name |
| `audit.include-request-headers` / `-response-headers` | - | yes | Allow-list |
| `serialization.*` | application's mapper | yes | Forked only when something is set |

### Restart vs. refresh

Keys marked **no** are genuinely refreshable. Once per second per client, the pipeline re-reads the
bound `@ConfigurationProperties` and rebuilds what is pure function of them - the retry decision, the
exchange logger, the redaction lists, the audit recorder - and reconfigures the live rate limiter
through Resilience4j's own `changeLimitForPeriod` / `changeTimeoutDuration`. So with
`hot-reload-spring-boot-starter`, an actuator refresh or a Vault lease renewal in place, raising a
log level or dropping a retry count during an incident is a configuration change rather than a
rollout.

A call already in flight keeps the policy it started with: a logical call should not change the rules
halfway through deciding whether to repeat itself. And a configuration that has become invalid -
a `retry-on-exception` class that no longer exists - leaves the previous one in effect and logs a
warning, rather than taking the client down.

Two keys inside an otherwise refreshable block still need a restart:
`rate-limiter.limit-refresh-period`, because changing it means discarding the cycle in progress, and
`retry-on-exception`, because the class list is resolved once.

Everything else requires a restart, and the reason is always the same - it is baked into an object
built once: a connection pool, an `SSLContext`, a base URL, an authenticator, a breaker's window.
Changing those values under a running client would leave half the calls on the old object.

## Failure modes, and what each looks like

| What happened | Exception | Log | Metrics |
|---|---|---|---|
| Dependency returns 503, retries succeed | none | WARN per failed attempt on `ludwig.restclient.<client>` | `requests{status=200}`, `retries{reason="status:503"}` |
| Dependency returns 503, budget spent | `RestClientResponseException` | WARN per attempt | `requests{status=503,outcome=SERVER_ERROR}`, `retries` |
| Breaker opens | `RestClientCallNotPermittedException` | nothing - no call was made | `calls.notpermitted{policy="circuit-breaker"}`, `circuitbreaker.transitions{to="OPEN"}` |
| Bulkhead full | `RestClientCallNotPermittedException` | nothing | `calls.notpermitted{policy="bulkhead"}` |
| Rate limit exhausted | `RestClientCallNotPermittedException` | nothing | `calls.notpermitted{policy="rate-limiter"}` |
| Read timeout | `RestClientTimeoutException` | WARN with the duration | `requests{outcome=UNKNOWN,exception=...}` |
| Connection refused | `RestClientConnectionException` | WARN | as above |
| Token endpoint refuses | `RestClientAuthenticationException` | WARN, never with the credential | `auth.token.refreshes` stops rising |
| Inbound token missing on a relay client | `RestClientAuthenticationException` | WARN | - |
| A listener throws | none | WARN once per listener class | `listener.failures` |
| An audit sink throws | none | WARN | `audit.failures` |

## Testing your own clients

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

The slice boots this starter's auto-configurations and nothing else - no data source, no web server,
no other dependency - and discovers your interfaces the same way production does.

## Startup validation

Bean Validation checks one field at a time, which catches a negative pool size and misses every
interesting mistake. These are relationships, and each produces behaviour that is intermittent and
nearly impossible to attribute weeks later, so the pod fails instead:

- a client with no `base-url`, or a `base-url` in `defaults`
- credentials over plain HTTP to a non-loopback host
- a retry budget that cannot fit two attempts, so `max-attempts` is a lie
- `request-timeout` no longer than `connect-timeout`
- a time limiter on a `sync` client; a thread-pool bulkhead or a bulkhead wait on an `async` one
- `trust-all` without the second switch, or with a production profile active
- a keystore with no password
- an unknown `auth.type`; `bearer` with both a token and a supplier
- token relay without `relay-enabled`, or in a service that is not a resource server
- a breaker whose `minimum-number-of-calls` exceeds its window, so it can never open
- a rate-limiter wait longer than the read timeout
- an engine, an exception class or a Jackson module that is not on the classpath

Warnings - logged, not fatal - cover a pool block on the JDK transport, `http2` on Apache, an API key
in a query parameter, 4xx statuses recorded as breaker failures, and retrying non-idempotent methods
by default.

All problems are reported together. A deployment with three mistakes should take one rollout to find
them, not three.

## What it deliberately does not do

- **No service discovery or load balancing.** `base-url` is a URL. In this estate that is a Kubernetes
  service name or a gateway, both of which already balance; adding a second balancer inside the
  client would make traffic distribution a function of two systems.
- **No response caching.** Caching is a domain decision about staleness, not a transport one.
- **No request signing beyond the shipped schemes.** That is what the authenticator SPI is for.
- **No retry on a streaming request body.** A `sync` request body is buffered so a retry can send it
  again; a client uploading large files should disable retries or use the transport directly.
- **No cluster-wide rate limiting.** The limiter is per process. The number to configure is the
  partner's quota divided by the replica count, and it has to be revisited when the deployment is
  scaled.
- **No `@Retryable`-style annotations.** Resilience is a property of the dependency, not of a method,
  and a call site must not be able to disagree with the deployment about how a dependency is reached.

## Integration with the other modules

| Module | What it adds when present |
|---|---|
| `observability-spring-boot-starter` | Correlation id propagation (its own interceptor), trace ids on audit records, the URI-cardinality meter filter, runtime log-level changes per client |
| `web-core-spring-boot-starter` | A dependency failure that escapes a controller renders as the platform's RFC 9457 problem document |
| `security-spring-boot-starter` | The audited principal; resource-server configuration for token relay |
| `hot-reload-spring-boot-starter` | The refreshable keys above follow a watched file or Vault secret |

All of them are optional. Without any of them the clients work; audit records simply carry `null`
where a correlation id or a principal would be.

## Package layout

```
ru.ludwigandreas.restclient
├── annotation      @LudwigRestClient, @EnableLudwigRestClients
├── spi             every extension point - depends on nothing else in this module
├── config          @ConfigurationProperties, the deep merge, the validator, auto-configuration
├── core            the registry, the runtime, and both execution pipelines
├── auth            the authenticator factory, the token cache, the shipped providers
├── resilience      the policies, the retry decision, the failure classifier
├── transport       JDK / Apache / Reactor Netty, TLS, proxy, pool gauges
├── observability   metrics, listeners, redaction, exchange logging, audit
├── error           the exception hierarchy and the translator chain
├── registrar       interface scanning and proxy registration
└── test            @LudwigRestClientTest
```

`spi` depending on nothing else in the module is enforced by an ArchUnit rule, because an SPI that
reaches into the implementation cannot be implemented from outside without dragging the
implementation along - and the person who adds that import will be adding a perfectly reasonable
convenience method.
