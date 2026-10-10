# The service log stream

## Purpose

Defines the one console log stream every Ludwig service writes: that a single module owns it, how the
human-readable and machine-readable formats are selected, and which identity and correlation fields every
log event carries regardless of which format is in use.

## Requirements

### Requirement: One module owns the log output pipeline

Exactly one module SHALL shape the platform's log output. No other module SHALL supply a log encoder or
layout implementation.

A second encoder is the same defect as a second audit sink or a second operation status enum: two field
vocabularies that are discovered to disagree only once both are deployed, at which point the log
aggregator's index template already has the wrong mapping for one of them.

A deployment MAY author its own logging-framework configuration, and the owning module SHALL leave an
appender so configured exactly as it found it. The same holds for a pattern a service chose through the
framework's own console-pattern or file-pattern property: the human-readable format is not installed over
it. This is an escape hatch, not a second mechanism: the
service is choosing its own output rather than publishing a competing field vocabulary for other modules
to write through, and silently overriding it would change output shape in the one place a surprise is
hardest to notice — the lines still appear, in the wrong form, somewhere else.

#### Scenario: A second encoder fails the build

- **WHEN** a module other than the owning module declares a type implementing the logging framework's
  encoder or layout interface
- **THEN** the architecture rules fail the build and name the offending type

#### Scenario: A service needs no logging configuration of its own

- **WHEN** a service adds the owning module as a dependency and configures nothing
- **THEN** its log stream carries the mandatory fields defined by this capability

#### Scenario: A hand-authored appender configuration is left alone

- **WHEN** a service configures an appender with an encoder it supplied itself
- **AND** the structured format is otherwise selected
- **THEN** that appender's encoder is not replaced

#### Scenario: A pattern the service chose is left alone

- **WHEN** a service sets the framework's console-pattern property
- **AND** the human-readable format is selected
- **THEN** the console appender keeps the pattern the service set

### Requirement: Logs are written to standard output

Log events SHALL be written to standard output, not standard error, in both formats.

#### Scenario: Events reach stdout

- **WHEN** a service logs an event at any severity, including ERROR
- **THEN** the event is written to standard output

### Requirement: The console format default depends on the active profile

The console format SHALL default to the machine-readable structured format, except when the `local`
profile is active, where it SHALL default to the human-readable format.

This SHALL be published as a default at the lowest precedence, so an explicit setting in application
configuration, an environment variable or a system property overrides it without the service needing to
know the owning module exists.

Both halves matter. A structured default means no service can reach production writing text that the
aggregator cannot parse, which is the failure a switched-off-by-default structured format guarantees
eventually. A human-readable default under `local` means no developer reads JSON on a workstation, and
removes the copy-paste override every new service otherwise has to remember.

#### Scenario: Structured by default

- **WHEN** a service starts with no logging format configured and the `local` profile is not active
- **THEN** each log event is written as one structured record per line

#### Scenario: Human-readable under the local profile

- **WHEN** a service starts with no logging format configured and the `local` profile is active
- **THEN** each log event is written in the human-readable format

#### Scenario: An explicit setting wins over the default

- **WHEN** a service sets the log format explicitly and the `local` profile is active
- **THEN** the explicit setting is honoured

#### Scenario: An existing manual override is unaffected

- **WHEN** a service already configures the human-readable format for its `local` profile by hand
- **THEN** its behaviour is unchanged by the introduction of the profile-dependent default

### Requirement: The format is installed before the application logs anything

The chosen format SHALL be in effect for the first log event the process emits, including the framework
banner, the active-profile list, database migration output and any bootstrap failure.

A stream that is text for its first lines and structured thereafter is one most log shippers handle by
dropping whichever half they were not configured for — and the lines lost are the startup and bootstrap
lines, which are the ones needed when a service fails to start.

The framework banner is the one thing that never passes through the logging system: it is printed
straight to standard output, so no format can be installed on it. When the structured format is selected
the banner SHALL therefore default to off, published at the lowest precedence like every other default,
so an explicit banner setting still wins. Under the human-readable format the banner is left alone.

#### Scenario: Startup output is already in the selected format

- **WHEN** a service starts with the structured format selected
- **THEN** every line it writes, from the first, is a structured record
- **AND** no line is written in the human-readable format, the banner included

#### Scenario: An explicit banner setting wins

- **WHEN** a service sets the banner mode explicitly with the structured format selected
- **THEN** the explicit setting is honoured

#### Scenario: A bootstrap failure is in the selected format

- **WHEN** a service fails during startup with the structured format selected
- **THEN** the failure and its stack trace are written as structured records

### Requirement: Every log event carries service and build identity

Every log event SHALL carry the service name, the application version and the abbreviated commit id, in
both formats. The deployment environment and the instance identifier SHALL be carried when available.

The commit id is included because it is what turns a log line into something that can be resolved to
source. It adds no field varying independently of one already present: the commit and the version move
together, so the series it can produce are the releases that already exist.

#### Scenario: Identity on a structured event

- **WHEN** a service logs an event with the structured format selected
- **THEN** the record carries fields for the service name, the application version and the abbreviated
  commit id

#### Scenario: Identity on a human-readable event

- **WHEN** a service logs an event with the human-readable format selected
- **THEN** the line carries the service name, the application version and the abbreviated commit id

#### Scenario: Environment and instance on a human-readable event

- **WHEN** a service with a resolved deployment environment and instance identifier logs an event with
  the human-readable format selected
- **THEN** the line carries both

#### Scenario: Absent identity is omitted, not faked

- **WHEN** a service runs from an artifact with no build provenance available, as in an IDE
- **THEN** the commit id field is omitted from the event
- **AND** it is not written as a placeholder value

### Requirement: Correlation metadata survives a format change

The correlation id SHALL be carried on every log event in both formats, and the trace and span ids SHALL
be carried in both formats whenever they are in scope.

Switching to the human-readable format is a readability choice. It must not also be a loss of the
metadata that makes a log line findable, which is what happens when the human-readable path is the
framework's default pattern.

#### Scenario: Correlation id on a human-readable event

- **WHEN** a request carrying a correlation id is handled with the human-readable format selected
- **THEN** every line logged during that request carries the correlation id

#### Scenario: Trace and span ids on a human-readable event

- **WHEN** a request is handled within a sampled trace with the human-readable format selected
- **THEN** every line logged during that request carries the trace id and the span id

#### Scenario: Unsampled request still carries a correlation id

- **WHEN** a request is handled that was not sampled for tracing
- **THEN** its log events carry a correlation id
- **AND** in the structured format the trace and span id fields are omitted rather than written empty
- **AND** in the human-readable format the correlation, trace and span ids keep fixed positions, so the
  trace and span positions are empty

### Requirement: Each supported field-name set names the build identity field

Every field-name set the structured format offers SHALL define a name for the commit id field, following
that set's own naming convention.

The point of offering more than one field-name set is that an aggregator ingests the stream with no
ingest-time mapping. A field present in one set and missing from another defeats that for whichever set
omits it.

#### Scenario: The commit field is named in every field set

- **WHEN** the structured format is configured with any supported field-name set
- **THEN** the commit id is written under a field name belonging to that set's convention

### Requirement: Application and build identity are logged once at startup

A service SHALL emit one log event at startup carrying its full application and build identity: the
service name, version, environment, instance, commit id, branch, build timestamp, CI build number and
dirty indicator, with unavailable fields omitted.

The per-event fields are deliberately a subset, because a log line is written millions of times and the
startup event once. The branch, the build timestamp and the dirty indicator belong to the second.

The event SHALL state the identity in its message, so it is readable as text, and in the structured
format SHALL also carry the build fields as fields of their own, so it is a filter rather than a text
search.

#### Scenario: One startup identity event

- **WHEN** a service completes startup
- **THEN** exactly one log event is emitted carrying the application and build identity
- **AND** the fields that could not be resolved are omitted from it

#### Scenario: Startup event in either format

- **WHEN** a service completes startup with the human-readable format selected
- **THEN** the startup identity event is emitted and is readable as text

#### Scenario: Startup event in the structured format

- **WHEN** a service completes startup with the structured format selected
- **THEN** the startup identity event carries the full commit id, branch, build timestamp, CI build number
  and dirty indicator as separate fields, omitting those that could not be resolved

#### Scenario: A dirty build is visible at startup

- **WHEN** a service starts from an artifact built from a dirty working tree
- **THEN** the startup identity event records that the tree was dirty
