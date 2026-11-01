## Why

`identity-projection-spring-boot-starter` fuses two things that change for different reasons:

- the **projection**: users, roles, partners and grants in PostgreSQL, the authority resolver that reads them, and
  the administrative area that manages them. This is the same in every service;
- **one way of feeding it**: a Kafka listener for one topic and one event format, `OidcUserEvent`. This depends on
  the identity provider.

The projection's only input is `apply(OidcUserEvent)`. A service whose provider publishes users over REST, or a
service with two providers (staff and customers), cannot feed the projection without copying the module. A
projection fed only by events also has no repair path: a lost event, or a user deleted while the consumer was down,
stays wrong until the next event for that user.

This change makes the projection provider-neutral and makes each way of feeding it a starter of its own. Nothing
here keeps compatibility: the module has two in-repo consumers, both migrated here.

## What Changes

- **BREAKING - the projection is provider-neutral.** `identity-projection-spring-boot-starter` keeps the store, the
  `AuthorityResolver`, `DataScopeProvider` and `PartnerIdentityResolver` implementations, the `identity`
  administrative area, contact-data policy, authority-cache eviction and `PrincipalDisabledEvent`. It no longer
  depends on `messaging-spring-boot-starter` and contains no Kafka code.
- **A public feed SPI.** `IdentityProjection` takes neutral `IdentityChange` records (upsert with the user's full
  profile and role set, disable, remove) from a named **source**. Ordering by `occurredAt`, staleness, idempotence and
  eviction are the projection's, so every feed gets them identically.
- **Sources are declared.** `ludwig.identity.sources.<name>` declares each source a service accepts, with its role
  mapping (provider role or group to platform roles, and whether unmapped roles are kept or dropped). A feed naming
  an undeclared source fails startup. A subject belongs to the source that first projected it; a change for it from
  another source is refused, counted and logged, never applied.
- **Full sweeps with a safety threshold.** A feed that can list a source completely runs a sweep: every active user
  of that source the sweep did not see is disabled. A sweep that would disable more than
  `ludwig.identity.sweep.max-disable-ratio` (default 10%) of the source's active users is aborted and changes
  nothing, because an empty or truncated listing must not lock out a whole organisation.
- **New `identity-source-kafka-spring-boot-starter`.** Today's listener, moved and generalised: one subscription per
  source (`ludwig.identity.kafka.subscriptions.<source>.topic`), a mandatory consumer group per service, the
  existing event format as the published contract `IdentityUserEvent` v1.
- **New `identity-source-directory-spring-boot-starter`.** A pull engine for providers that expose users over an
  API. A service implements one interface, `IdentityDirectory` (`fetch(query)` returning a page of users and a
  cursor), against its provider with its own `@LudwigRestClient`; the starter does the rest:
  - incremental sync from a persisted watermark on a schedule, and a full sweep on a longer one, both job-core
    scheduled jobs under run locks (visible and runnable through the `jobs` area);
  - resumable progress (each page and its watermark commit together), failure isolation per source, metrics.
- **BREAKING - configuration moves.** `ludwig.identity.kafka.topic` is replaced by
  `ludwig.identity.kafka.subscriptions.<source>.topic`, read by the new starter, which also owns
  `ludwig.identity.kafka.enabled` and the now mandatory `ludwig.identity.kafka.group-id`. Startup fails on the removed
  key, naming its replacement.
- **The `identity` area lists the sources.** `GET /admin/v1/identity/sources` (`identity.source.read`): each source,
  its feeds, its user counts by status, its last applied change, and for a directory its watermark and last sync
  and sweep outcomes.
- **Both services move.** notification-service and crud-service-example declare their source and add the Kafka
  source starter. notification-service's local seed writes recipients through `IdentityProjection`.

## Capabilities

### New Capabilities
- `identity-projection`: the provider-neutral projection - feed SPI, sources and ownership, ordering and staleness,
  role mapping, full sweeps and their threshold, revocation latency, disabled-principal notification.
- `identity-source-kafka`: the Kafka feed - subscriptions per source, the `IdentityUserEvent` contract, consumer
  group and dead-letter behaviour.
- `identity-source-directory`: the pull feed - the `IdentityDirectory` SPI, incremental sync, sweeps, watermark state,
  scheduling and failure behaviour.

### Modified Capabilities
- `identity-admin`: the area declares `identity.source.read` and serves the sources listing.
- `starter-conditional-wiring`: the Kafka consumer requirement moves to `identity-source-kafka-spring-boot-starter`
  and its new properties.
- `notification-local-run`: recipients are seeded through `IdentityProjection`, not the repository.

## Non-goals

- **A SCIM 2.0 directory.** A ready-made `IdentityDirectory` for SCIM providers is the follow-up change
  `identity-source-scim`; it needs nothing from this change beyond the SPI.
- **Live resolution without a projection.** A service can still declare its own `AuthorityResolver`; the platform
  does not ship one, because notification-service needs the local user table and `PrincipalDisabledEvent` needs a
  state change to observe.
- **Provider webhooks.** A push endpoint is a service's own controller calling `IdentityProjection`; no starter
  until a second service needs one.
- **Moving a subject between sources**, merging duplicate subjects, or namespacing subjects per source.
- **Group membership, nested groups or attributes beyond today's profile.** Roles stay a flat set per user.
- **Partners and grants from a provider.** They remain locally administered through the `identity` area.

## Impact

Modules, with POM tier and in-repo dependents (from `project-index.json`):

- `identity-projection-spring-boot-starter` - library (parent `common`); dependents `crud-service-example`,
  `notification-service`. Loses the Kafka code and its `messaging-spring-boot-starter` dependency; gains the feed
  SPI, sources, role mapping, sweeps, a sweep staging table (additive changeset) and the sources listing.
- `identity-source-kafka-spring-boot-starter` - **new** library (parent `common`); depends on the projection and
  `messaging-spring-boot-starter`; no dependents yet beyond the two services.
- `identity-source-directory-spring-boot-starter` - **new** library (parent `common`); depends on the projection and
  `job-core`; owns the `identity_directory_state` table.
- `notification-service`, `crud-service-example` - services (parent `ludwig-service-parent`), no dependents:
  POM, `application.yml`, local seed, tests, OpenAPI documents.
- Build: the reactor `pom.xml`, `ludwig-bom` and `jacoco-aggregate` list the two new modules;
  `project-index.json` / `PROJECT_INDEX.md` are rebuilt.
- Shared contracts in `openspec/specs/`: `identity-admin`, `starter-conditional-wiring` and `notification-local-run`
  are modified; three capabilities are added. `operation-authorization` and `personal-access-token` name the identity
  projection but their requirements are unchanged.
- Docs: READMEs (EN and RU) of the three identity modules and both services, the admin guide (EN and RU) and
  `CHANGELOG` (EN and RU).
