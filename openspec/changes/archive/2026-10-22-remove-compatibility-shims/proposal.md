## Why

Every module was read for code that exists only because removing it would have broken a published
API or wire contract below a major release - the "kept for compatibility, can go later" class. 3.0.0
is the major release, so this is the one release where each can go, and a platform declared finished
should not ship a single one of them. The survey found four, plus one stale comment from the previous
change:

| Where | What is kept, and the stated reason |
|---|---|
| `messaging-spring-boot-starter`, `outbox-spring-boot-starter` | Four pre-prefix Kafka header names (`event-type`, `event-version`, `idempotency-key`, `trace-id`): published as `MessageHeaders.LEGACY_*`, **written** beside every canonical `ludwig-*` header by `KafkaOutboxDispatcher`, and **read** as a fallback by `EnvelopeReader` and `VersionGatingDeserializer`, "during the transition" |
| `odata-filter-spring-boot-starter` | `ODataFilterExceptionHandler`, a `@RestControllerAdvice` "kept for services that do not use" `web-core-spring-boot-starter`, with its switch `odata.filter.web.problem-detail-advice-enabled` - the one second advice the one-`ProblemDetail`-pipeline rule exists to forbid |
| `observability-spring-boot-starter` | A second public constructor on `LogFieldNames` (19 names) and on `JsonLogEncoderConfig` (no `BuildIdentity`), "kept because a record's canonical constructor is part of its published API" |
| `audit-core` | `Redaction.LEGACY_SETTINGS_MASK` and `Redaction.LEGACY_HEADER_MASK`, retired markers that nothing writes, and `Redaction.isMask` still answering `true` for them |
| `grpc-client-spring-boot-starter` | A POM comment still calling the token-cache duplication "owed work", which `consolidate-grpc-contracts-and-tokens` removed |

The header transition's own condition is met by the release history: every producer since **v1.1.0**
(2026-09-28) writes both spellings, and every consumer since v1.1.0 reads the canonical one first.

## What Changes

- **BREAKING (wire):** `KafkaOutboxDispatcher` writes only the canonical `ludwig-*` envelope headers.
  `EnvelopeReader` and `VersionGatingDeserializer` read only those. A record carrying only a pre-prefix
  name - produced by a pre-1.1.0 producer - reads as having no such header. Upgrade precondition, stated
  in the changelog: no consumer older than 1.1.0 is still deployed, and no topic still holds records
  from a pre-1.1.0 producer that a consumer may replay.
- **BREAKING (API):** `MessageHeaders.LEGACY_EVENT_TYPE`, `LEGACY_EVENT_VERSION`,
  `LEGACY_IDEMPOTENCY_KEY` and `LEGACY_TRACE_ID` are removed.
- **BREAKING (API, configuration):** `ODataFilterExceptionHandler` is removed, with
  `ODataFilterWebAutoConfiguration.odataFilterExceptionHandler()` and the
  `odata.filter.web.problem-detail-advice-enabled` property. `web-core-spring-boot-starter` becomes a
  required dependency of `odata-filter-spring-boot-starter` (it already brings only `spring-web`, never
  `spring-webmvc`), so its mapper and bundle are always contributed. An ArchUnit test makes a second
  advice in the module a build failure.
- **BREAKING (API):** the non-canonical constructors of `LogFieldNames` and `JsonLogEncoderConfig` are
  removed. Each record keeps one constructor.
- **BREAKING (API):** `Redaction.LEGACY_SETTINGS_MASK` and `Redaction.LEGACY_HEADER_MASK` are removed,
  and `Redaction.isMask` recognises `Redaction.MASK` alone. The persisted `[redacted]` marker was
  rewritten by `audit-0004`. The migration test spells that string itself.
- Every removed public element (and the two dropped bean-method annotations) gets a `revapi.differences` entry naming this change, the owner's
  decision and the replacement. This is the same exception `consolidate-grpc-contracts-and-tokens`
  recorded for removal without a deprecating release.
- The grpc-client POM comment is corrected. The changelog's 3.0.0 section lists every removal, its
  replacement and the header upgrade precondition, in both locales.

## Capabilities

### New Capabilities

- `major-release-removals`: what a major release must leave behind. No published member survives
  whose only stated reason is compatibility with an earlier release, and each removal is a justified
  `revapi.differences` entry.

### Modified Capabilities

- `messaging-envelope`: the "Legacy header spellings are written and read during the migration window"
  requirement is removed and replaced by one that allows only the canonical names on the wire.
- `problem-detail-pipeline`: an added requirement. A module whose failures reach a web response depends
  on the pipeline and ships no fallback advice for its absence.

Shared contracts in `openspec/specs/` touched: `messaging-envelope` (one requirement removed, one
added) and `problem-detail-pipeline` (one added). No other.

## Impact

| Module | Tier | Change | In-repo dependents the gate must run |
|---|---|---|---|
| `messaging-spring-boot-starter` | library (starter) | `LEGACY_*` removed, fallback reads removed | `identity-projection-spring-boot-starter`, `notification-service`, `outbox-spring-boot-starter`, `user-settings-spring-boot-starter` |
| `outbox-spring-boot-starter` | library (starter) | dual write removed | `audit-spring-boot-starter`, `crud-service-example`, `export-spring-boot-starter`, `file-action-spring-boot-starter`, `file-ingest-spring-boot-starter`, `notification-service`, `user-settings-spring-boot-starter` |
| `odata-filter-spring-boot-starter` | library (starter) | advice and property removed, `web-core` required | `crud-service-example`, `export-spring-boot-starter`, `notification-service`, `user-settings-spring-boot-starter` |
| `observability-spring-boot-starter` | library (starter) | two constructors removed | `crud-service-example`, `export-spring-boot-starter`, `file-action-spring-boot-starter`, `grpc-client-spring-boot-starter`, `grpc-server-spring-boot-starter`, `notification-service`, `reconciliation-spring-boot-starter`, `rest-client-spring-boot-starter`, `user-settings-spring-boot-starter` |
| `audit-core` | library | two constants removed, `isMask` narrowed | fifteen modules, per `scripts/manifest.sh module sources/audit-core` |
| `grpc-client-spring-boot-starter` | library (starter) | POM comment | none |
| root `pom.xml` (`common`) | reactor | `revapi.differences` entries | everything |

Because the root POM changes, the only gate is `mvn clean install`. The version is a tag:
`.mvn/maven.config` is not edited.

## Non-goals

- Renaming `odata-filter`'s `odata.filter.*` configuration and metric prefix to `ludwig.*`. It is the one
  module off the platform prefix, and only a major release could rename it. But no module defers it for
  compatibility, so it is outside this survey's criterion. It belongs in a change of its own if wanted.
- Dropping the legacy `user_setting_audit` and `sync_audit_record` tables. They stay deliberately: the
  deployment verifies the migration against them and drops them itself. That is a data-ownership
  decision, not a compatibility one.
- Deleting the personal-access-token direct-authentication path. It is scaffolding that waits for the
  company gateway to gain PAT support, which is an external condition, not a release.
- `OutboxAuditLogger`, `PersistingOutboxAuditLogger` and `outbox_status_history`. They are kept for an
  operational reason the module documents, not for compatibility.
- Deferred features, not compatibility shims: architecture-rules' rule 6 and a wire-level `.proto`
  compatibility check.
