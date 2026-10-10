## ADDED Requirements

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
