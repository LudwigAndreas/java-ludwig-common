# grpc-server Specification

## Purpose
The behaviour of a platform service that exposes gRPC: when it listens, which calls it refuses
before a handler runs, what a handler can rely on being present, the limits that hold without
configuration, and how it reports health and shuts down.
## Requirements
### Requirement: The server listens only when there is something to serve
With the server starter present, an application SHALL open the gRPC port only if it declares at
least one gRPC service, and SHALL start normally otherwise.

#### Scenario: The starter is present with no service
- **WHEN** an application with the server starter and no gRPC service starts
- **THEN** it starts and nothing listens on the gRPC port

#### Scenario: A service is declared
- **WHEN** an application declares one gRPC service and starts
- **THEN** the service is reachable on `ludwig.grpc.server.port`, 9090 by default

#### Scenario: The starter is switched off
- **WHEN** an application with declared services starts with `ludwig.grpc.server.enabled=false`
- **THEN** it starts and nothing listens on the gRPC port

### Requirement: Calls are authenticated unless explicitly permitted
The server SHALL reject a call that carries no valid credential with `UNAUTHENTICATED` before the
handler runs, for every method except the health service, an enabled reflection service and methods
listed in `ludwig.grpc.server.security.permit-unauthenticated`.

#### Scenario: A call arrives with no credential
- **WHEN** a client calls a service method with no `authorization` metadata
- **THEN** the call fails with `UNAUTHENTICATED` and the handler is not invoked

#### Scenario: A call arrives with a valid token
- **WHEN** a client calls with a bearer token the service's resource-server configuration accepts
- **THEN** the handler runs with the same principal and authorities an HTTP request with that token
  would have

#### Scenario: The health service is called anonymously
- **WHEN** a client calls `grpc.health.v1.Health/Check` with no credential
- **THEN** the call succeeds

### Requirement: A server with no way to authenticate refuses to start
An application that declares a gRPC service and has no token decoder SHALL fail to start unless
authentication is disabled explicitly.

#### Scenario: Security is absent and not disabled
- **WHEN** an application with a gRPC service and no resource-server configuration starts
- **THEN** startup fails with a message naming `ludwig.grpc.server.security.enabled`

#### Scenario: Security is disabled explicitly
- **WHEN** the same application starts with `ludwig.grpc.server.security.enabled=false`
- **THEN** it starts and logs a warning that the gRPC server is unauthenticated

### Requirement: Identity, correlation and caller preferences are present where the handler runs
A handler SHALL observe the caller's principal, the correlation id and the caller's locale and zone
on the thread that executes it, whichever thread that is, and none of them SHALL remain on that
thread after the call's callback returns.

#### Scenario: A handler runs on a different thread from the one that accepted the call
- **WHEN** a call is served by an executor with several threads and the handler reads the current
  principal, the correlation id and the current user preferences
- **THEN** all three are the caller's

#### Scenario: A thread is reused by another caller
- **WHEN** a thread that served one caller next serves a call from a different caller
- **THEN** the handler observes only the second caller's principal, correlation id and preferences

#### Scenario: A caller sends no locale
- **WHEN** a call carries no locale metadata
- **THEN** the handler's preferences are resolved from the platform's remaining preference sources,
  as for an HTTP request with no `Accept-Language`

### Requirement: Failures are rendered through the platform's problem mapping
An exception escaping a handler SHALL reach the caller as the gRPC status the platform's problem
mapping yields, and an exception no mapper claims SHALL reach the caller as `INTERNAL` carrying the
correlation id and no exception message or stack trace.

#### Scenario: A mapped exception escapes a handler
- **WHEN** a handler throws an exception a contributed problem mapper maps to a 409 problem
- **THEN** the caller receives `ABORTED` with that problem's code in the error details

#### Scenario: An unmapped exception escapes a handler
- **WHEN** a handler throws an exception with the message `password=hunter2`
- **THEN** the caller receives `INTERNAL`, the status description and details do not contain
  `hunter2`, and they do contain the correlation id

### Requirement: Limits hold without configuration
The server SHALL bound inbound message size, inbound metadata size, concurrent calls per connection,
connection age and handler concurrency by default, and SHALL answer a call it cannot accept with
`RESOURCE_EXHAUSTED` rather than queueing it without bound.

#### Scenario: A message exceeds the default size
- **WHEN** a client sends a request message larger than 4 MiB to a server with default configuration
- **THEN** the call fails with `RESOURCE_EXHAUSTED` and the handler is not invoked

#### Scenario: Handler capacity is exhausted
- **WHEN** every handler thread is busy and the bounded queue is full
- **THEN** a further call fails with `RESOURCE_EXHAUSTED`

#### Scenario: A connection outlives the maximum age
- **WHEN** a connection has been open longer than `max-connection-age`
- **THEN** the server asks the client to reconnect and calls in flight complete within the grace
  period

### Requirement: Health follows the application's readiness
The server SHALL expose the standard gRPC health service, and its answer SHALL agree with the
application's readiness state.

#### Scenario: The application is ready
- **WHEN** the application's readiness state is accepting traffic
- **THEN** `grpc.health.v1.Health/Check` answers `SERVING`

#### Scenario: The application is shutting down
- **WHEN** shutdown begins
- **THEN** the health service answers `NOT_SERVING` before the server stops accepting calls

### Requirement: Shutdown drains calls in flight
On shutdown the server SHALL stop accepting new calls, let calls in flight finish for up to
`ludwig.grpc.server.shutdown-grace`, and then cancel the remainder.

#### Scenario: A call is in flight when shutdown begins
- **WHEN** shutdown begins while a call that completes within the grace period is running
- **THEN** that call completes successfully and a call started after shutdown began is refused

### Requirement: Reflection is off by default and refused in production
Server reflection SHALL be disabled unless enabled explicitly, and an application SHALL fail to
start with reflection enabled while a production profile is active.

#### Scenario: Reflection is enabled under a production profile
- **WHEN** an application starts with `ludwig.grpc.server.reflection.enabled=true` and the `prod`
  profile active
- **THEN** startup fails with a message naming the property and the profile

### Requirement: Transport is encrypted unless plaintext is doubly permitted
The server SHALL refuse to listen in plaintext on a non-loopback address unless both the server's
own plaintext switch and the top-level `ludwig.grpc.allow-plaintext` are set, and SHALL refuse
plaintext under a production profile regardless.

#### Scenario: Plaintext is requested with one switch
- **WHEN** the server is configured for plaintext without `ludwig.grpc.allow-plaintext=true`
- **THEN** startup fails with a message naming both switches

#### Scenario: Plaintext is requested in production
- **WHEN** both switches are set and a production profile is active
- **THEN** startup fails

### Requirement: An opted-in unary method replays instead of repeating
With the platform's idempotency module present, a unary method that opts in SHALL execute at most
once per idempotency key and scope, and a repeat with the same key and the same request SHALL
receive the original response without the handler running again.

#### Scenario: A call is repeated with the same key and request
- **WHEN** a client repeats a completed opted-in call with the same idempotency key and an identical
  request message
- **THEN** it receives the original response, the reply is marked as replayed, and the handler ran
  once

#### Scenario: A key is reused with a different request
- **WHEN** a client reuses an idempotency key with a different request message
- **THEN** the call fails with the status the idempotency module's key-reuse problem maps to and
  the handler does not run

#### Scenario: A required key is missing
- **WHEN** a method that requires a key is called without one
- **THEN** the call fails with `INVALID_ARGUMENT`

#### Scenario: No idempotency store is available
- **WHEN** an application declares a method that opts in and has no idempotency store configured
- **THEN** startup fails with a message naming the method

### Requirement: A call without a deadline is visible
The server SHALL count every call that arrives with no deadline, tagged by service and method.

#### Scenario: A caller sends no deadline
- **WHEN** a client calls a method without setting a deadline
- **THEN** the call is served and the no-deadline counter for that method increases by one

### Requirement: Administrative operations are enforced on gRPC methods
A method of a `@LudwigGrpcService` annotated `@AdminAccess(<operation>)` SHALL be invoked only when the authenticated
caller is admitted to the administrative surface and holds that operation. Otherwise the call SHALL fail with
`PERMISSION_DENIED` before the handler runs, and the denial SHALL be audited as an administrative denial. A
privileged operation SHALL require non-blank `admin-reason` call metadata of at most 500 characters, else
`INVALID_ARGUMENT`. The handler SHALL run with the administrative invocation bound, so its audit events record the
operation and reason. Startup SHALL fail, naming the method, when an annotated method names an operation absent
from the catalog, names an operation that requires four-eyes approval, or exists while no administrative surface is
configured. Methods without the annotation are unaffected.

#### Scenario: Automation calls an administrative method it is granted
- **WHEN** a service principal holding `catalog.operate` calls a method annotated `@AdminAccess("catalog.operate")`
- **THEN** the handler runs, with the invocation naming `catalog.operate` bound

#### Scenario: A caller without the operation
- **WHEN** a caller holding only `catalog.read` calls the same method
- **THEN** the call fails with `PERMISSION_DENIED`, the handler is not invoked, and the denial is audited

#### Scenario: A privileged call without a reason
- **WHEN** a caller holding `catalog.privileged` calls a privileged method with no `admin-reason` metadata
- **THEN** the call fails with `INVALID_ARGUMENT` and the handler is not invoked; with `admin-reason: INC-7` it runs
  with that reason bound

#### Scenario: A typo in the operation
- **WHEN** a gRPC method is annotated `@AdminAccess("catalgo.read")`
- **THEN** the application fails to start naming the method and the operation

#### Scenario: No administrative surface
- **WHEN** a gRPC method carries `@AdminAccess` and the service has no administrative surface configured
- **THEN** the application fails to start naming the method, rather than serving it to any authenticated caller

#### Scenario: An operation that requires approval
- **WHEN** a gRPC method's operation is listed in `ludwig.admin.approval.required`
- **THEN** the application fails to start naming the method, because four-eyes approval exists only over HTTP
