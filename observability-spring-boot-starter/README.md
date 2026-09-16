# observability-spring-boot-starter

***English** · [Русский](README.ru.md)*

The observability foundation every service in this repository sits on: distributed tracing exported
over OTLP, one correlation id propagated across HTTP and Kafka and into every log line, structured
JSON logs carrying trace, span and correlation ids, RED metrics on every HTTP endpoint with bounded
tag cardinality, liveness and readiness split apart, and log levels changeable without a restart.

Add the dependency and a service is wired the same way as every other service in the estate - no
`@Bean` for a sampler, no `logback-spring.xml`, no filter that copies a header into the MDC, no
`MeterFilter` guarding against a cardinality explosion nobody remembered to guard against.

## Quick start

```xml
<dependency>
    <groupId>ru.ludwigandreas</groupId>
    <artifactId>observability-spring-boot-starter</artifactId>
    <version>1.0.0</version>
</dependency>
```

Point it at a collector. That is the whole configuration:

```yaml
spring:
  application:
    name: product-catalog

management:
  otlp:
    tracing:
      endpoint: http://otel-collector:4318/v1/traces
  tracing:
    sampling:
      probability: 0.1
```

A request now produces this log line - one JSON object, already in fields:

```json
{"@timestamp":"2026-09-16T09:12:13.481Z","log.level":"INFO","log.logger":"com.example.OrderService",
 "process.thread.name":"http-nio-8080-exec-3","message":"order 4711 accepted",
 "trace.id":"0af7651916cd43dd8448eb211c80319c","span.id":"b7ad6b7169203331",
 "correlation.id":"0af7651916cd43dd8448eb211c80319c","service.name":"product-catalog",
 "service.version":"1.4.2","service.environment":"prod","service.node.name":"catalog-7d9f-xk2",
 "labels":{"tenant":"acme"}}
```

this response:

```console
$ curl -i -H 'X-Correlation-Id: order-4711' localhost:8080/api/v1/orders/4711
HTTP/1.1 200
X-Correlation-Id: order-4711
X-Trace-Id: 0af7651916cd43dd8448eb211c80319c
```

and these metrics, tagged by identity and bounded by construction:

```
http_server_requests_seconds_bucket{service="product-catalog",environment="prod",version="1.4.2",
  uri="/api/v1/orders/{id}",method="GET",status="200",outcome="SUCCESS",le="0.1"} 412
```

## The problem this starter exists to remove

Every one of the six things below is something each service was re-deriving, and the re-derivations
did not agree with each other. That is the expensive part: not the work, but the fact that the
estate ends up with four spellings of the correlation header, three log shapes, two names for the
same service, and a sampling rate nobody can change without a rollout.

**Nothing linked the three pillars.** Per-module Micrometer metrics existed, and that was all. A slow
endpoint on a dashboard led to no trace, and a trace led to no logs, because no id was shared between
them. This module makes the trace id a log field, the correlation id a span tag, and the service
identity the same string in all three.

**Every service invented its own correlation.** Usually a filter copying `X-Request-Id` into the MDC,
usually without validating it, usually forgetting to restore the MDC afterwards, and always stopping
at the first outbound call. The id never crossed Kafka at all.

**Logs were text.** A pattern layout has to be parsed back apart by a regular expression that lives
somewhere else and breaks the first time a message contains a newline - which is to say, the first
time anything logs a stack trace.

**Liveness and readiness were the same endpoint.** A service that had lost its database - unready, but
perfectly alive - failed its liveness probe too, and Kubernetes restarted it in a loop that could not
possibly fix the database.

**Nothing bounded metric cardinality.** `http.server.requests` is tagged by URI template and therefore
safe, right up until a request fails to match a handler and contributes its raw path. A vulnerability
scanner then drives the series count until the registry exhausts the heap.

**Changing a log level meant a restart.** Which destroys the process holding the symptom you were
trying to diagnose.

## What you get, out of the box

| Area | What is configured | Why it is not the framework default |
|------|--------------------|-------------------------------------|
| Tracing | OTLP exporter, W3C propagation, parent-based ratio sampling | Boot ships the machinery; the estate-wide choices are left to you |
| Sampling override | `X-Ludwig-Force-Trace` forces one request to be traced | Not possible at all without a custom `Sampler` |
| Span hygiene | `/actuator/**` server spans dropped at export | Probes otherwise outnumber real traffic in the backend |
| Correlation | One id across HTTP in, HTTP out, Kafka in, Kafka out, and the MDC | Entirely absent from Spring Boot |
| Logs | JSON, ECS/OTel/flat field sets, masking, truncation | Boot 3.3 has no structured logging |
| Metrics | Identity tags, URI cardinality cap, SLO histogram, probe paths excluded | Boot removed `max-uri-tags`; the rest was never there |
| Annotations | `@Timed`, `@Counted`, `@Observed` actually work | Micrometer ships the aspects and registers none of them |
| Probes | Liveness and readiness split, graceful shutdown | Off by default in Boot |
| Log levels | `logging.level.*` applied from a watched file or Vault secret | Requires a restart, or a per-replica actuator call |

## Correlation id vs trace id

They are not the same thing and neither replaces the other.

The **trace id** identifies one distributed execution. It exists only while tracing is on and only if
the request was sampled - at a 10% probability, 90% of requests have no usable trace id at all.

The **correlation id** identifies a piece of *business* work. It is present on 100% of requests because
it is never sampled away, it survives a retry that gets its own fresh trace, and it can be supplied by
the caller to tie together work this service knows nothing about.

So: support quotes the correlation id, engineers open the trace id, and the two are deliberately made
equal when a request arrives without a correlation id - the trace id is adopted as one, so either
value can be pasted into either tool.

### How it travels

| Hop | Mechanism |
|-----|-----------|
| HTTP in | `X-Correlation-Id`, falling back to `X-Request-Id`, else the trace id, else generated |
| HTTP out | Added to every `RestTemplate` built from the auto-configured builder |
| Kafka out | Record header, added by wrapping the Spring-managed producer factory |
| Kafka in | Read from the record header into the MDC for the listener's duration |
| Logs | MDC key `correlationId`, published as its own JSON field |
| Traces | Span tag `correlation.id`, so a ticket's id finds the trace |
| Response | `X-Correlation-Id` and `X-Trace-Id` headers |

Inbound values are treated as untrusted input, because they are. A value that is too long or contains
anything outside `[A-Za-z0-9_.:-]` is **replaced** rather than repaired - a `\r\n` reaching a response
header is a response-splitting bug, and an unbounded value multiplies the log volume of one request by
whatever the caller chose to send.

## Structured logging

**JSON is on by default.** This is deliberate and slightly opinionated: a starter whose structured
logging must be switched on is one every service forgets to switch on. The cost lands on developers
reading a local console, so the service template's `local` profile should carry:

```yaml
ludwig:
  observability:
    logging:
      json:
        enabled: false
```

Pick the field set your aggregator already understands, and no ingest-time mapping is needed:

| `field-set` | Names | For |
|-------------|-------|-----|
| `ecs` (default) | `@timestamp`, `log.level`, `trace.id` | Elasticsearch, OpenSearch |
| `otel` | `Timestamp`, `SeverityText`, `TraceId` | OpenTelemetry collector |
| `flat` | `timestamp`, `level`, `trace_id` | Loki, and stacks with no schema |

The encoder is installed before the application logs anything - it runs immediately after Spring Boot
initializes the logging system, so the banner, the profile list, the Liquibase migrations and any
bootstrap failure are already JSON. Switching format later would leave a stream that is half text and
half JSON, which most shippers handle by dropping the half they were not configured for.

### MDC handling

MDC entries are nested under `labels` by default. Flattening them lets an application key called
`message` or `service.name` overwrite the structural field of that name - and in Elasticsearch, a key
whose value type differs from the mapped type poisons the index template for every service sharing
the index.

The MDC is an open map, so anything anyone ever puts there is logged on every subsequent line of that
thread. In a service that puts request-scoped data there, set an allow-list:

```yaml
ludwig:
  observability:
    logging:
      json:
        mdc-include-keys: [tenant, correlationId, traceId, spanId]
```

Values whose key contains `password`, `secret`, `token`, `credential`, `authorization` or `apikey` are
replaced with `***`. That is a backstop, not a security control - secrets should not reach the MDC at
all - but a log aggregator is the worst possible place to discover that one did, being the one system
the whole company can search.

### Structured arguments

SLF4J 2.x key-values become top-level fields, and are masked on the same rules:

```java
log.atInfo().addKeyValue("orderId", id).addKeyValue("amount", total).log("order accepted");
```

## RED metrics

`http.server.requests` is the meter; what this module adds is everything that makes it safe and
aggregatable.

**The cardinality cap is the most important setting here.** Beyond `max-uri-tags` distinct URIs,
further ones are recorded as `OTHER` rather than dropped, so the request count and error rate stay
correct while the tag stops growing:

```yaml
ludwig:
  observability:
    metrics:
      http:
        max-uri-tags: 100
```

**Histograms, not client-side percentiles.** Micrometer can compute a p99 inside the process; that
number is correct for one replica and meaningless once aggregated, because the average of four
replicas' p99 values is not the fleet p99 - and it is biased *low*, which hides incidents. Buckets are
exported instead and the percentile is computed at query time:

```yaml
ludwig:
  observability:
    metrics:
      http:
        slo: [50ms, 100ms, 200ms, 500ms, 1s, 2s, 5s, 10s]
```

Identity is applied as common tags rather than left to the scraper, because `job` and `instance` are
the scrape target's view: absent from anything pushed, lost when series are federated into a central
store, and carrying no version - so "did the p99 move because of the release?" cannot be asked.

## Tracing one specific request

At a 10% sampling probability, the odds are overwhelming that the request a user just complained about
was not sampled. The usual remedies are to raise the global probability - a fleet-wide cost increase
requiring a rollout - or to ask them to try again until they get lucky.

Instead:

```console
$ curl -H 'X-Ludwig-Force-Trace: true' https://catalog.internal/api/v1/orders/4711
```

**This is off by default and needs an explicit opt-in**, because anything that can set the header can
make a service trace 100% of its traffic into a per-span-billed backend:

```yaml
ludwig:
  observability:
    tracing:
      sampling:
        trusted-force-header: true   # only where the edge strips or authorizes the header
```

The override applies only where the trace *begins*. A request arriving with a caller's sampling
decision keeps it - overriding would record a child whose parent was never exported, producing a trace
that appears to start halfway down a call chain.

## Log levels without a restart

With [`hot-reload-spring-boot-starter`](../hot-reload-spring-boot-starter/README.md) on the classpath,
`logging.level.*` keys in a watched file or Vault secret are applied to the running logging system:

```yaml
ludwig:
  hotreload:
    files:
      - path: /etc/config/logging.yml
```

```yaml
# /etc/config/logging.yml - edited during an incident, no restart, no lost state
logging:
  level:
    com.example.payments: DEBUG
```

Removing the key **reverts the logger to its startup level**. Without that, the realistic sequence is
that someone raises a chatty package to DEBUG at three in the morning, deletes the line the next day,
and the service keeps logging at DEBUG - at full volume, into a billed pipeline - until its next
restart, which may be weeks away. The symptom is a log bill, and nothing points at the cause.

## Probes

Liveness and readiness are split, which is the difference between a correct rolling deploy and a crash
loop. A service that has lost its database is *unready* and perfectly *alive*; answering both probes
from one aggregate gets it killed and restarted in a loop that cannot fix the database.

```console
$ curl localhost:8080/actuator/health/liveness    # is the JVM wedged? restart me
$ curl localhost:8080/actuator/health/readiness   # can I serve? route to me
```

Graceful shutdown is enabled with a 30-second grace period, so in-flight requests finish instead of
being severed mid-response - otherwise a routine deploy shows up as a burst of client-side 502s,
indistinguishable from a real incident.

`management.endpoints.web.exposure.include` defaults to `health,info,metrics,prometheus,loggers`.
`env`, `configprops`, `heapdump` and `threaddump` are deliberately excluded: they disclose credentials
and memory contents, and a starter must not be the reason they became reachable.

## Configuration

Everything below is a *default*, published as the lowest-precedence property source. Any value set in
`application.yml`, as an environment variable or as a system property wins, without the service
needing to know this module exists.

| Property | Default | Notes |
|----------|---------|-------|
| `ludwig.observability.enabled` | `true` | Master switch |
| `ludwig.observability.service.name` | `${spring.application.name}` | Stamped on metrics, spans and logs alike |
| `ludwig.observability.service.version` | jar manifest | `Implementation-Version`, else `build.version` |
| `ludwig.observability.service.environment` | first active profile | A guess; set it explicitly in deployments |
| `ludwig.observability.service.instance` | `$HOSTNAME` | The pod name, under Kubernetes |
| `ludwig.observability.tracing.excluded-paths` | `/actuator/**` | Dropped at export, not at sampling |
| `ludwig.observability.tracing.sampling.trusted-force-header` | `false` | See above - opt in deliberately |
| `ludwig.observability.correlation.header-name` | `X-Correlation-Id` | |
| `ludwig.observability.correlation.additional-inbound-headers` | `[X-Request-Id]` | Tried in order |
| `ludwig.observability.correlation.max-length` | `128` | Startup failure if not positive |
| `ludwig.observability.correlation.allowed-pattern` | `[A-Za-z0-9_.:-]+` | Non-matching values are replaced |
| `ludwig.observability.logging.json.enabled` | `true` | Switch off in the `local` profile |
| `ludwig.observability.logging.json.field-set` | `ecs` | `ecs`, `otel` or `flat` |
| `ludwig.observability.logging.json.max-message-length` | `16384` | `0` disables truncation |
| `ludwig.observability.logging.levels.revert-on-removal` | `true` | |
| `ludwig.observability.metrics.http.max-uri-tags` | `100` | Startup failure if negative |
| `ludwig.observability.metrics.http.slo` | `50ms…10s` | The latencies you actually promise |

Spring Boot's own properties are used wherever one already exists - `management.tracing.sampling.probability`,
`management.otlp.tracing.endpoint`, `management.endpoints.*`. This module never introduces a second
name for a knob that already has one.

## What it deliberately does not do

- **It does not replace Spring Boot's tracing.** The exporter, the propagators, the Micrometer bridge
  and the span processor are all Boot's. This module configures that machinery for an estate; the
  pieces it adds are the ones Boot has no opinion on.
- **It does not instrument WebClient.** Reactive is not on this module's classpath. Trace context still
  propagates through Boot's own instrumentation; the correlation header does not, and needs an
  `ExchangeFilterFunction` reading `CorrelationContext`.
- **It does not intercept producers built outside the Spring-managed factory.** Code that constructs a
  `KafkaProducer` directly has no hook to attach to; it should read `CorrelationContext.currentId()`
  and set the header itself.
- **It does not export logs over OTLP.** Logs go to stdout as JSON for the node agent to collect,
  which is what every Kubernetes log pipeline already does.

## Integration with the other modules

- [`web-core-spring-boot-starter`](../web-core-spring-boot-starter/README.md) - supplies its
  `TraceIdProvider`, so the `traceId` on an RFC 9457 problem document is the id of the span that was in
  scope when the request failed, rather than whatever an MDC key happened to hold.
- [`hot-reload-spring-boot-starter`](../hot-reload-spring-boot-starter/README.md) - powers runtime log
  levels through its watcher SPI.
- Both are **optional** dependencies. The starter works without either.

## Package layout

```text
ru.ludwigandreas.observability
├── config/        autoconfiguration, properties, environment defaults
├── core/          ServiceIdentity and its derivation from the environment
├── correlation/   the correlation id, its storage and its validation
├── tracing/       sampler, span export filtering, trace context access
├── web/           servlet filters and outbound HTTP propagation
├── kafka/         producer and consumer correlation
├── logging/       JSON encoder, its installation, runtime log levels
└── metrics/       RED meter filters and the cardinality cap
```

## Build note

Unlike most modules here, the actuator, the tracing bridge, the OTLP exporter and the Prometheus
registry are **not** optional dependencies. A starter whose exporter must be assembled per service
puts the assembly back in every service's pom, which is the duplication this module exists to remove.
A deployment that exports differently excludes what it does not want - one documented exclusion in one
place, rather than five correct dependencies in fifty poms.

The integration points - Spring MVC, Spring Kafka, web-core, hot-reload - are all optional, so a batch
worker with no servlet container and no broker still gets metrics, logs and traces without being given
either.
