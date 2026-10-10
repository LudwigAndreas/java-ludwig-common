# Shared test containers

## Purpose
`test-support` shares one container per image per JVM so that a suite pays for one start. This
capability states that such a container cannot be stopped by one of the application contexts that
share it.

## Requirements

### Requirement: A shared container outlives every application context
A container obtained from `Containers` SHALL keep running, on the same mapped port, after an
application context that was given it closes or fails to start.

#### Scenario: A context holding the container as a bean closes
- **WHEN** an application context that imports `PostgresContainerConfiguration` is closed while
  Testcontainers reuse is not enabled
- **THEN** the container is still running on the same mapped port, and a second context still
  open against it can run a query

#### Scenario: A context fails to load
- **WHEN** an application context that imports `PostgresContainerConfiguration` fails during
  start-up
- **THEN** the container is still running

#### Scenario: A test asks a shared container to stop
- **WHEN** code calls `stop()` or `close()` on a container obtained from `Containers`
- **THEN** the container keeps running; it is removed when the JVM exits
