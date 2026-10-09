## Purpose

A service's HTTP surface is a generated artifact committed to the tree, so that a change to it is
visible in a diff, and the build fails when the committed description no longer matches what the
service actually serves.

## ADDED Requirements

### Requirement: A service that serves HTTP publishes its OpenAPI document into the tree

Each service module SHALL have a current OpenAPI document committed at
`docs/api/<artifactId>.openapi.yaml`. The document SHALL be produced from the service's real
application context, not written or edited by hand.

#### Scenario: A reviewer reads a pull request that changes a response body

- **WHEN** a change adds, removes or retypes a field on any response a controller returns
- **THEN** the committed document under `docs/api/` changes in the same commit, so the altered HTTP
  surface is visible in the diff rather than only inside a running process

#### Scenario: The document is edited by hand

- **WHEN** somebody edits `docs/api/<artifactId>.openapi.yaml` directly
- **THEN** the next build of that service fails, because the document generated from the context no
  longer matches the committed bytes. A hand edit is indistinguishable from drift and is refused for
  the same reason

#### Scenario: A service's document is absent

- **WHEN** a service that serves HTTP has no committed document
- **THEN** its build fails on the first run of the comparison, which writes the generated document
  and reports that the committed copy is missing

### Requirement: The build fails on any divergence between the code and the committed document

The comparison SHALL run as part of `verify` for the service that owns the document, SHALL compare
the full generated document against the committed bytes, and SHALL report the command that refreshes
it. It SHALL NOT silently rewrite the committed copy during an ordinary build.

#### Scenario: An endpoint is added without refreshing the document

- **WHEN** a new handler method is added to a controller and the service is built with `verify`
- **THEN** the build fails naming the path that appeared, and the failure message states the command
  that regenerates the committed document

#### Scenario: An ordinary build is run against an up-to-date document

- **WHEN** the committed document already matches the generated one
- **THEN** the build passes and the committed file is left untouched, including its modification time,
  so a clean tree stays clean

#### Scenario: The author refreshes the document deliberately

- **WHEN** the regeneration is requested explicitly
- **THEN** the committed document is overwritten from the generated one and the build passes, leaving
  the refreshed file as a change the author commits and a reviewer reads

### Requirement: The document describes the error contract, which no controller return type mentions

Every error this platform serves is an RFC 9457 problem document produced by `web-core`'s advice
rather than returned from a handler, so a document inferred only from return types describes the
happy paths alone. Each service's document SHALL declare the problem shape as a reusable schema, and
SHALL state that the stable member is the machine-readable code rather than the localized prose.

#### Scenario: A client generator is run against the published document

- **WHEN** a consumer generates a client from `docs/api/<artifactId>.openapi.yaml`
- **THEN** the generated client has a type for the problem document, so a caller can read an error
  rather than only a success

#### Scenario: A caller decides which member of an error to branch on

- **WHEN** the problem schema is read
- **THEN** the code member is documented as stable and the detail member as localized from the
  caller's `Accept-Language`, so a consumer is told not to branch on the human-readable text

### Requirement: A filterable collection's query options appear in the published document

Where a service exposes a collection accepting the OData query options, those options SHALL appear
in that service's published document as described query parameters. This makes the existing
`odata-query-contract` requirement observable in the tree rather than only inside a running process.

#### Scenario: The reference service's filterable collection is inspected

- **WHEN** the published document for the service demonstrating the reference shape is read
- **THEN** its filterable collection lists `$filter`, `$orderby`, `$top`, `$skip` and `$count` with
  their types

#### Scenario: The contributing starter stops being on the service's compile path

- **WHEN** the dependency that contributes the query-option descriptions is removed or made
  unavailable to the service
- **THEN** the service's build fails, because the regenerated document loses those parameters and no
  longer matches the committed copy

### Requirement: The published documents are a description, not a compatibility promise

`docs/api/` SHALL state that the documents describe what the services currently serve. The build
SHALL NOT fail merely because a diff is backwards-incompatible for a consumer.

#### Scenario: A change removes a field a consumer depended on

- **WHEN** a response field is removed and the committed document is refreshed in the same commit
- **THEN** the build passes and the removal is visible in the diff for a human to judge. Nothing here
  encodes a compatibility policy, because the repository has not decided one for HTTP surfaces
