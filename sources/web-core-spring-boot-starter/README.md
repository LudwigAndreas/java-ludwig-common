# web-core-spring-boot-starter

***English** · [Русский](README.ru.md)*

The REST foundation every service sits on: one localized [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457)
`ProblemDetail` pipeline that modules **contribute to** (no module ships its own `@RestControllerAdvice`),
transport-neutral business exceptions, `Accept-Language` locale resolution wired into Bean Validation,
caller preferences (language and time zone), a paged response envelope, and the long-running-operation
contract. A plain `@RestController` answers every failure - business, validation, framework, security,
unhandled - as one problem document in the caller's language.

| | |
|---|---|
| Role | starter |
| Depends on (in-repo) | nothing |
| Used by | nearly every starter and both services (see `PROJECT_INDEX.md`) |
| Surfaces | REST (error rendering, response envelopes) |

| Package | Contents |
|---|---|
| `problem` | `LocalizedException`, `ProblemStatus`, `ProblemDefinition`, `ProblemCodes`, `Violation`; `ProblemMessages`, `ProblemMessageBundle`, `ExceptionProblemMapper`, `ProblemMapperRegistry`, `ProblemDetailFactory` - none MVC-dependent |
| `problem.mapper` | Shipped mappers, each conditional on the class it maps |
| `web` | `ApiExceptionHandler`, `PageResponse` |
| `preference` | `UserPreferences`, `UserPreferenceSource`, `UserPreferenceFormatter` |
| `operation` | `OperationStatus`, `OperationResponse`, `OperationProgress`, `OperationResult`, `OperationFailure`, `Cancellation`, `OperationHeaders`, `OperationLocationHeader`, `OperationResponses` |
| `trace` | `TraceIdProvider`, `MdcTraceIdProvider` |
| `config` | Auto-configurations, `WebCoreProperties` |

## Usage

```xml
<dependency>
    <groupId>ru.ludwigandreas</groupId>
    <artifactId>web-core-spring-boot-starter</artifactId>
</dependency>
```

Declare what a failure means in the service layer, without HTTP:

```java
public class ProductNotFoundException extends LocalizedException {
    public ProductNotFoundException(UUID id) {
        super(ProblemStatus.NOT_FOUND, "error.product.not-found", id);
    }
}
```

Text in `src/main/resources/i18n/messages[_ru].properties`:

```properties
error.product.not-found.title=Product not found
error.product.not-found=No product exists with id {0}.
```

Throw it:

```console
$ curl -H 'Accept-Language: ru' localhost:8080/api/v1/products/8f3a...
HTTP/1.1 404
Content-Type: application/problem+json
Content-Language: ru

{
  "type": "urn:ludwig:problem:error.product.not-found",
  "title": "Товар не найден",
  "detail": "Товар с идентификатором 8f3a... не существует.",
  "status": 404,
  "instance": "/api/v1/products/8f3a...",
  "code": "error.product.not-found",
  "timestamp": "2026-09-15T18:22:07.411Z",
  "traceId": "0af7651916cd43dd8448eb211c80319c"
}
```

### How the pipeline works

A module states only what its failure **means** (status, code, arguments, extra properties) through an
`ExceptionProblemMapper` bean, and ships its text as a `ProblemMessageBundle` bean. One advice renders
everything. The application's own `MessageSource` is consulted before any module bundle, so any wording can
be overridden by defining the same key locally.

```
 module: ExceptionProblemMapper ─┐                    ┌─> ProblemMessages (app bundle first, then modules)
 module: ProblemMessageBundle  ──┼─> ProblemMapperRegistry (first match wins)
 app:    LocalizedException    ──┘                    └─> ProblemDetailFactory ─> ApiExceptionHandler
```

### What it answers out of the box

| Failure | Status | Code |
|---|---|---|
| a `LocalizedException` | as declared | as declared |
| `@Valid @RequestBody` rejected | 400 | `ludwig.web.error.validation` + `violations` |
| constraint on `@RequestParam` / `@PathVariable` | 400 | `ludwig.web.error.validation` + `violations` |
| `ConstraintViolationException` from a `@Validated` bean | 400 | `ludwig.web.error.validation` + `violations` |
| unparseable body | 400 | `ludwig.web.error.malformed-request` |
| missing or unconvertible parameter | 400 | `ludwig.web.error.bad-request` + `parameter` |
| wrong method | 405 | `ludwig.web.error.method-not-allowed` (keeps `Allow`) |
| wrong `Content-Type` | 415 | `ludwig.web.error.unsupported-media-type` |
| nothing acceptable to `Accept` | 406 | `ludwig.web.error.not-acceptable` |
| unknown path | 404 | `ludwig.web.error.not-found` |
| `AccessDeniedException` inside the dispatch | 403 | `ludwig.web.error.forbidden` |
| `AuthenticationException` inside the dispatch | 401 | `ludwig.web.error.unauthorized` |
| `DataIntegrityViolationException` | 409 | `ludwig.web.error.conflict` |
| `OptimisticLockingFailureException` | 409 | `ludwig.web.error.concurrent-modification` |
| `PessimisticLockingFailureException` | 423 | `ludwig.web.error.concurrent-modification` |
| `QueryTimeoutException` | 503 | `ludwig.web.error.upstream-unavailable` |
| oversized upload | 413 | `ludwig.web.error.payload-too-large` |
| anything unhandled | 500 | `ludwig.web.error.internal` |

`@PreAuthorize` and data guards throw inside the MVC dispatch, after the security filter chain, so without
this mapping they would be 500s. Every response has `Content-Type: application/problem+json` and a
`Content-Language` naming the language actually used.

### Extending

Map an exception:

```java
@Bean
ExceptionProblemMapper quotaExceededMapper() {
    return ExceptionProblemMapper.forType(
            QuotaExceededException.class,
            e -> ProblemDefinition.of(ProblemStatus.TOO_MANY_REQUESTS, "error.quota.exceeded", e.limit())
                    .withProperty("limit", e.limit()));
}
```

- Mappers are consulted in `Ordered` order; first match wins. Library mappers use
  `ExceptionProblemMapper.DEFAULT_MODULE_ORDER`, so an application mapper (order 0) overrides them.
- The registry walks the cause chain (JPA, transaction and proxy wrappers), shallower causes first, so a
  deliberate 409 stays a 409 when a framework wraps it.

Contribute text from a module:

```java
@Bean
ProblemMessageBundle myModuleProblemMessages() {
    return ProblemMessageBundle.of("i18n/ludwig-mymodule-messages");
}
```

Report domain violations in the same shape as Bean Validation:

```java
throw new InvalidProductException()
        .withProperty("violations", List.of(
                Violation.of("price", messages.get(...), "PriceBelowCost")));
```

Modules contributing to the pipeline: `audit`, `db-core`, `export`, `file-action`, `file-ingest`,
`grpc-server`, `idempotency`, `messaging`, `object-storage`, `odata-filter`, `pat`, `security`,
`user-settings`, and `notification-service`.

### Localization

Three beans, all `@ConditionalOnMissingBean`, declared before Boot's `MessageSourceAutoConfiguration` and
`ValidationAutoConfiguration`:

- a `MessageSource` over `ludwig.web.i18n.basenames`, UTF-8, never falling back to the server's locale;
- an `AcceptHeaderLocaleResolver` limited to `supported-locales`, so an unsupported language gets the
  default language in full, not a half-translated response;
- a `LocalValidatorFactoryBean` bound to that `MessageSource`, so constraint messages are localized too.

Keep bundles in lockstep across locales; `checkstyle-rules` checks bundle key consistency.

### Caller preferences

`UserPreferences` is the platform's single answer to "which language and time zone does this caller read
in", and it publishes the zone into `LocaleContextHolder` (so `@JsonFormat`, `@DateTimeFormat` and message
arguments use the caller's zone, not the container's).

In a MapStruct mapper, name the formatter and conversions are selected by type:

```java
@Mapper(componentModel = "spring", uses = UserPreferenceFormatter.class)
public interface ProductMapper {
    ProductResponse toResponse(Product product);   // Instant createdAt -> OffsetDateTime in the caller's zone
}
```

`UserPreferenceFormatter` has exactly one method per source/target pair (`Instant → OffsetDateTime`,
`Instant → LocalDate`), so MapStruct needs no qualifiers.

Elsewhere: `UserPreferences.current()` (never null, never throws; conservative default) or
`currentIfResolved()` (empty if nobody expressed a preference). Derived values are methods:
`numberFormat()`, `firstDayOfWeek()`, `dateTimeFormatter(FormatStyle)`.

Resolution, per dimension independently (first source that answers wins):

| Order | Source | Answers from |
|---|---|---|
| 100 | `StoredUserPreferenceSource` ([user-settings](../user-settings-spring-boot-starter)) | stored `user.locale` / `user.timezone`, only when set at a layer more specific than `PLATFORM` |
| 200 | `RequestHeaderPreferenceSource` | `Accept-Language`; time zone header (`X-Time-Zone` by default) |
| last | `ConfiguredPreferenceSource` | `ludwig.web.i18n.default-locale`, `ludwig.web.preferences.default-zone` |

A stored choice beats a request header (the browser's header is a hint; the stored value is the user's
choice). Both `Europe/Moscow` and `+03:00` are accepted in the header; an unusable value is ignored, not
rejected. A caller tag like `ru-RU` is kept when its language is supported (region data drives first day of
week and separators), so `Content-Language` may be `ru-RU`.

Off the request thread:

```java
UserPreferences captured = UserPreferences.current();          // request thread
try (UserPreferences.Scope scope = captured.bind()) {          // worker thread
    render(captured);
}
```

This is the **ambient** caller context only. "What are user X's preferences" is
`SettingsLookup.getAll(subject)` in user-settings.

### Paged responses

```java
@GetMapping
public PageResponse<ProductResponse> search(ProductQuery query) {
    return PageResponse.of(productService.search(query), mapper::toResponse);
}
```

```json
{ "content": [...], "page": 1, "size": 20, "offset": 25, "totalElements": 137, "totalPages": 7 }
```

- **`offset` is authoritative**; `page` is derived and lossy for non-aligned offsets (OData `$skip`).
- `totalElements` / `totalPages` are **absent** (not null, not 0) when the caller declined the count
  (`$count=false`). Use `PageResponse.of(content, offset, size, totalElements)` for non-`Page` results.
- Spring Data's `Page` is never returned directly: its JSON shape is a persistence-library detail.

### Long-running operations

A **contract, not a framework**: each module keeps its own run table and maps onto this envelope at its edge.

```
PENDING -> RUNNING -> SUCCEEDED | FAILED | CANCELLED
SUCCEEDED -> EXPIRED            (retention removed the result; the record stays)
```

`EXPIRED` and `CANCELLED` are not failures: branch on `status.isFailure()`, never on `!= SUCCEEDED`. Richer
domain states (e.g. reconciliation's `COLLECTING`) map onto these and stay visible in
`OperationResponse.detail()`.

```java
@PostMapping
ResponseEntity<RunResponse> submit(@Valid @RequestBody RunRequest body) {
    Run run = service.submit(body);
    RunResponse response = response(run);
    return run.isQueued()
            ? OperationResponses.accepted(response.operation(), response,
                    statusUri(run.getId()), OperationLocationHeader.LOCATION)
            : OperationResponses.completed(response.operation(), response);
}
```

| Endpoint | Response |
|---|---|
| submit, queued | `202` + `Location` (the operation is the resource) or `Operation-Location` (result lives elsewhere) - the caller must choose |
| submit, already done | `200` with a terminal envelope |
| poll (`OperationResponses.poll`) | `200`; `Retry-After` while non-terminal. Refuses a non-terminal response without `Retry-After`, a success without `result`, a failure without `failure` |
| cancel (`cancellationRequested`) | `202` (cooperative cancellation); already-finished operation returns its terminal envelope, not 409 |

`OperationProgress.total` is nullable (a streaming producer cannot know it); null is absent in JSON. A
submit endpoint should accept `Idempotency-Key`: a duplicate gets `409` + `Retry-After`, never a second `202`.

### Coexisting with your own advice

The shared advice is registered at `Ordered.LOWEST_PRECEDENCE`. Spring uses the first advice that can handle
an exception at all, so anywhere but last it would swallow exceptions your own advice handles. A service
advice without `@Order` still precedes it. `ludwig.web.problem.enabled=false` removes the advice and leaves
`ProblemDetailFactory`, `ProblemMessages` and the mapper chain injectable.

### Non-web use

`LocalizedException`, `ProblemStatus`, `ProblemMessages` and the mapper chain have no MVC dependency; the MVC
half is conditional on a servlet web application. Batch jobs and Kafka consumers render the same errors into
replies, logs or dead letters.

## Configuration

| Property | Default | Meaning |
|---|---|---|
| `ludwig.web.enabled` | `true` | Master switch |
| `ludwig.web.problem.enabled` | `true` | Register the shared advice |
| `ludwig.web.problem.type-prefix` | `urn:ludwig:problem:` | Prefix of the `type` URI; point it at documentation to make it dereferenceable |
| `ludwig.web.problem.include-instance` | `true` | `instance` = request URI |
| `ludwig.web.problem.include-timestamp` | `true` | Add `timestamp` |
| `ludwig.web.problem.include-trace-id` | `true` | Publish the trace id in the body |
| `ludwig.web.problem.trace-id-mdc-key` | `traceId` | MDC key to read it from |
| `ludwig.web.problem.trace-id-property` | `traceId` | Member name in the body |
| `ludwig.web.problem.include-rejected-value` | `false` | Echo submitted values in `violations` - may leak secrets |
| `ludwig.web.problem.include-exception-message` | `false` | Add `debug` to 5xx problems - leaks internals |
| `ludwig.web.problem.advice-order` | last | Order of the shared advice |
| `ludwig.web.i18n.enabled` | `true` | Configure `MessageSource`, `LocaleResolver`, validator |
| `ludwig.web.i18n.basenames` | `classpath:i18n/messages` | Application bundles |
| `ludwig.web.i18n.encoding` | `UTF-8` | Bundle encoding |
| `ludwig.web.i18n.supported-locales` | `en` | Languages the API answers in |
| `ludwig.web.i18n.default-locale` | `en` | Fallback language |
| `ludwig.web.i18n.fallback-to-system-locale` | `false` | Allow the host locale |
| `ludwig.web.i18n.cache-duration` | `-1` (forever) | Positive = re-read changed bundles |
| `ludwig.web.i18n.configure-validator` | `true` | Bind Bean Validation to the `MessageSource` |
| `ludwig.web.preferences.enabled` | `true` | Resolve caller preferences; off = plain `AcceptHeaderLocaleResolver` |
| `ludwig.web.preferences.default-zone` | `UTC` | Zone when nothing else answers (never the container's) |
| `ludwig.web.preferences.time-zone-header` | `X-Time-Zone` | Header carrying the caller's zone; blank = none |
| `ludwig.web.preferences.accept-language` | `true` | Consult `Accept-Language` |

## Deployment

Nothing to deploy. The build must compile with `-parameters` (set in the root POM and
`ludwig-service-parent`); without it a parameter violation names the input `arg0`.

## Observability

**Logs.** `ERROR Request failed with <status> [<code>]` for 5xx, with the exception (the client sees only the
trace id). `INFO` at startup naming the preference sources and defaults.

**Traces.** The trace id (MDC `traceId`, populated by observability) is put into every problem body, so
"quote the trace id" is an instruction a user can follow.

**Metrics.** None of its own; HTTP server metrics come from
[observability](../observability-spring-boot-starter) and Spring Boot.

## Audit

Not applicable. Security decisions are audited by the security starter; this module only renders responses.

## Runbook

| Symptom | Likely cause | What to do |
|---|---|---|
| A user reports an error | - | Ask for the `traceId` from the body; search logs and traces by it |
| Error text in English for a Russian caller | Missing key in the `_ru` bundle, or `ru` not in `supported-locales` | Add the key; check `ludwig.web.i18n.supported-locales` |
| Expected 4xx arrives as 500 | Exception not mapped and not a `LocalizedException` | Add an `ExceptionProblemMapper` or extend `LocalizedException` |
| Own `@RestControllerAdvice` never called | It has a lower precedence than another advice handling `Exception` | Give it an explicit `@Order` |
| Validation messages show `{...}` keys | `configure-validator=false` or the key is missing | Re-enable / add the key |
| Dates rendered in UTC instead of the user's zone | Mapper does not use `UserPreferenceFormatter`, or code uses `ZoneOffset.UTC` | Use the formatter / `UserPreferences.current()` |

## Testing

Unit and slice tests for every mapping in the table above, bundle resolution order, preference resolution
order, operation response invariants and `PageResponse` serialization. In a service, assert error responses
with MockMvc against `code` and `status`, not against text.

## Design decisions

- **One advice, contributed mappers.** Separate per-module advices produce different shapes and cannot be
  translated by the application.
- **401/403 bodies are generic.** Saying *why* access was denied makes the endpoint an enumeration oracle; the
  specifics go to the audit log. A 401 rendered here has no `WWW-Authenticate` (avoids a browser credential
  prompt). With the security starter, codes are `ludwig.security.error.*`, identical to the filter chain's.
- **No secrets in problem bodies**: rejected values and exception messages are off by default and are
  explicit deployment switches.
- **Preferences hold two dimensions.** Everything else is derived, so stored values cannot contradict each other.
- **Enforced by ArchUnit** (`RuleGroup.PRESENTATION`): `noAmbientDefaultLocaleOrZone` (no `Locale.getDefault()`,
  `ZoneId.systemDefault()`, ...) and `noSecondCallerPreferenceType`. A mapper using `ZoneOffset.UTC` is a review
  question, not a rule.
- **No shared operation table.** Each module's run table fits its own queries.
