# web-core-spring-boot-starter

***English** · [Русский](README.ru.md)*

The REST foundation every service in this repository sits on: one localized
[RFC 9457](https://www.rfc-editor.org/rfc/rfc9457) (formerly RFC 7807) `ProblemDetail` pipeline that
every module *contributes to* instead of shipping its own `@RestControllerAdvice`, transport-neutral
business exceptions, request-locale resolution wired into Bean Validation, and a paged response
envelope that doesn't leak Spring Data's JSON shape.

Add the dependency and a plain `@RestController` service answers every failure - business,
validation, framework, security, unhandled - as one problem document in the caller's language, with
no advice, no `MessageSource` and no locale resolver of its own.

## Quick start

```xml
<dependency>
    <groupId>ru.ludwigandreas</groupId>
    <artifactId>web-core-spring-boot-starter</artifactId>
    <version>1.0.0</version>
</dependency>
```

Declare what a failure *means*, in the service layer, with no reference to HTTP:

```java
public class ProductNotFoundException extends LocalizedException {

    public ProductNotFoundException(UUID id) {
        super(ProblemStatus.NOT_FOUND, "error.product.not-found", id);
    }
}
```

Put its text in `src/main/resources/i18n/messages[_ru].properties`:

```properties
error.product.not-found.title=Product not found
error.product.not-found=No product exists with id {0}.
```

Throw it. Nothing else to wire:

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

## The problem this starter exists to remove

Every module in this repository that could fail in a way a client sees used to ship its own
`@RestControllerAdvice`. That looks like good encapsulation and it does not survive contact with a
second module. Two things go wrong, and they compound:

**Language.** A library advice can only emit the text it was compiled with. The OData starter's
advice answered with its exceptions' own developer-facing English - *"Property 'supplierCost' cannot
be used in $filter/$orderby: not annotated @Filterable"* - because the only bundle that could
translate it would have to live in the application. So a service that answered every other error in
the caller's language had one subsystem answering in English. The only way out was to switch that
advice off (`odata.filter.web.problem-detail-advice-enabled=false`) and re-handle all seven of its
exception types by hand. Every service using that module wrote the same advice, and this starter's
predecessor - the `ApiExceptionHandler` in `crud-service-example` - carried a comment saying so.

**Shape.** Several advices mean several opinions about what a problem document contains. One set
`title` and no `code`; another the reverse. A client could not rely on `code` being present, because
whether it was depended on which subsystem had failed.

The fix is to separate *meaning* from *rendering*. A module states only what its failure means - this
is a 400, this is its code, this is the field at fault - by contributing an `ExceptionProblemMapper`,
and ships its text as a `ProblemMessageBundle`. One advice renders everything, and the application's
own bundle is consulted before any module's, so any wording can be overridden by defining the same
key locally. No fork, no configuration, no re-handling.

```
      a module                    an application                  this starter
 ┌──────────────────┐          ┌───────────────────┐         ┌────────────────────┐
 │ its exceptions   │          │ LocalizedException│         │ ProblemMapper      │
 │        │         │          │   subclasses      │         │   Registry         │
 │        ▼         │          │        │          │         │        │           │
 │ ExceptionProblem │──────────┼────────┼──────────┼────────▶│  first match wins  │
 │   Mapper (bean)  │          │        ▼          │         │        │           │
 │        +         │          │  i18n/messages    │         │        ▼           │
 │ ProblemMessage   │──────────┼──── consulted ────┼────────▶│  ProblemMessages   │
 │  Bundle (bean)   │          │      first        │         │        │           │
 └──────────────────┘          └───────────────────┘         │        ▼           │
                                                             │ ProblemDetail      │
                                                             │   Factory          │
                                                             │        │           │
                                                             │        ▼           │
                                                             │ ApiExceptionHandler│
                                                             └────────────────────┘
```

## What it answers, out of the box

| Failure | Status | Code |
|---|---|---|
| a `LocalizedException` | whatever it declares | whatever it declares |
| `@Valid @RequestBody` rejected | 400 | `ludwig.web.error.validation` + `violations` |
| a constraint on a `@RequestParam`/`@PathVariable` | 400 | `ludwig.web.error.validation` + `violations` |
| `ConstraintViolationException` from a `@Validated` bean | 400 | `ludwig.web.error.validation` + `violations` |
| unparseable body | 400 | `ludwig.web.error.malformed-request` |
| missing or unconvertible parameter | 400 | `ludwig.web.error.bad-request` + `parameter` |
| wrong method | 405 | `ludwig.web.error.method-not-allowed` (keeps `Allow`) |
| wrong `Content-Type` | 415 | `ludwig.web.error.unsupported-media-type` |
| nothing acceptable to `Accept` | 406 | `ludwig.web.error.not-acceptable` |
| unknown path | 404 | `ludwig.web.error.not-found` |
| `AccessDeniedException` in the dispatch | 403 | `ludwig.web.error.forbidden` |
| `AuthenticationException` in the dispatch | 401 | `ludwig.web.error.unauthorized` |
| `DataIntegrityViolationException` | 409 | `ludwig.web.error.conflict` |
| `OptimisticLockingFailureException` | 409 | `ludwig.web.error.concurrent-modification` |
| `PessimisticLockingFailureException` | 423 | `ludwig.web.error.concurrent-modification` |
| `QueryTimeoutException` | 503 | `ludwig.web.error.upstream-unavailable` |
| an oversized upload | 413 | `ludwig.web.error.payload-too-large` |
| anything unhandled | 500 | `ludwig.web.error.internal` |

Several of those are the point of the module rather than decoration. `AccessDeniedException` and
`AuthenticationException` are thrown by `@PreAuthorize` and data guards *inside* the MVC dispatch,
after the security filter chain has handed the request over - the application's `AccessDeniedHandler`
never sees them, and without this they surface as 500s. A constraint violation and an optimistic
locking failure are also 500s by default: a rejected input and a lost write race, both reported as
server faults.

Every response carries `Content-Type: application/problem+json` and a `Content-Language` naming the
language the body actually came back in - which is not always the one that was asked for.

## Extending it

### Teach it an exception it has never heard of

One bean. `ExceptionProblemMapper.forType` covers the common case:

```java
@Bean
ExceptionProblemMapper quotaExceededMapper() {
    return ExceptionProblemMapper.forType(
            QuotaExceededException.class,
            e -> ProblemDefinition.of(ProblemStatus.TOO_MANY_REQUESTS, "error.quota.exceeded", e.limit())
                    .withProperty("limit", e.limit()));
}
```

Mappers are consulted in `Ordered` order and the first match wins. A library's own mappers register
at `ExceptionProblemMapper.DEFAULT_MODULE_ORDER`, so an application's mapper - at the default order
of 0 - overrides any of them without having to know what number the library picked.

The registry also walks the exception's causes. The exception that reaches an advice is often not the
one that was thrown: a JPA flush wraps a constraint violation, a transaction manager wraps a commit
failure, a proxy wraps a checked exception. Walking the chain means a deliberate 409 stays a 409 when
a framework layer decides to wrap it, instead of degrading to a 500. Shallower causes win, so a
wrapper that *is* mapped beats its own mapped cause.

### Contribute text from a module

```java
@Bean
ProblemMessageBundle myModuleProblemMessages() {
    return ProblemMessageBundle.of("i18n/ludwig-mymodule-messages");
}
```

Resolution order for a key is total: the application's `MessageSource` first, then the contributed
bundles in their declared order, then the caller's default. So a module ships working text, and an
application reworded any of it by defining the same key in its own bundle.

### Report your own violations

A domain rule that Bean Validation cannot express reports in the same shape as a rejected
`@NotBlank`, so a client has one code path for "rejected field by field":

```java
throw new InvalidProductException()
        .withProperty("violations", List.of(
                Violation.of("price", messages.get(...), "PriceBelowCost")));
```

## Localization

Three beans a REST service would otherwise write itself, all `@ConditionalOnMissingBean`:

- a `MessageSource` over `ludwig.web.i18n.basenames`, UTF-8, never falling back to the server's own
  locale - otherwise the API's language depends on how the container happens to be configured;
- an `AcceptHeaderLocaleResolver` limited to `supported-locales`, so a caller asking for a language
  with no bundle gets the default *in full* rather than a half-translated response;
- a `LocalValidatorFactoryBean` bound to that `MessageSource`, which is what makes constraint
  messages (`{catalog.validation.product.sku.required}`) localized. Without it a rejected request
  comes back with a translated `title` and English field messages.

This configuration is declared `before` Spring Boot's `MessageSourceAutoConfiguration` and
`ValidationAutoConfiguration`, both of which back off when a bean of the relevant type already
exists - so it wins by ordering rather than by fighting. Any application bean of the same type takes
precedence, and `ludwig.web.i18n.enabled=false` hands the whole question back to Boot.

Keep the bundles in lockstep: a key present in one locale and absent in another is how an API ends up
answering half in one language and half in another. A small test asserting identical key sets across
bundles is worth the ten lines.

## Paged responses

```java
@GetMapping
public PageResponse<ProductResponse> search(ProductQuery query) {
    return PageResponse.of(productService.search(query), mapper::toResponse);
}
```

```json
{ "content": [...], "page": 0, "size": 20, "totalElements": 137, "totalPages": 7 }
```

Spring Data's `Page` is not returned directly. Its JSON shape is an implementation detail of the
persistence library - it has changed between versions, and serializing it emits a `pageable` object
describing how the query was executed rather than what the client asked for. Returning it makes every
consumer of the API depend on the service's choice of data-access library; Spring Boot 3.3 warns
about exactly this on startup.

## Long-running operations

Five modules of this platform had each named the states of a long-running run for themselves, and they
disagreed. Export called a finished run `SUCCEEDED`; file-ingest called the same thing `COMPLETED`.
One state, two words, in one platform, visible in two APIs. Export answered `202` with no `Location`,
so a client was told to poll and not told where. Nothing anywhere emitted `Retry-After`, so every
client invented its own interval and they all picked one second.

`ru.ludwigandreas.webcore.operation` is the contract that fixes that. It is a **contract, not a
framework**: there is no shared operation table behind it and there is not going to be one. Four
modules already track runs in tables designed for their own domain, and a generic table would
duplicate all four, add a write to every transition and be worse at every one of their queries. Each
module keeps its table and maps onto the envelope at its edge - which is also what keeps this starter
free of a persistence dependency it must not have.

### The vocabulary

```
PENDING -> RUNNING -> SUCCEEDED | FAILED | CANCELLED
SUCCEEDED -> EXPIRED            (retention removed the result; the record stays)
```

`EXPIRED` is terminal and is **not** a failure, and neither is `CANCELLED`. Branch on
`status.isFailure()`, never on `!= SUCCEEDED` - a client that treats every non-`SUCCEEDED` terminal
state as an error will page somebody for routine housekeeping.

Richer domain lifecycles are not replaced by this. Reconciliation's `COLLECTING`, `PENDING_SUBMIT` and
`ORPHANED` carry information these six constants cannot; they map onto the core and stay visible in
`OperationResponse.detail()`. A shared vocabulary that erased them would be a downgrade, and somebody
would correctly refuse to adopt it. `architecture-rules`' `operations.no-second-operation-vocabulary`
is careful about the difference - see that module's README.

### Submitting

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

`202` with a header pointing at the status resource, and the caller has to choose which header:

- **`Location`** when the operation *is* the resource the client will read. Export's run and
  notification's request both work this way.
- **`Operation-Location`** when the eventual result has a different URL from the status monitor -
  create something slowly, then `GET` the thing that was created.

There is no default, deliberately. A helper that picked silently would pick `Location` for everybody,
and the modules that needed the other one would be the ones that never found out.

A `200` from a submit endpoint is the synchronous fast path and is explicitly permitted: a client
should not poll for something that is already done. The contract requires only that such a `200` carry
a *terminal* envelope.

A `202` may carry a terminal envelope too, and that is deliberate. An earlier version of
`OperationResponses.accepted` refused one; notification showed the rule was too strong. It fans a
request out in the same transaction that accepts it, so the request operation is finished by the time
the response is written - and it answers `202` anyway, because what it created is a queued intention
and whether anything reaches anybody is decided minutes later by a provider it does not control. The
invariant that *is* load-bearing is that a `202` names a status resource and the caller chose which
header names it. A terminal envelope still owes its evidence, so a `202` saying `SUCCEEDED` with
nothing to fetch is still refused.

### Polling

`OperationResponses.poll(...)` returns `200` and adds `Retry-After` while the operation is
non-terminal. It **refuses** a non-terminal response with no `Retry-After`, a terminal success with no
`result` and a terminal failure with no `failure` - each of those is a bug in the producer, and each
was shipped somewhere in this platform before the contract existed, so it fails on the first call in
the first test rather than in a client's integration six weeks later.

### Cancelling

`202`, not `204`. Cancellation on this platform is cooperative - the operation stops when it next
checks its `Cancellation`, on whichever instance is running it - so all a cancel endpoint can
truthfully report is that the request was recorded. A `204` claims the work has stopped, which is a lie
that clients build retry logic on top of.

Cancelling an operation that has already finished is **not** an error. A `409` there invites a retry
loop over something that will never change; `OperationResponses.cancellationRequested` returns the
current envelope with its terminal state.

### Progress

`OperationProgress` has a **nullable total**, and that is the point. A progress model that requires a
denominator forces every producer to either lie or omit progress entirely: file-ingest cannot know the
record count of a streaming file before it has read it. A null total serializes as an absent member
rather than as `"total": 0`, which a client reads as "done, and then some".

### Idempotency, which is next to this and is not this

A submit endpoint for a long-running operation should accept an `Idempotency-Key`, so a retried submit
returns the *same* operation id instead of starting a second run. What it must not do is blur two
codes that `idempotency-spring-boot-starter` is deliberately careful about:

| | meaning |
|---|---|
| `202` | I accepted new work; here is its operation id |
| `409` + `Retry-After` | your duplicate found work already in flight; nothing new was started |

A submit endpoint that answered `202` for a duplicate would be telling the caller it had started a
second run.

## Configuration

| Property | Default | What it does |
|---|---|---|
| `ludwig.web.enabled` | `true` | master switch for the whole starter |
| `ludwig.web.problem.enabled` | `true` | registers the shared advice; off leaves the pipeline usable from your own advice |
| `ludwig.web.problem.type-prefix` | `urn:ludwig:problem:` | prefix for the `type` URI; point it at your docs to make it dereferenceable |
| `ludwig.web.problem.include-instance` | `true` | sets `instance` to the request URI |
| `ludwig.web.problem.include-timestamp` | `true` | adds a `timestamp` member |
| `ludwig.web.problem.include-trace-id` | `true` | publishes the trace id in the body |
| `ludwig.web.problem.trace-id-mdc-key` | `traceId` | MDC key it is read from |
| `ludwig.web.problem.trace-id-property` | `traceId` | member name it is published as |
| `ludwig.web.problem.include-rejected-value` | `false` | echoes the submitted value in `violations` |
| `ludwig.web.problem.include-exception-message` | `false` | adds a `debug` member to 5xx problems |
| `ludwig.web.problem.advice-order` | last | order of the shared advice; see below |
| `ludwig.web.i18n.enabled` | `true` | configures `MessageSource`, `LocaleResolver`, validator |
| `ludwig.web.i18n.basenames` | `classpath:i18n/messages` | the application's own bundles |
| `ludwig.web.i18n.encoding` | `UTF-8` | bundle encoding |
| `ludwig.web.i18n.supported-locales` | `en` | languages the API answers in |
| `ludwig.web.i18n.default-locale` | `en` | what an unsupported language falls back to |
| `ludwig.web.i18n.fallback-to-system-locale` | `false` | whether the host's locale may be used |
| `ludwig.web.i18n.cache-duration` | `-1` (forever) | positive values re-read changed bundles |
| `ludwig.web.i18n.configure-validator` | `true` | binds Bean Validation to the `MessageSource` |

### Coexisting with your own advice

The shared advice registers at `Ordered.LOWEST_PRECEDENCE`, and that is load-bearing rather than
arbitrary. It handles `Exception`, and Spring returns the first advice that can handle a thrown
exception *at all* - not the one with the most specific handler for it. An advice sitting anywhere
but last would therefore swallow every exception your own `@RestControllerAdvice` was written to
render, including types it declares explicitly. Last means every other advice gets first refusal.

Ties resolve in your favour: Spring's sort is stable and autoconfiguration beans register after the
application's own, so a `@RestControllerAdvice` with no `@Order` still precedes this one. Give it an
explicit `@Order` if you would rather not depend on that.

If you want to render *everything* yourself, `ludwig.web.problem.enabled=false` removes the advice
and leaves `ProblemDetailFactory`, `ProblemMessages` and the mapper chain injectable - your advice
then has one line per handler and still gets localized text from every module's bundle.

### Two switches worth leaving alone

`include-rejected-value` and `include-exception-message` are off by default, and both are switches
rather than profile checks so that turning them on is a deployment decision someone can audit.

The field that most often fails validation is also the one most likely to hold a password, a token or
a card number - and a problem body is exactly the kind of thing that gets pasted into a ticket. For
the same reason, a 500's `detail` never quotes the exception: *"connection refused to
db-7.internal:5432"* is written for whoever operates the service. What a client gets instead is the
trace id, in the body rather than only in a header a browser console hides, which is what makes
"quote the trace id" an instruction someone can actually follow.

## Security notes

- The advice catches `AccessDeniedException` before Spring Security's `ExceptionTranslationFilter`
  can, which is deliberate - that filter is not in the path for an exception thrown inside the
  dispatch. A 401 rendered this way carries no `WWW-Authenticate` header; for a browser-facing API
  that is usually what you want, since the browser's reaction to that header is a native credential
  prompt no session-based UI asks for.
- 401 and 403 answers say only that access was denied. Distinguishing "you lack the editor role" from
  "that record belongs to another tenant" turns an endpoint into an oracle for enumerating records;
  the specifics belong in an audit log keyed by subject.
- If `security-spring-boot-starter` is present, it contributes a mapper so these come back under
  `ludwig.security.error.*` - the same codes its filter-chain handlers write, so a client never has
  to know which layer denied it.

## Non-web modules

`LocalizedException`, `ProblemStatus`, `ProblemMessages` and the mapper chain have no Spring MVC
dependency, and the MVC half of the autoconfiguration is conditional on a servlet web application. A
batch job or a Kafka consumer can throw and render the same errors - into a reply message, a log, a
dead-letter record - without Spring MVC on its classpath.

## Build note

This starter's behaviour depends on one compiler flag: `-parameters`, set in the root pom. Spring
reads method parameter names to bind a `@RequestParam` without repeating the name in the annotation,
and a parameter-level constraint violation can only name the parameter it rejected if those names are
in the bytecode - without the flag, a 400 reports the offending input as `arg0`.

## Modules that contribute to this pipeline

| Module | Contributes |
|---|---|
| [`odata-filter-spring-boot-starter`](../../sources/odata-filter-spring-boot-starter/README.md) | `ODataFilterProblemMapper` + `ludwig.odata.error.*` text; its own advice stands down when this starter is present |
| [`security-spring-boot-starter`](../../sources/security-spring-boot-starter/README.md) | `SecurityProblemMapper` + the `ludwig.security.error.*` bundle its filter-chain handlers already used |
| [`db-core`](../../sources/db-core/README.md) | `DbCoreProblemMapper` + `ludwig.db.error.*` text - notably `EntityNotFoundException` as a 404 rather than a 500 |

## Package layout

| Package | What's in it |
|---|---|
| `problem` | `LocalizedException`, `ProblemStatus`, `ProblemDefinition`, `ProblemCodes`, `Violation` - the vocabulary, none of it MVC-dependent |
| `problem` (pipeline) | `ProblemMessages`, `ProblemMessageBundle`, `ExceptionProblemMapper`, `ProblemMapperRegistry`, `ProblemDetailFactory` |
| `problem.mapper` | the mappers shipped here, each conditional on the class it maps |
| `web` | `ApiExceptionHandler`, `PageResponse` |
| `operation` | the long-running-operation contract: `OperationStatus`, `OperationResponse`, `OperationProgress`, `OperationResult`, `OperationFailure`, `Cancellation`, `OperationHeaders`, `OperationLocationHeader`, `OperationResponses` |
| `trace` | `TraceIdProvider`, `MdcTraceIdProvider` |
| `config` | the three autoconfigurations and `WebCoreProperties` |
