## ADDED Requirements

### Requirement: The published documents are refreshed, checked and published by one script

`scripts/api-docs.sh` SHALL provide `refresh`, which regenerates every committed document under
`docs/api/` (OpenAPI and AsyncAPI); `check`, which runs every drift comparison and fails if any document
is stale; and `publish --version <v>`, which uploads each committed document to the platform's Nexus
repository as `ru.ludwigandreas:<artifactId>:<v>` with type `yaml` and classifier `openapi` or `asyncapi`.
A version ending in `-SNAPSHOT` SHALL go to the snapshots repository and any other to the releases
repository, using the repository ids and URLs the root POM deploys libraries with. `publish` SHALL refuse
to run when `check` fails; `publish --dry-run` SHALL print the uploads without running the check or
uploading. `missing` SHALL fail, without a build, when a service (role `service` in `project-index.json`)
has neither a document of a kind nor a line in `docs/api/README.md` declaring it has no such surface;
`check` includes it, and `scripts/gate.sh` runs it.

#### Scenario: Publishing a release's documents

- **WHEN** `scripts/api-docs.sh publish --version 4.0.0` runs on a tree whose documents are current
- **THEN** `crud-service-example-4.0.0-openapi.yaml` and `crud-service-example-4.0.0-asyncapi.yaml`
  (and the same for every other service) are uploaded to the releases repository

#### Scenario: Publishing stale documents

- **WHEN** a committed document no longer matches its service
- **THEN** `publish` fails before uploading anything, naming the stale document

#### Scenario: A new service without documents

- **WHEN** a service is added and `docs/api/` has no AsyncAPI document for it
- **THEN** `scripts/api-docs.sh missing`, and so the gate, fails naming the service and the kind

#### Scenario: A snapshot

- **WHEN** the version is `4.1.0-SNAPSHOT`
- **THEN** the documents go to the snapshots repository
