## Why

Some administrative controls act on state held in each replica's memory: local cache entries, hot-reloaded
configuration, minted outbound tokens, and circuit-breaker state. An administrative call lands on exactly one
replica, so an endpoint that acts locally would report success while the other replicas carry on unchanged.
`starter-admin-endpoints` therefore left all such controls out. A "broadcast" was named as the prerequisite
(`openspec/intents/admin-surface-follow-ups.md` §2), and this change builds it, with cache eviction as its first
control.

## What Changes

- **A replica-control channel in `job-core`.**
  - Each replica heartbeats a row in a new `job_instance` table: its owner id from `JobInstanceIdentity`, when it
    started, and when it was last seen.
  - A control command is a row in `job_control_command`. Every replica polls for new commands
    (`ludwig.job-core.control.poll-interval`, default `PT2S`), applies each one exactly once through the handler
    registered under its name, and writes an acknowledgement (`succeeded` or `failed`, with a code) to
    `job_control_ack`.
  - The transport is the database job-core already requires. No Kafka or Redis is needed, so every service that
    can hold a run lock can broadcast.
- **The `replicas` administrative area.**
  - `GET /admin/v1/replicas` (`replicas.read`) lists live instances, those seen within three poll intervals.
  - `GET /admin/v1/replicas/commands/{id}` (`replicas.read`) returns a command as the platform operation envelope,
    with one acknowledgement per targeted instance.
  - A command targets the instances that were live when it was submitted. It is:
    - `SUCCEEDED` when all of them acknowledged success;
    - `FAILED` when any acknowledged failure;
    - `FAILED` with code `ludwig.job.error.control-unacknowledged` when the deadline
      (`control.ack-timeout`, default `PT30S`) passes with targets still silent. An instance that died is named
      among the silent targets, never assumed done.
- **A control SPI.** A module contributes a `ReplicaControl`: a name, the `AdminOperation` that authorizes it
  (from the module's own area), a validated argument type, and a handler. Submission endpoints are per control:
  `POST /admin/v1/replicas/controls/{control}` answers `202` with the command's envelope and `Location` set to the
  command resource. Authorization is the control's own operation, so evicting a security cache is
  `caches.privileged`, not a `replicas` grant.
- **First control: evicting a cache everywhere.**
  - A new `caches` area. `GET /admin/v1/caches` (`caches.read`) lists the registered caches with purpose, tier and
    TTL.
  - The control `cacheevict` (`caches.operate`) clears all entries of a named performance cache on every replica.
    For a `SECURITY`-purpose cache it requires `caches.privileged` and a reason.
  - The `caches` area is contributed by `job-core` through an optional dependency on `cache-spring-boot-starter`.
    The cache module stays free of in-repo dependencies, as `cache-purpose` requires, and gains only
    `LudwigCacheRegistry.evictAll(String name)`.
- **Services.** `notification-service` and `crud-service-example` grant `replicas.*` and `caches.*` to their admin
  roles, and `replicas.read` and `caches.read` to notification's support role.

## Capabilities

### New Capabilities
- `replica-control`: instance liveness, broadcasting a control to every live replica, and per-replica
  acknowledgement.
- `cache-admin`: listing caches and evicting a cache on every replica.

### Modified Capabilities
None. `cache-purpose` is unchanged; the registry gains a method without changing any requirement.

## Non-goals

- Per-key eviction. A key is typed per cache, and rendering a key from a string is a per-cache codec. Whole-cache
  eviction plus the TTL backstop covers the operational need.
- The other per-replica controls (hot-reload, outbound tokens, circuit breakers). They are `replica-controls`,
  which builds on this change.
- Targeting a subset of replicas.
- Exactly-once across a replica restart. A replica that restarts mid-command re-applies it if it had not yet
  acknowledged. Every control must be idempotent, and the SPI says so.

## Impact

- Modules, all libraries with parent `common`:
  - `job-core`, which has 9 in-repo dependents: `audit-spring-boot-starter`, `export-spring-boot-starter`,
    `file-action-spring-boot-starter`, `file-ingest-spring-boot-starter`, `idempotency-spring-boot-starter`,
    `messaging-db-spring-boot-starter`, `notification-service`, `outbox-spring-boot-starter` and
    `reconciliation-spring-boot-starter`;
  - `cache-spring-boot-starter`, whose in-repo dependents `pat-spring-boot-starter`, `security-spring-boot-starter` and
    `user-settings-spring-boot-starter` all run in the gate.
- Services: `notification-service` and `crud-service-example`, through their `application.yml`.
- Shared contracts: the new capabilities above. `admin-authorization` is used as it is.
- `job-core`'s POM gains an optional dependency on `cache-spring-boot-starter`. The root `pom.xml`, `ludwig-bom` and
  `ludwig-service-parent` are unchanged.
- Migrations `0002-create-instance.sql` and `0003-create-control.sql` in job-core's changelog.
- The manifest is rebuilt (POM change). READMEs (EN and RU) of `job-core` and the cache starter, the admin guide,
  both services' OpenAPI documents, and `CHANGELOG` (EN and RU).
