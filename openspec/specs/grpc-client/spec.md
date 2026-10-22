# grpc-client Specification

## Purpose
The behaviour of a named gRPC client a platform service configures to call another platform
service: how its configuration is inherited, the deadline every call carries, what protects the
dependency and the caller from each other, when a call is repeated, how it authenticates, and what
a caller sees when it fails.
## Requirements
### Requirement: Clients are declared by name and inherit from a defaults block
A service SHALL declare gRPC clients under `ludwig.grpc.client.clients.<name>`, each inheriting
unset values from `ludwig.grpc.client.defaults`, with nested blocks merged field by field and lists
replaced. A client's `target` and its token-relay switch SHALL never be inherited.

#### Scenario: A client overrides one field of an inherited block
- **WHEN** `defaults` sets a circuit breaker's failure threshold and window and a client sets only
  the window
- **THEN** that client uses its own window and the inherited threshold

#### Scenario: A target is placed in defaults
- **WHEN** `defaults` declares a `target`
- **THEN** startup fails with a message that a target cannot be shared

#### Scenario: The starter is present with no client
- **WHEN** an application with the client starter and no configured client starts
- **THEN** it starts and no channel is created

### Requirement: A client is consumed as a stub, a channel or through a registry, with identical behaviour
A configured client SHALL be usable as an injected generated stub bound by name, as an injected
channel qualified by name, and by lookup in a registry, and a call made through any of the three
SHALL be subject to the same deadline, resilience, authentication, metrics, logging and audit.

#### Scenario: The same call is made three ways
- **WHEN** one method is called through an injected stub, through a stub built on the injected
  channel, and through a stub built on the registry's channel
- **THEN** each call carries the client's credential and deadline and is counted in the client's
  request metric

#### Scenario: An unknown client is requested
- **WHEN** code asks the registry for a client that is not configured
- **THEN** it fails immediately with a message listing the configured client names

#### Scenario: A stub names an unconfigured client
- **WHEN** an injection point binds a stub to a client name that is not configured
- **THEN** startup fails naming the injection point and the client

### Requirement: Every call carries a deadline
A call SHALL be sent with a deadline no later than the client's configured deadline and no later
than the remaining deadline of the inbound call it is made within. A client configured with no
positive deadline SHALL fail startup.

#### Scenario: A call is made with only the configured deadline
- **WHEN** a client configured with `deadline: 3s` is called outside any inbound call
- **THEN** the server observes a deadline of at most three seconds

#### Scenario: A call is made within an inbound call that is nearly out of time
- **WHEN** a handler with 500 ms of its own deadline remaining calls a client configured with
  `deadline: 3s`
- **THEN** the outbound call carries a deadline of at most 500 ms

#### Scenario: The deadline passes
- **WHEN** the peer does not answer before the deadline
- **THEN** the caller receives a timeout failure that names the client and states the request may
  have been processed

#### Scenario: No deadline is configured
- **WHEN** neither `defaults` nor a client declares a deadline
- **THEN** the client uses the built-in default of five seconds

#### Scenario: A deadline is switched off
- **WHEN** a client declares a deadline of zero
- **THEN** startup fails naming the client

### Requirement: Resilience policies apply in a fixed order to the logical call
The rate limiter, bulkhead and circuit breaker SHALL each take one permit and record one result per
logical call regardless of how many attempts it takes, in the order rate limiter, bulkhead, circuit
breaker, retry.

#### Scenario: A call succeeds on its third attempt
- **WHEN** an idempotent call fails twice with `UNAVAILABLE` and then succeeds
- **THEN** the circuit breaker records one successful call

#### Scenario: The breaker is open
- **WHEN** a client's circuit breaker is open and a call is made
- **THEN** the caller receives a not-permitted failure, no request reaches the peer, and the
  not-permitted counter for policy `circuit-breaker` increases

#### Scenario: The peer rejects a request as invalid
- **WHEN** calls fail repeatedly with `INVALID_ARGUMENT`, `NOT_FOUND` or `PERMISSION_DENIED`
- **THEN** the circuit breaker does not open

### Requirement: A call is repeated only when the contract says a repeat is safe
The client SHALL retry a failed call only for a method whose contract declares it idempotent or
free of side effects, and only for retryable statuses. A call site SHALL NOT be able to opt a
method into retry.

#### Scenario: An idempotent method fails transiently
- **WHEN** a method declared `IDEMPOTENT` fails with `UNAVAILABLE` and retry is enabled
- **THEN** the call is attempted again within the deadline and the retry counter increases

#### Scenario: A method of unknown idempotency fails after the request was sent
- **WHEN** a method declared `IDEMPOTENCY_UNKNOWN` fails with `UNAVAILABLE` after the server
  received the request
- **THEN** the call is not attempted again and the caller receives the failure

#### Scenario: The server asks the client to back off
- **WHEN** a retryable failure carries a server pushback delay
- **THEN** the next attempt is not sent before that delay has elapsed

### Requirement: Clients are isolated from each other
Each named client SHALL have its own connection, its own resilience state and its own credentials,
and exhaustion or failure of one SHALL NOT affect another, including another client with the same
target.

#### Scenario: One dependency saturates
- **WHEN** one client's bulkhead is full and its breaker is open
- **THEN** a call through a different client proceeds normally

### Requirement: Authentication is selected per client and guarded
A client SHALL attach credentials according to its configured authentication type, SHALL refuse at
startup to send a credential in plaintext to a non-loopback target, and SHALL treat relay of the
caller's token as a per-client opt-in that fails closed.

#### Scenario: A machine token is used by concurrent calls
- **WHEN** many calls start at the moment a client-credentials token expires
- **THEN** exactly one new token is requested and every call uses it

#### Scenario: The peer rejects a cached token
- **WHEN** a unary call fails with `UNAUTHENTICATED` using a cached machine token
- **THEN** the token is discarded, the call is attempted once more with a new token, and a second
  `UNAUTHENTICATED` is reported as an authentication failure

#### Scenario: Token relay is configured with no inbound token
- **WHEN** a relay client is called outside an authenticated request
- **THEN** the call fails with an authentication failure and no request is sent anonymously

#### Scenario: Token relay is used while serving a gRPC call
- **WHEN** a relay client is called from a gRPC handler serving an authenticated caller
- **THEN** the outbound call carries that caller's bearer token, also when the server's principal
  type keeps no token of its own

#### Scenario: Token relay is enabled in defaults
- **WHEN** `defaults` sets the relay switch and a client does not
- **THEN** that client does not relay, and startup fails if its type is token relay

#### Scenario: A credential would travel in plaintext
- **WHEN** a client with an authentication type other than `none` targets a non-loopback address in
  plaintext
- **THEN** startup fails naming the client

### Requirement: Failures are typed and attributable
A failed call SHALL surface as a failure that identifies the client and the correlation id and
distinguishes a peer's failure answer, a passed deadline, a connection that could not be made, a
credential that could not be obtained or used, and a call this service's own policy refused. A
failure answer from a platform server SHALL carry the decoded problem.

#### Scenario: The peer answers with a platform problem
- **WHEN** the peer fails a call with a status carrying a problem code
- **THEN** the caller's failure exposes the gRPC code and a problem document with that code

#### Scenario: A dependency failure escapes a controller
- **WHEN** a peer failure of `UNAVAILABLE` escapes an HTTP controller of the calling service
- **THEN** the HTTP response is the platform's upstream-unavailable problem with status 503

#### Scenario: The calling service sent an invalid request
- **WHEN** a peer failure of `INVALID_ARGUMENT` escapes an HTTP controller of the calling service
- **THEN** the HTTP response is a 500 problem, not a 400

### Requirement: Calls are balanced per call by default
A client SHALL distribute calls across every address its target resolves to unless configured
otherwise, and SHALL warn at startup when configured to use a single address.

#### Scenario: A target resolves to three addresses
- **WHEN** a client with default configuration makes many calls to a target that resolves to three
  servers
- **THEN** each server receives calls

#### Scenario: Single-address balancing is configured
- **WHEN** a client sets `load-balancing: pick_first`
- **THEN** the application starts and logs a warning naming the client

### Requirement: Outbound calls are observable without exposing payloads
Each client SHALL publish a request timer tagged by client, service, method and status, counters for
retries, refused calls and token refreshes, and a per-client log line per call. Message payloads
SHALL NOT be logged unless enabled for that client, and payload logging SHALL be refused under a
production profile.

#### Scenario: A call completes
- **WHEN** a call through client `notifications` to `NotificationService/Send` returns `OK`
- **THEN** the request timer records it with those four tags and one log line names the method,
  status and duration and contains no field of the request message

#### Scenario: Payload logging is enabled in production
- **WHEN** a client sets payload logging and a production profile is active
- **THEN** startup fails naming the client

### Requirement: Outbound calls are audited through the platform's one sink
With audit enabled for a client, each call SHALL be recorded to the platform audit sink in the
outbound-call category with no message payload, sampling SHALL apply to successful calls only, and
a call refused by this service's own policy SHALL be recorded with a denied outcome.

#### Scenario: A sampled-out success and a failure
- **WHEN** a client audits with a sampling probability of zero and makes one successful and one
  failed call
- **THEN** exactly one audit event is recorded, for the failed call

#### Scenario: A call is refused by the breaker
- **WHEN** an audited client's call is refused because its breaker is open
- **THEN** an audit event with a denied outcome is recorded

### Requirement: An inconsistent configuration fails startup, completely
A configuration that is valid field by field and wrong as a whole SHALL prevent startup, and every
problem found SHALL be reported together.

#### Scenario: Three clients are each misconfigured
- **WHEN** one client has no target, another has a breaker whose minimum number of calls exceeds
  its window, and a third names an unknown authentication type
- **THEN** startup fails once with a message that lists all three problems

