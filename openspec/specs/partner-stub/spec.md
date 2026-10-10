# Partner stubs

## Purpose
Defines the HTTP stand-in for one partner of a service: how it is wired to the service's real
client, shared between integration tests and the local launcher, and made to fail on demand, so
that a partner's outage can be reproduced locally through the same client code production runs.

## Requirements

### Requirement: A stub is an HTTP server for one partner
A partner stub SHALL be a real HTTP server standing in for exactly one partner of the service,
identified by the stub's name, and the service SHALL reach it through its real client, so pool
limits, timeouts, retry and the circuit breaker all apply. Unless the stub says otherwise its name
is a rest-client name and it stands in for that client.

#### Scenario: A stubbed client is called
- **WHEN** the service calls a `@LudwigRestClient("suppliers")` interface and a stub for
  `suppliers` is registered
- **THEN** the request arrives at the stub over HTTP, and the stub's recorded requests contain it
  with the headers the real client sent

#### Scenario: Two stubs claim one name
- **WHEN** two registered stubs declare the same name
- **THEN** start-up fails naming it and both stub types

### Requirement: The stub's address is wired by name
For a stub that stands in for a rest client, the module SHALL set
`ludwig.rest-client.clients.<name>.base-url` to the stub's address before the service's
rest-client configuration is validated, overriding any value from the service's own configuration
files. A stub SHALL be able to contribute further property overrides of its own.

#### Scenario: The service's configuration names a production URL
- **WHEN** `application.yml` sets `ludwig.rest-client.clients.suppliers.base-url` to a remote host
  and a stub for `suppliers` is registered
- **THEN** the client calls the stub and no request leaves the machine

#### Scenario: A client authenticates against a token endpoint
- **WHEN** a stub also stands in for its partner's token endpoint and contributes that property
- **THEN** the client obtains its token from the stub

### Requirement: One stub definition serves tests and the launcher
A stub definition SHALL be usable unchanged by an integration test and by a local application.

#### Scenario: A stub is used in an integration test
- **WHEN** an integration test registers a service's stubs
- **THEN** they are started before the context, wired by name, and every stub is returned to its
  healthy answers with its recorded requests cleared after each test method

#### Scenario: The same stub is used by the local application
- **WHEN** the local application registers the same stubs
- **THEN** they answer with the same healthy responses and stay running until the application
  stops

### Requirement: A stub's port is dynamic unless configured
A stub SHALL listen on an operating-system-allocated port unless
`ludwig.local.stubs.<name>.port` is set, in which case it SHALL listen on that port.

#### Scenario: No port is configured
- **WHEN** two modules' suites start a stub for the same client name at the same time
- **THEN** both start, on different ports

#### Scenario: A port is configured and taken
- **WHEN** `ludwig.local.stubs.suppliers.port` names a port another process holds
- **THEN** start-up fails naming the client and the port

### Requirement: A stub can be made to fail
A stub SHALL support these faults, each for the whole stub or for a path pattern, and each
reversible without restarting the service: unavailable (HTTP 503), slow (a fixed delay), connection
reset, empty response, and refused connection. One stub's fault SHALL NOT affect another stub.

#### Scenario: A stub is made slow
- **WHEN** a stub is given a delay longer than its client's read timeout
- **THEN** the client's call fails as a timeout through the client's own error translation, and the
  client's retry policy runs

#### Scenario: A stub is made unavailable while others are healthy
- **WHEN** one of two stubs is made unavailable
- **THEN** calls to it receive 503 and calls to the other stub succeed

#### Scenario: A fault is scoped to a path
- **WHEN** a connection-reset fault is applied to one path pattern of a stub
- **THEN** requests matching the pattern fail and other requests to that stub get healthy answers

#### Scenario: A fault is cleared
- **WHEN** a stub's fault is cleared
- **THEN** the next request receives the healthy answer

### Requirement: A fault can be switched from three places
A fault SHALL be switchable from test code, from a start-up property
(`ludwig.local.stubs.<name>.fault`), and over HTTP on the stub's control URL while the service is
running. All three SHALL produce the same behaviour. The one exception is refused connection, which
closes the stub's listener and with it the control URL: it SHALL be switchable from test code and
from the start-up property only.

#### Scenario: A fault is set at start-up
- **WHEN** the local application starts with `ludwig.local.stubs.suppliers.fault=unavailable`
- **THEN** the first call to `suppliers` receives 503 and the start-up summary shows the fault

#### Scenario: A fault is set while paused at a breakpoint
- **WHEN** a developer sends the documented request to a stub's control URL while the service is
  suspended in a debugger, then resumes
- **THEN** the resumed call observes the fault

#### Scenario: A fault is cleared over HTTP
- **WHEN** a developer sends the documented clearing request, which carries no state, to the same
  control URL
- **THEN** the next call receives the healthy answer

#### Scenario: An unknown fault name is configured
- **WHEN** `ludwig.local.stubs.suppliers.fault` names a fault that does not exist
- **THEN** start-up fails listing the valid names

### Requirement: Every rest client has a stub or an explicit pass-through
For a service that has a local application, every rest-client name SHALL have either a registered
stub or an explicit pass-through declaration made beside the service's stubs. The omission SHALL
fail the build and SHALL fail the launcher. For a single launch, a stubbed client SHALL be
switchable to pass-through with `ludwig.local.stubs.<name>.mode=passthrough`.

#### Scenario: A new client interface is added without a stub
- **WHEN** a service that has a local application gains a `@LudwigRestClient` interface and no stub
  or pass-through is declared for its name
- **THEN** the service's architecture test fails naming the interface and the client name

#### Scenario: A client configured only by name has no stub
- **WHEN** the rest-client registry reports a name that has neither a stub nor a pass-through
- **THEN** the local application refuses to start, naming the client

#### Scenario: A client is declared pass-through
- **WHEN** a service declares the `billing` client pass-through beside its stubs
- **THEN** the client keeps its configured base URL, the architecture test and the launcher both
  accept it, and the start-up summary lists it as pass-through with the address it calls

#### Scenario: A stubbed client is switched to pass-through for one launch
- **WHEN** the local application starts with `ludwig.local.stubs.suppliers.mode=passthrough` and a
  stub for `suppliers` is registered
- **THEN** that stub is not started, the client keeps its configured base URL, and the start-up
  summary lists it as pass-through

#### Scenario: A stub is registered for a client that does not exist
- **WHEN** a stub names a client that nothing but the stub itself configures
- **THEN** the local application refuses to start, naming the stub, so a renamed client cannot
  leave a dead stub behind

### Requirement: A stub can stand in for a partner that is not a rest client
A stub SHALL be able to name the property its address is published under, in place of a
rest-client name, and SHALL be able to publish no address at all. A stub that names neither SHALL
default to its rest client's `base-url`, so an existing rest-client stub is unchanged.

#### Scenario: A partner is addressed by the service's own property
- **WHEN** a stub names `ludwig.notification.channels.chat.base-url` as its address property
- **THEN** that property resolves to the stub's address ahead of every other property source, and
  the service's own HTTP client for that partner calls the stub

#### Scenario: A stub publishes no address
- **WHEN** a stub declares that it has no address property
- **THEN** it is started, no property is overridden for it, and its address is available to
  fixtures and tests by the stub's name

#### Scenario: A property-addressed stub overrides a property nothing else sets
- **WHEN** a stub names an address property that no other property source configures
- **THEN** the local application refuses to start, naming the stub and the property, so a renamed
  property cannot leave a dead stub behind

#### Scenario: A stub that is not a rest client is launched without the rest-client starter
- **WHEN** a service without the rest-client starter launches with stubs addressed by properties
- **THEN** the application starts and the client-implies-stub check does not run

### Requirement: Faults apply to every kind of stub
The fault switch, its three control surfaces, the per-stub properties and the reset after each
test method SHALL behave identically for a stub addressed by a rest-client name, by another
property, or by none.

#### Scenario: A property-addressed stub is made unavailable
- **WHEN** a stub addressed by a service property is given the unavailable fault
- **THEN** the service's call to that partner receives HTTP 503

### Requirement: A stub can add lines to the start-up summary
A stub SHALL be able to contribute lines that are printed under its entry in the start-up summary.

#### Scenario: A stub prints a ready-made command
- **WHEN** a stub contributes a command line built from its own address
- **THEN** the start-up summary shows it under that stub, after the fault-switch lines
