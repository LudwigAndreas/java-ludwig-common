# The ProblemDetail pipeline

## Purpose
This platform answers every error with one RFC 9457 `ProblemDetail`, produced by one advice in
`web-core-spring-boot-starter`. A module teaches that pipeline about its exceptions; it does not
ship a second one.
## Requirements
### Requirement: A module contributes mappers, never its own advice
A module SHALL extend the shared pipeline by registering an `ExceptionProblemMapper` bean, and
SHALL NOT ship its own `@RestControllerAdvice`.

#### Scenario: A module needs an unmapped exception rendered as a problem
- **WHEN** a module has an exception the pipeline has never heard of
- **THEN** it registers one `ExceptionProblemMapper` bean — `ExceptionProblemMapper.forType(...)`
  covers the common case — and the shared advice renders it

#### Scenario: A module's code runs outside per-handler exception handling
- **WHEN** a module's error path is a servlet filter, which Spring's per-handler exception
  handling never reaches — as `idempotency-spring-boot-starter`'s HTTP filter is
- **THEN** it still produces the same document shape by invoking `web-core`'s own
  `ProblemDetailFactory` and message bundles directly, rather than by shipping an advice

### Requirement: Mapper precedence lets an application override a library
Mappers SHALL be consulted in `Ordered` order with the first match winning. A library's mappers
SHALL register at `ExceptionProblemMapper.DEFAULT_MODULE_ORDER`.

#### Scenario: An application wants different rendering for a library's exception
- **WHEN** an application registers a mapper for an exception a library already maps, at the
  default order of 0
- **THEN** the application's mapper wins, without the application having to know what order
  number the library picked

### Requirement: The registry walks the exception's cause chain
Mapper resolution SHALL walk the thrown exception's causes, with shallower causes winning.

#### Scenario: A mapped exception is wrapped by a framework layer
- **WHEN** a deliberate 409 is thrown and a JPA flush, a transaction manager or a proxy wraps it
- **THEN** it still renders as a 409 rather than degrading to a 500, because the chain is walked

#### Scenario: Both a wrapper and its cause are mapped
- **WHEN** an exception and its cause both have mappers
- **THEN** the wrapper's mapper wins, because shallower causes win

### Requirement: The shared advice registers last
The shared advice SHALL register at `Ordered.LOWEST_PRECEDENCE`.

#### Scenario: An application ships its own advice alongside the shared one
- **WHEN** an application defines a `@RestControllerAdvice` with no explicit `@Order`
- **THEN** it still precedes the shared advice. The shared advice handles `Exception`, and Spring
  returns the first advice that can handle a thrown exception *at all* — not the one with the most
  specific handler — so an advice anywhere but last would swallow every exception the
  application's advice was written to render

### Requirement: A problem body never leaks operational or user detail by default
`include-rejected-value` and `include-exception-message` SHALL be off by default, and SHALL be
switches rather than profile checks so that enabling them is an auditable deployment decision. A
500's `detail` SHALL NOT quote the exception message.

#### Scenario: A validation failure is rendered with defaults
- **WHEN** a request is rejected by Bean Validation and `include-rejected-value` is off
- **THEN** the rejected value is not in the response body. The field that most often fails
  validation is also the one most likely to hold a password, a token or a card number, and a
  problem body is exactly what gets pasted into a ticket

#### Scenario: An internal error is rendered
- **WHEN** a request fails with an unmapped exception
- **THEN** the body carries the trace id — in the body, not only in a header a browser console
  hides — and not the exception message, so that "quote the trace id" is an instruction a caller
  can actually follow

### Requirement: PAT failures enter the existing pipeline as contributed mappers

`pat-spring-boot-starter` SHALL contribute exception mappers into `web-core`'s single RFC 9457
pipeline and SHALL NOT declare a `@RestControllerAdvice` of its own. Every PAT problem document's
`title` and `detail` SHALL resolve from the module's own i18n bundle, present in both locales with
identical key sets.

#### Scenario: A PAT management request is rejected
- **WHEN** issuance is refused for a lifetime beyond the ceiling, an empty scope set, a scope the
  owner does not hold, or an owner the caller may not act for
- **THEN** the response is a localized `ProblemDetail` from the shared pipeline, with a distinct
  `type` per failure so a client can branch on it without parsing prose

#### Scenario: The module is added to an application that already has its own advice
- **WHEN** the application declares its own advice or its own mapper for the same exception
- **THEN** the application's mapper wins, by the existing precedence requirement - adding this module
  does not change how a consumer overrides a library

### Requirement: A PAT-credentialed caller on the management surface is `403`, not `401`

A request authenticated by a PAT-derived assertion that reaches a PAT management endpoint SHALL
produce a `403` with a problem `type` distinct from an ordinary authorization denial.

The caller is authenticated and their identity is established; what is refused is the *credential*.
A `401` would tell a client to re-authenticate, which they can do successfully and still be refused -
a loop. The distinct type exists so a client library can report "this endpoint cannot be used with a
token" rather than "access denied", which is the difference between a five-minute fix and a support
ticket.

#### Scenario: A PAT-backed caller calls the issuance endpoint
- **WHEN** the request's credential is a PAT and the endpoint is a PAT management endpoint
- **THEN** the response is `403` with the credential-not-permitted problem type, and its `detail`
  names the restriction in the caller's locale

#### Scenario: The same caller lacks the authority as well
- **WHEN** the caller would also fail ordinary authorization
- **THEN** the credential refusal is what is reported, because it is evaluated first and is the
  actionable one - telling the caller to acquire an authority they cannot use with this credential
  would send them down the wrong path

### Requirement: An exchange failure body carries no reason

The problem document returned by a failed token exchange SHALL be identical for every cause, and
SHALL NOT carry a field, code or message distinguishing an unknown key id from a bad secret, a failed
checksum, a revoked token, an expired token or a refused source.

This is a deliberate departure from the pipeline's usual helpfulness and the departure is the point.
The pipeline's existing requirement that a problem body never leaks operational detail by default is
the same instinct; here it is absolute, because any distinguishable failure on this endpoint is an
oracle for an attacker testing a credential guess. The reason is tagged in metrics and recorded in
audit, where it costs the defender nothing.

#### Scenario: Two different exchange failures are compared
- **WHEN** an unknown key id and a known key id with a wrong secret are both presented
- **THEN** the two response bodies and statuses are identical

#### Scenario: An operator needs the reason
- **WHEN** exchange failures are examined in metrics or audit
- **THEN** the cause is present there, tagged, for every failure the uniform response concealed

### Requirement: A rejected credential has a problem type distinct from a missing authority

The pipeline SHALL define a problem `type` meaning "the presented credential was not accepted", and
that type SHALL be distinct from the type used when an authenticated caller lacks an authority. The
credential-rejected document SHALL carry the location at which the caller can manage their
credentials. Its constant SHALL live in `pat-core`, so that the edge - which returns this document on
the issuer's behalf and is not part of this repository - configures against a definition rather than
inventing one.

The two cases demand opposite actions from an automation client. A rejected credential means reissue
the token; a missing authority means request a role. A client that cannot distinguish them sends its
user down the wrong path, and for an unattended pipeline the wrong path is a silent stall.

#### Scenario: A client receives a credential rejection
- **WHEN** a PAT cannot be exchanged for any reason
- **THEN** the document carries the credential-rejected type and the management location, and
  nothing identifying which cause applied

#### Scenario: A client receives an authorization denial
- **WHEN** a PAT-backed request is authenticated but the owner lacks the required authority
- **THEN** the document carries the ordinary authorization-denied type, not the credential-rejected
  one - the credential worked, and reissuing it would change nothing

#### Scenario: The type constants are consumed from outside this repository
- **WHEN** the edge is configured to render the credential-rejected response
- **THEN** the type and the management location are read from `pat-core`'s published constants, which
  both sides of the seam share for the same reason the `ludwig_pat` claim name is defined once

### Requirement: A gRPC status is a rendering of the one registry, not a second registry
A failure on a gRPC call SHALL be resolved by the same mapper registry that resolves it for HTTP and
then rendered as a gRPC status. A module SHALL NOT declare a gRPC-specific exception-to-status
mapping of its own, and a mapper contributed for HTTP SHALL take effect for gRPC with no further
registration.

#### Scenario: A module's mapped exception escapes a gRPC handler
- **WHEN** a module contributes a mapper that maps its exception to a 404 problem, and that
  exception escapes a gRPC handler in a service using the module
- **THEN** the caller receives `NOT_FOUND` carrying that problem's code, with no gRPC-specific code
  in the module

#### Scenario: An application overrides a library's mapping
- **WHEN** an application registers a higher-precedence mapper for a library's exception
- **THEN** both the HTTP response and the gRPC status for that exception follow the application's
  mapper

#### Scenario: A wrapped exception escapes a gRPC handler
- **WHEN** a mapped exception is wrapped by a framework exception before escaping a gRPC handler
- **THEN** the status is the one the wrapped exception's mapping yields, as it is for HTTP

### Requirement: A gRPC failure leaks no more than an HTTP one
A gRPC status SHALL NOT carry an exception message, class name or stack trace for an unmapped
failure, and SHALL carry field values in a violation only where the HTTP rendering would.

#### Scenario: An internal error is rendered over gRPC
- **WHEN** an unmapped exception escapes a gRPC handler
- **THEN** the status is `INTERNAL` and its description and details contain the correlation id and
  neither the exception's message nor its type

### Requirement: A module ships no fallback advice for the pipeline's absence
A module whose failures can reach a web response SHALL depend on `web-core-spring-boot-starter`
non-optionally and contribute its mappers and bundle unconditionally. It SHALL NOT ship a
`@RestControllerAdvice` for when that starter is absent.

#### Scenario: odata-filter in a servlet application
- **WHEN** a servlet application uses `odata-filter-spring-boot-starter`
- **THEN** its query errors are rendered by the shared pipeline through `ODataFilterProblemMapper`,
  and the context contains no advice of that module's own

#### Scenario: A module adds an advice of its own
- **WHEN** a class in `ru.ludwigandreas.odatafilter` is annotated `@RestControllerAdvice`
- **THEN** that module's architecture test fails the build

