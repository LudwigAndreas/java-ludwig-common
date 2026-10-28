## Why

Two small defects in the administrative surface's discovery endpoint, recorded in
`openspec/intents/admin-surface-follow-ups.md` §3:

- `GET /admin/v1/me/operations` is published in the OpenAPI documents with content type `*/*`. A generated client
  therefore has no response type.
- An operation whose description key is missing from its module's bundle is shown to an administrator as its raw
  id (`outbox.privileged`), and nothing notices. Every module added its keys by hand in `starter-admin-endpoints`;
  the next module to add an area can forget, and the first sign would be a support ticket from the UI team.

## What Changes

- The discovery endpoint declares `produces = application/json`. Both published OpenAPI documents are regenerated.
- Startup fails when a catalogued operation's description key does not resolve in the default locale. The message
  names the operation and the key. Bundle key-set parity across locales is already enforced by `i18n-bundles`, so
  the default locale is enough.
- **BREAKING (startup)**: a service with a custom `AdminOperationContributor` whose keys are not in any bundle no
  longer starts. No in-repo contributor is affected; the check is verified against both services.

## Capabilities

### New Capabilities
None.

### Modified Capabilities
- `admin-authorization`: the discovery requirement states the media type, and a new requirement makes a missing
  description a startup failure.

## Non-goals

- Binding failsafe in `security-spring-boot-starter`. Its context tests need no infrastructure and live in `unit/`,
  like `SecurityAutoConfigurationTest`. The follow-up stays recorded as "when the module grows a test that needs a
  container".
- Translating descriptions into further locales.
- Checking description wording.

## Impact

- Module: `security-spring-boot-starter` (library; parent `common`). It has 16 in-repo dependents, all of which the
  gate runs:
  - `audit-spring-boot-starter`
  - `crud-service-example` (service)
  - `export-spring-boot-starter`
  - `file-action-spring-boot-starter`
  - `file-ingest-spring-boot-starter`
  - `grpc-client-spring-boot-starter`
  - `grpc-server-spring-boot-starter`
  - `identity-projection-spring-boot-starter`
  - `job-core`
  - `messaging-db-spring-boot-starter`
  - `notification-service` (service)
  - `outbox-spring-boot-starter`
  - `pat-spring-boot-starter`
  - `reconciliation-spring-boot-starter`
  - `test-support-security`
  - `user-settings-spring-boot-starter`
- `docs/api/notification-service.openapi.yaml` and `docs/api/crud-service-example.openapi.yaml` are regenerated.
- Shared contract: `admin-authorization` is modified.
- No POM changes.
