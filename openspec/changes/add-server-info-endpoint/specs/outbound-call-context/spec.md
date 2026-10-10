## Purpose

Defines which ambient identity - correlation id, trace id, acting principal - an outbound HTTP call
made through the platform's rest-client starter carries, and guarantees that exactly one source of
that identity is in effect however the service's starters are combined.

## ADDED Requirements

### Requirement: Exactly one call context source is in effect

A service that includes the rest-client starter SHALL start with exactly one source of outbound call
context, whichever other platform starters it includes. Including the observability starter beside
the rest-client starter SHALL NOT prevent the service from starting.

#### Scenario: Both starters are present

- **WHEN** a service includes the rest-client starter and the observability starter, and declares no
  call context source of its own
- **THEN** the service starts
- **AND** the single call context source in effect is the platform's, drawing the correlation id from
  the observability starter

#### Scenario: Only the rest-client starter is present

- **WHEN** a service includes the rest-client starter without the observability starter
- **THEN** the service starts
- **AND** the single call context source in effect carries no ambient identity

#### Scenario: Observability is present but switched off

- **WHEN** a service includes both starters and sets `ludwig.observability.enabled=false`
- **THEN** the service starts
- **AND** the single call context source in effect carries no ambient identity

### Requirement: An application-declared source replaces the platform's

Where the application declares its own call context source, that source SHALL be the only one in
effect. Neither the platform source nor the no-identity fallback SHALL be registered beside it.

#### Scenario: The application supplies its own source

- **WHEN** a service that includes both starters declares its own call context source
- **THEN** the service starts
- **AND** outbound clients are built with the application's source and no other
