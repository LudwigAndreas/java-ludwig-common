## Context

See proposal.md - Why. Current state that shapes the approach:

- `identity-projection-spring-boot-starter` (package root `ru.ludwigandreas.identity`) holds the tables
  (`security_user`, `security_user_role`, `security_partner`, `security_partner_role`, `security_grant`), the three
  security SPIs that read them, the `identity` administrative area, and `IdentityProjectionService.apply(OidcUserEvent)`,
  which is the only write path for users. Its staleness rule (`occurredAt` not newer than stored → skip), contact
  policy, after-commit authority eviction and `PrincipalDisabledEvent` are provider-neutral already; only the input
  type is not.
- `security_user.source_system` exists (NOT NULL) and is written by the Kafka mapper today; nothing reads it.
- The Kafka listener (`ru.ludwigandreas.identity.kafka`) dead-letters a malformed payload on the first attempt and
  leaves an apply failure to the messaging starter's retry; that behaviour is kept.
- job-core already provides what a pull feed needs: `ScheduledJob` (fixed delay, non-reentrant, drains on stop,
  `runSoon()` for the jobs area) and `RunLock`.
- notification-service reads `security_user` directly (recipient resolution, announcement broadcasts) through
  `SecurityUserRepository` and `QSecurityUserEntity`, and its local seed writes through that repository.

## Goals / Non-Goals

**Goals:**
- One write path into the projection, `IdentityProjection`, used identically by every feed, a service's own code
  and the local seed.
- Feeds are separate starters with no knowledge of each other; the projection knows feeds only through a small
  status SPI.
- A pull feed a service enables by writing one class.
- Repair: a complete listing corrects anything a feed missed, and cannot wipe a source by accident.

**Non-Goals:**
- Read APIs over the projection. Services keep reading `security_user` through the projection's repository and
  Q-types; only writes are closed.
- Resuming an interrupted sweep. A sweep restarts; incremental sync is the resumable path.

## Decisions

### 1. Three modules, nested package roots

| Module | Package root | Depends on (in-repo) |
|---|---|---|
| `identity-projection-spring-boot-starter` | `ru.ludwigandreas.identity` | db-core, security, web-core (optional) |
| `identity-source-kafka-spring-boot-starter` | `ru.ludwigandreas.identity.kafka` | identity-projection, messaging |
| `identity-source-directory-spring-boot-starter` | `ru.ludwigandreas.identity.directory` | identity-projection, job-core |

Nested roots follow `messaging` / `messaging.db` and `audit` / `audit.store`. The Kafka package moves out of the
projection wholesale, so no package is split across jars.

*Alternatives:* one module with optional dependencies and properties (rejected: the provider-specific code and its
dependencies stay in every service, and a REST provider still has no seam); a separate module for the admin area
(rejected: it administers the projection's own tables and is already conditional on the admin surface).

### 2. The feed SPI: neutral, complete-state changes

In `ru.ludwigandreas.identity.feed`:

```java
public sealed interface IdentityChange permits Upsert, Disable, Remove {
    String source(); String subject(); Instant occurredAt(); String sourceVersion();
}
record Upsert(String source, String subject, Instant occurredAt, String sourceVersion, UserProfile profile) ...
record UserProfile(String displayName, String tenantId, Set<String> roles, Contact contact) {}

public interface IdentityProjection {
    ApplyOutcome apply(IdentityChange change);             // APPLIED, STALE, FOREIGN_SOURCE, DISCARDED
    List<ApplyOutcome> applyAll(List<IdentityChange> page); // one transaction
    Sweep beginSweep(String source);
}
public interface Sweep { void listed(Collection<String> subjects); SweepResult complete(); void abandon(); }
```

`applyAll` joins an existing transaction, so a caller can commit a page and its own bookkeeping together (the
directory's watermark). Role mapping, contact policy, staleness, ownership, eviction and the disabled event are
applied inside `apply`, never by a feed.

*Alternatives:* Spring application events as the input (rejected: no outcome, no transaction to join, and a typo in
an event type is silent); keeping `OidcUserEvent` as the input (rejected: it is the Kafka wire format).

### 3. Sources are configuration; ownership uses `source_system`

`ludwig.identity.sources.<name>` binds `role-mapping` (`Map<String, Set<String>>`) and `unmapped-roles`
(`KEEP` | `DROP`). The projection validates at startup that every feed's source is declared (feeds expose
`IdentityFeed { String source(); String kind(); Map<String, Object> status(); }` beans, which also drive the sources
listing). A change for an existing row whose `source_system` differs is `FOREIGN_SOURCE`. No schema change for
ownership.

### 4. Sweeps stage the listed subjects in a table

`beginSweep` creates a sweep id; `listed` batch-inserts `(sweep_id, subject)` into `identity_sweep_listed`;
`complete` runs in one transaction: count the source's active users not listed (`NOT EXISTS`), abort if the ratio
exceeds `ludwig.identity.sweep.max-disable-ratio`, otherwise disable them set-wise, publish one `PrincipalDisabledEvent`
and one eviction per disabled subject after commit, and delete the sweep's rows. `abandon` deletes them; rows of a
sweep older than one day are deleted by the next sweep of any source.

*Alternatives:* an in-memory set (rejected: unbounded memory for large directories); stamping `last_seen_at` on every
user (rejected: rewrites every row of the source on each sweep).

### 5. Kafka: one listener, topics mapped to sources

One `@KafkaListener` over every subscribed topic, on the existing `identityKafkaListenerContainerFactory`; the
record's topic selects the source. The wire format keeps today's fields as the contract `IdentityUserEvent` v1. The
consumer settings stay under `ludwig.messaging.consumers.<identity consumer>.*`.

### 6. Directory: watermark per page, jobs per source

```java
public interface IdentityDirectory {
    String source();
    DirectoryPage fetch(DirectoryQuery query);   // cursor, changedSince (null = complete listing), pageSize
    default boolean filtersByChange() { return true; }
}
record DirectoryPage(List<IdentityChange> changes, String nextCursor, Instant watermark) {}
```

When `changedSince` is given, a directory returns changes in ascending change order and `watermark` is the last
change time of the page. A sync commits each page's `applyAll` and the source's new watermark in one transaction; a
restart asks again from the stored watermark, and a change at the boundary is applied twice and skipped as stale the
second time. The cursor is never persisted. State lives in `identity_directory_state` (source, watermark, last sync and
sweep time, outcome, error code, counts), owned by the directory module's changelog
(`ludwig.identity.directory.liquibase.enabled`). Each directory gets two `ScheduledJob`s with run locks of the same
names (decision recorded in the spec); a failure is caught per run, stored as the outcome and rethrown to the job,
which logs it - the next run is the retry.

### 7. The sources listing composes feed status

`IdentityAdminController` gains `GET /admin/v1/identity/sources`; the projection supplies counts per source and
status, and each `IdentityFeed` bean supplies its own `status()` map. The projection never depends on a feed module.

### Dependency-direction check

- Does `identity-projection-spring-boot-starter` already depend on `identity-source-kafka-spring-boot-starter` or
  `identity-source-directory-spring-boot-starter`? They are new; nothing depends on them.
- Does `messaging-spring-boot-starter` depend on `identity-projection-spring-boot-starter`, directly or transitively?
  `project-index.json` inRepoDependents for `identity-projection-spring-boot-starter` is [`crud-service-example`,
  `notification-service`], which does not contain `messaging-spring-boot-starter`, so the Kafka source's two edges
  form no cycle. The projection's own edge to messaging is removed.
- Does `job-core` depend on `identity-projection-spring-boot-starter`, directly or transitively? The same
  inRepoDependents list does not contain `job-core`, and job-core's dependencies are architecture-rules,
  cache, security, test-support, test-support-security and web-core, none of which depends on the projection, so the
  directory source's edges form no cycle.

### POMs

- `pom.xml` (root): two `<module>` entries.
- `ludwig-bom`: two managed platform artifacts (the BOM lists every published module at `${project.version}`); no
  third-party version changes.
- `ludwig-service-parent`: no change.
- `build/jacoco-aggregate/pom.xml`: the two modules added to the coverage aggregate.
- The two services' POMs add `identity-source-kafka-spring-boot-starter`; the projection's POM drops
  `messaging-spring-boot-starter`.

### Conventions and their checks

| Convention | Check | Owner |
|---|---|---|
| Users are written only through `IdentityProjection` | `architecture-rules` rule `identity.writes-go-through-the-projection`: no class outside `ru.ludwigandreas.identity` (excluding `.kafka` and `.directory`, which use the SPI anyway) calls a `save*` or `delete*` method of an identity repository | ArchUnit |
| Feeds name a declared source | startup validation in the projection | the starter |
| One directory per source, valid source names | startup validation | the starters |
| The removed `ludwig.identity.kafka.topic` | startup failure naming the replacement | the starter |

Nothing platform-centralised is reimplemented: scheduling and locking are job-core's, the listing is on the admin
surface and its audit, eviction is the cache primitive's, and refusals go through the ProblemDetail pipeline.

## Risks / Trade-offs

- [A directory whose change filter misses deletions] → sweeps repair them on `sweep-interval`; the gap is
  documented per directory.
- [The sweep threshold blocks a legitimate mass offboarding] → the abort is logged with the would-be count; the
  operator raises `max-disable-ratio` for one run or disables users through the provider's events.
- [Two providers issuing the same subject] → refused as `FOREIGN_SOURCE` and counted; namespacing subjects is a
  non-goal, so a service with colliding providers needs a provider-side fix.
- [A directory returning changes out of order] → the stored watermark could skip a change; the contract states the
  ascending order, and a sweep repairs any miss.
- [Large sweeps] → staging inserts are batched; the set-wise disable is one statement; eviction per disabled
  subject is bounded by the threshold.

## Migration Plan

Breaking by design, no bridge. Per service: add `identity-source-kafka-spring-boot-starter`, declare the source
(`ludwig.identity.sources.oidc`, matching the `source_system` value existing rows carry), move
`ludwig.identity.kafka.topic` to `ludwig.identity.kafka.subscriptions.oidc.topic`, set
`ludwig.identity.kafka.group-id`. Schema changes are additive (`identity_sweep_listed`, `identity_directory_state`).
Rollback is the previous major.
