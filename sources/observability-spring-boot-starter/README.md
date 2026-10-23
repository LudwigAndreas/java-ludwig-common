# observability-spring-boot-starter

***English** · [Русский](README.ru.md)*

The observability baseline every service sits on: distributed tracing over OTLP with a per-request
sampling override, one correlation id across HTTP, Kafka and the MDC, structured JSON logs carrying trace,
span, correlation and build identity, RED metrics with bounded URI cardinality and aggregatable SLO
histograms, split liveness/readiness probes with graceful shutdown, `GET /server/info` for user
interfaces, and log levels changeable at runtime. Adding the dependency wires a service the same way as
every other service.

| | |
|---|---|
| Role | starter |
| Depends on (in-repo) | [`audit-core`](../audit-core) (for `Redaction.MASK`); optional: [`web-core`](../web-core-spring-boot-starter), [`hot-reload`](../hot-reload-spring-boot-starter) |
| Used by | `export`, `file-action`, `grpc-client`, `grpc-server`, `reconciliation`, `rest-client`, `user-settings` starters; both services |
| Surfaces | REST `GET /server/info`; actuator endpoints; OTLP export; stdout logs |

```text
ru.ludwigandreas.observability
├── config/        auto-configuration, properties, environment defaults
├── core/          ServiceIdentity, BuildIdentity
├── correlation/   correlation id, storage, validation
├── tracing/       sampler, span export filtering, trace context access
├── web/           servlet filters, outbound HTTP propagation, /server/info
├── kafka/         producer and consumer correlation
├── logging/       JSON encoder, text pattern, startup identity event, runtime log levels
└── metrics/       RED meter filters, cardinality cap
```

## Usage

```xml
<dependency>
    <groupId>ru.ludwigandreas</groupId>
    <artifactId>observability-spring-boot-starter</artifactId>
</dependency>
```

Point it at a collector:

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

A request then produces one JSON log line:

```json
{"@timestamp":"2026-09-16T09:12:13.481Z","log.level":"INFO","log.logger":"com.example.OrderService",
 "process.thread.name":"http-nio-8080-exec-3","message":"order 4711 accepted",
 "trace.id":"0af7651916cd43dd8448eb211c80319c","span.id":"b7ad6b7169203331",
 "correlation.id":"0af7651916cd43dd8448eb211c80319c","service.name":"product-catalog",
 "service.version":"1.4.2","service.environment":"prod","service.node.name":"catalog-7d9f-xk2",
 "service.commit.id":"c1fc5b8","labels":{"tenant":"acme"}}
```

response headers:

```console
$ curl -i -H 'X-Correlation-Id: order-4711' localhost:8080/api/v1/orders/4711
HTTP/1.1 200
X-Correlation-Id: order-4711
X-Trace-Id: 0af7651916cd43dd8448eb211c80319c
```

and metrics tagged by identity:

```
http_server_requests_seconds_bucket{service="product-catalog",environment="prod",version="1.4.2",
  uri="/api/v1/orders/{id}",method="GET",status="200",outcome="SUCCESS",le="0.1"} 412
```

### What is configured

| Area | Configuration |
|---|---|
| Tracing | OTLP exporter, W3C propagation, parent-based ratio sampling |
| Sampling override | `X-Ludwig-Force-Trace: true` forces tracing of one request (opt-in) |
| Span hygiene | `/actuator/**` server spans dropped at export |
| Correlation | One id across HTTP in/out, Kafka in/out, MDC, span tag, response header |
| Logs | JSON on stdout (readable text under the `local` profile), ECS/OTel/flat field sets, commit on every line, masking, truncation |
| Metrics | Identity common tags, URI cardinality cap, SLO histogram buckets, probe paths excluded |
| Annotations | `@Timed`, `@Counted`, `@Observed` aspects registered |
| Probes | Liveness/readiness split, graceful shutdown (30s) |
| Log levels | `logging.level.*` applied from a watched file or Vault secret, reverted on removal |

### Correlation id vs trace id

- **Trace id**: one distributed execution; exists only when the request was sampled.
- **Correlation id**: one piece of business work; present on 100% of requests, survives retries, can be
  supplied by the caller. When a request arrives without one, the trace id is adopted as the correlation
  id, so either value works in either tool.

| Hop | Mechanism |
|---|---|
| HTTP in | `X-Correlation-Id`, else `X-Request-Id`, else the trace id, else generated |
| HTTP out | Header on every `RestTemplate` built from the auto-configured builder |
| Kafka out | Record header, via the Spring-managed producer factory |
| Kafka in | Record header into the MDC for the listener's duration |
| Logs | MDC `correlationId` → its own JSON field |
| Traces | Span tag `correlation.id` |
| Response | `X-Correlation-Id` and `X-Trace-Id` |

Inbound values are untrusted: longer than `max-length` or outside `[A-Za-z0-9_.:-]` → **replaced** (prevents
response splitting and log amplification). Code outside these hooks reads `CorrelationContext.currentId()`.
For the audit trail, publish a `CorrelationProvider` backed by `CorrelationContext` (see
[audit-core](../audit-core#correlation)).

### Logs

**JSON everywhere except under the `local` profile**, where the default is readable text. The profile name is
fixed; `ludwig.observability.logging.json.enabled` overrides in either direction. Text keeps all metadata:

```text
2026-09-16T12:12:13.481+03:00  INFO [product-catalog/1.4.2@c1fc5b8 env=local instance=dev-laptop] [order-4711,0af7651916cd43dd8448eb211c80319c,b7ad6b7169203331] [http-nio-8080-exec-3] com.example.OrderService : order 4711 accepted
```

`[name/version@commit env= instance=]` then `[correlation,trace,span]`; unresolved parts are omitted (an
unsampled request reads `[order-4711,,]`). A pattern set via `logging.pattern.*` or a custom encoder in
`logback-spring.xml` is left untouched.

| `field-set` | Names | Commit field | For |
|---|---|---|---|
| `ecs` (default) | `@timestamp`, `log.level`, `trace.id` | `service.commit.id` | Elasticsearch, OpenSearch |
| `otel` | `Timestamp`, `SeverityText`, `TraceId` | `ServiceCommitId` | OpenTelemetry collector |
| `flat` | `timestamp`, `level`, `trace_id` | `commit_id` | Loki, schema-less stacks |

- The format is installed right after Spring Boot initialises logging, so startup and Liquibase output are
  already in it. Under JSON, `spring.main.banner-mode` defaults to `off`.
- **MDC** entries are nested under `labels` (flattening would let an MDC key overwrite a structural field
  and break shared index templates). Restrict with `mdc-include-keys`. Values whose key contains
  `password`, `secret`, `token`, `credential`, `authorization`, `apikey` or `api-key` are masked - a backstop, not a
  control.
- **SLF4J key-values** become top-level fields, masked on the same rules:
  `log.atInfo().addKeyValue("orderId", id).log("order accepted");`
- Messages are truncated at `max-message-length`.
- Logs go to **stdout only**; no files, no OTLP log export.

### Build identity

Every log event carries the abbreviated commit. The full provenance is logged once at startup:

```text
Application identity: service=product-catalog version=1.4.2 environment=prod instance=catalog-7d9f-xk2 commit=c1fc5b81ecfa20e1b3418002a5ace90473d6734c branch=master built=2026-09-16T00:00:00Z ci-build=4711 tree=clean
```

Values come from `git.properties` and `META-INF/build-info.properties`, packaged by
[`ludwig-service-parent`](../../build/ludwig-service-parent); git is never run at runtime. The build time is
truncated to the day (reproducible image digests). Unresolvable values are omitted, never `unknown`.
`BuildIdentity` is never a metric tag. `/actuator/info` exposes the `git` and `build` sections; narrow
`management.endpoints.web.exposure.include` if branch and commit must not be disclosed.

### `GET /server/info`

On the application port of a servlet service:

```json
{"service": "product-catalog", "version": "1.4.2", "environment": "prod", "commit": "c1fc5b8", "built": "2026-09-16"}
```

A fixed path in every service, for UIs that show which service and version answered. The five members are
an allow-list (no branch, dirty flag, CI number, full commit, namespace or instance). Unresolved values are
omitted; no identity at all answers `200 {}`. The starter adds no security rule: behind the security
starter, list it explicitly:

```yaml
ludwig:
  security:
    # This list replaces the default list - restate what you still need.
    public-paths: [/actuator/health, /actuator/health/**, /actuator/info, /server/info]
```

### Forcing a trace

```console
$ curl -H 'X-Ludwig-Force-Trace: true' https://catalog.internal/api/v1/orders/4711
```

Off by default (`tracing.sampling.trusted-force-header`): anything that can set the header can make the
service trace all traffic into a per-span-billed backend. Enable only where the edge strips or authorises the
header. Applies only at the trace root; an inbound sampling decision is respected.

### Runtime log levels

With [`hot-reload-spring-boot-starter`](../hot-reload-spring-boot-starter):

```yaml
ludwig:
  hotreload:
    files:
      - path: /etc/config/logging.yml
```

```yaml
# /etc/config/logging.yml
logging:
  level:
    com.example.payments: DEBUG
```

Removing the key **reverts** the logger to its startup level, so a forgotten DEBUG does not stay on until the
next restart.

### Probes

```console
$ curl localhost:8080/actuator/health/liveness    # JVM wedged? restart
$ curl localhost:8080/actuator/health/readiness   # can serve? route traffic
```

A service that lost its database is unready, not dead. Each service decides what its readiness group
includes (`management.endpoint.health.group.readiness.include`); keep dependencies out of liveness.

Exposure defaults to `health,info,metrics,prometheus,loggers`. `env`, `configprops`, `heapdump` and
`threaddump` are excluded (they disclose credentials and memory).

## Configuration

Every value below is a default published at the lowest precedence; anything set in `application.yml`, the
environment or system properties wins. Spring Boot's own properties are used where they exist
(`management.tracing.sampling.probability`, `management.otlp.tracing.endpoint`, `management.endpoints.*`).

| Property | Default | Meaning |
|---|---|---|
| `ludwig.observability.enabled` | `true` | Master switch |
| `ludwig.observability.service.name` | `${spring.application.name}` | On metrics, spans and logs |
| `ludwig.observability.service.version` | jar manifest / `build-info` | |
| `ludwig.observability.service.environment` | first active profile | **Set explicitly in every deployment** |
| `ludwig.observability.service.namespace` | unset | Optional grouping |
| `ludwig.observability.service.instance` | `$HOSTNAME` | Pod name under Kubernetes |
| `ludwig.observability.tracing.enabled` | `true` | |
| `ludwig.observability.tracing.excluded-paths` | `/actuator/**` | Dropped at export |
| `ludwig.observability.tracing.sampling.force-header` | `X-Ludwig-Force-Trace` | |
| `ludwig.observability.tracing.sampling.trusted-force-header` | `false` | Honour the force header |
| `ludwig.observability.correlation.enabled` | `true` | |
| `ludwig.observability.correlation.header-name` | `X-Correlation-Id` | Must match `ludwig.messaging.correlation-header` |
| `ludwig.observability.correlation.additional-inbound-headers` | `[X-Request-Id]` | Tried in order |
| `ludwig.observability.correlation.max-length` | `128` | Must be positive |
| `ludwig.observability.correlation.allowed-pattern` | `[A-Za-z0-9_.:-]+` | Non-matching values replaced |
| `ludwig.observability.correlation.kafka.enabled` / `.header-name` | `true` / `X-Correlation-Id` | Kafka propagation |
| `ludwig.observability.correlation.mdc-key` | `correlationId` | |
| `ludwig.observability.correlation.trace-id-response-header` | `X-Trace-Id` | |
| `ludwig.observability.build.*` | from `git.properties` / `build-info.properties` | `commit-id`, `abbreviated-commit-id`, `branch`, `timestamp`, `ci-build-number`, `dirty` |
| `ludwig.observability.server.info.enabled` | `true` | Serve `GET /server/info` |
| `ludwig.observability.logging.json.enabled` | `true`; `false` under `local` | |
| `ludwig.observability.logging.json.field-set` | `ecs` | `ecs`, `otel`, `flat` |
| `ludwig.observability.logging.json.max-message-length` | `16384` | `0` = no truncation |
| `ludwig.observability.logging.json.mdc-include-keys` / `mdc-exclude-keys` | empty (all) / empty | Allow-/deny-list of MDC keys |
| `ludwig.observability.logging.json.masked-keys` | `password, secret, token, credential, authorization, apikey, api-key` | Key fragments whose values are masked |
| `ludwig.observability.logging.json.max-stack-trace-length` | `12288` | |
| `ludwig.observability.logging.json.static-fields` | empty | Extra constant fields on every event |
| `ludwig.observability.logging.levels.hot-reload-enabled` | `true` | Apply `logging.level.*` from hot-reload sources |
| `ludwig.observability.logging.levels.revert-on-removal` | `true` | |
| `ludwig.observability.metrics.enabled` | `true` | |
| `ludwig.observability.metrics.aspects.enabled` | `true` | `@Timed`/`@Counted`/`@Observed` aspects |
| `ludwig.observability.metrics.common-tags` | `true` | Identity tags on every meter |
| `ludwig.observability.metrics.http.max-uri-tags` | `100` | Distinct `uri` values before `OTHER` |
| `ludwig.observability.metrics.http.slo` | `50ms, 100ms, 200ms, 500ms, 1s, 2s, 5s, 10s` | Histogram buckets = latencies you promise |
| `spring.main.banner-mode` | `off` under JSON | |

## Deployment

- **Collector**: an OTLP HTTP endpoint for traces (`management.otlp.tracing.endpoint`).
- **Prometheus**: scrape `/actuator/prometheus` on the management port.
- **Log shipping**: the node agent collects stdout. With a strict index template, map the commit field
  (`service.commit.id` / `ServiceCommitId` / `commit_id`) as a keyword before the first service writes it.
- **Kubernetes probes**: `livenessProbe` → `/actuator/health/liveness`, `readinessProbe` →
  `/actuator/health/readiness`; `terminationGracePeriodSeconds` above the 30s graceful shutdown plus any
  worker drain timeouts.
- **Edge**: strip `X-Ludwig-Force-Trace` from untrusted callers if `trusted-force-header` is on.

The actuator, tracing bridge, OTLP exporter and Prometheus registry are compile dependencies (a deployment that
exports differently excludes them); Spring MVC, Spring Kafka, web-core and hot-reload are optional.

## Observability

This module defines the platform's observability; its own signals:

| Signal | Meaning |
|---|---|
| `INFO Application identity: ...` | Once per startup |
| `WARN Reached the limit of <n> distinct 'uri' tag values ...` | Cardinality cap hit; unmatched paths (often scanners) now recorded as `OTHER` |
| `WARN Structured JSON logging is enabled but no pattern-encoded appender was found` | A custom logback configuration bypasses the JSON encoder |
| `INFO/WARN Hot-reloaded log level ...` | Runtime level changes, reverts, invalid levels |

**Recommended platform alerts** (per service, using the tags this module adds):

| Condition | Why |
|---|---|
| `sum(rate(http_server_requests_seconds_count{outcome="SERVER_ERROR"}[5m])) / sum(rate(http_server_requests_seconds_count[5m])) > 0.01` | Error ratio |
| `histogram_quantile(0.99, sum by (le, uri) (rate(http_server_requests_seconds_bucket[5m])))` above SLO | Latency |
| readiness probe failing on all replicas | Service unavailable |
| `jvm_memory_used_bytes{area="heap"} / jvm_memory_max_bytes` > 0.9 for 10 min | Memory pressure |
| `hikaricp_connections_pending` > 0 for 5 min | Connection pool exhaustion |

**Known gaps:** no correlation propagation for `WebClient` (use an `ExchangeFilterFunction` reading
`CorrelationContext`) or for Kafka producers built outside the Spring-managed factory.

## Audit

Not applicable: no state changes. Runtime log level changes are audited by hot-reload (`config` category).

## Runbook

| Symptom | Likely cause | What to do |
|---|---|---|
| No traces in the backend | Endpoint unset/unreachable, or probability 0 | Check `management.otlp.tracing.endpoint`, network to the collector, `management.tracing.sampling.probability` |
| Need a trace for one failing request | Not sampled | Repeat it with `X-Ludwig-Force-Trace: true` (if enabled), or search logs by correlation id |
| Logs not parsed by the aggregator | Text format in a non-`local` profile, or wrong field set | Check `ludwig.observability.logging.json.enabled` and `field-set` |
| `uri="OTHER"` dominates | Unmatched paths (scanners, 404s) or too many templates | Expected for scanners; raise `max-uri-tags` only if real templates exceed it |
| Pod restarts in a loop while the database is down | Liveness includes a dependency | Remove it from the liveness group; put it in readiness |
| 502s during deploys | Grace period shorter than shutdown | Raise `terminationGracePeriodSeconds`; check `server.shutdown=graceful` |
| Need DEBUG on a running pod | - | Add `logging.level.<pkg>: DEBUG` to the hot-reloaded file; remove the line to revert |
| Correlation id missing on Kafka consumer logs | Factory not built by messaging's builder | Use `ListenerContainerFactoryBuilder` (it composes the correlation interceptor) |

## Testing

Unit and context tests for correlation validation, propagation (HTTP, Kafka), JSON encoders per field set,
text pattern, masking, truncation, cardinality cap, force-trace sampler, log level reload/revert, and
`/server/info` allow-list. Services get the behaviour by dependency; their `ApiDocumentIT` covers
`/server/info` in the OpenAPI document.

## Design decisions

- **One correlation header, one log shape, one identity** for the whole estate, so signals link across services.
- **Histograms, not client-side percentiles**: per-replica p99s cannot be aggregated and are biased low.
- **Identity as common tags**, not left to the scraper: survives federation and carries the version.
- **No log files**: containers write to stdout; the node agent ships it.
- **Text only under `local`**: a JSON default that must be switched on is one that services forget.
