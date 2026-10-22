## ADDED Requirements

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
