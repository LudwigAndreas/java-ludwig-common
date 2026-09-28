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
