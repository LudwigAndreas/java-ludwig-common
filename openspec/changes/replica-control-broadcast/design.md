## Context

This adds a cross-cutting mechanism to `job-core` and a dependency edge from `job-core` to
`cache-spring-boot-starter`, and introduces a new convention (replica controls). It therefore needs the full
design.

Facts it builds on:
- `job-core` owns the run-lock table, `JobInstanceIdentity` (a per-process owner id), `SelfSchedulingLifecycle`,
  and the `jobs` admin area;
- `security-spring-boot-starter` provides `AdminOperationCatalog`, `AdminAccessEvaluator`, `AdminAudit`,
  `AdminInvocation` and `OperationResponses`;
- the shared tier of the cache module already broadcasts evictions over Redis pub/sub, but only for caches with a
  shared tier.

## Goals / Non-Goals

**Goals:**
- One broadcast mechanism, honest about partial delivery.
- Controls authorized by their own module's operations.
- No new infrastructure dependency.

**Non-Goals:** see the proposal.

## Decisions

1. **The database as transport, polled.**
   - **Kafka was rejected:** not every service has it, an ack would need a second topic, and "which replicas
     exist" would still need a registry.
   - **Redis pub/sub was rejected:** it is fire-and-forget, and absent without a shared cache.
   - **Postgres `LISTEN/NOTIFY` was rejected:** it is lost while a replica is reconnecting, so it would need
     polling as a fallback anyway.

   Polling one indexed table every 2 s costs one cheap query per replica per interval, and it gives durable
   commands, durable acknowledgements, and liveness from the same rows.
2. **Tables (job-core changelog).**
   - `job_instance(owner PK, started_at, last_seen_at)` holds the heartbeat. Rows unseen for a day are deleted by
     the same job.
   - `job_control_command(id PK, control, arguments JSONB, operation, submitted_by, submitted_at, deadline,
     targets TEXT[])`. `targets` is frozen at submission.
   - `job_control_ack(command_id, owner, acknowledged_at, outcome, failure_code, result JSONB,
     PK(command_id, owner))`. `result` is a control's optional per-replica answer, capped at 4 KiB; a larger result is
     acknowledged as failure code `ludwig.job.error.control-result-too-large`.

   Commands and acks older than `control.retention` (default `P7D`) are purged by the poller job under a run lock,
   bounded per pass.
3. **One poller job per replica, not a run-locked one.** `ReplicaControlPoller extends SelfSchedulingLifecycle`. Each
   pass does three things:
   - it upserts its heartbeat;
   - it selects commands with `deadline > now()`, its owner in `targets`, and no ack row of its own;
   - it applies each command and inserts the ack.

   "At most once per process" is held by an in-memory set of applied ids, kept in addition to the ack row. If the
   ack insert fails after the handler ran, the next pass does not re-apply. A restart *can* re-apply, which is
   why idempotency is part of the SPI contract.
4. **SPI: `ReplicaControl<A>`.** It declares `name()`, `argumentType()`, `operationFor(A args)` returning an
   `AdminOperation`, and `apply(A args)`, which returns an optional small per-replica result and throws to fail.

   `operationFor` takes the arguments because the right tier can depend on them: `cacheevict` on a `SECURITY` cache
   is privileged. The controller therefore:
   - binds and validates the arguments (Jakarta Validation);
   - evaluates `operationFor`;
   - authorizes through `AdminAccessEvaluator` programmatically, the same evaluator `@AdminAccess` uses, so the
     denial is audited identically;
   - requires `Admin-Reason` for a privileged operation;
   - records the command.

   **Mechanical check for the new convention:** a startup validator fails when two controls share a name, or when
   a control's `operationFor` can return an operation the catalog does not contain. The second is checked against
   a declared `possibleOperations()` set. The startup validator is the owner because the controls are beans, which
   is the same reasoning as `AdminHandlerValidator`.
5. **Envelope mapping.** This is done in `ReplicaCommandViews`, the module's edge:

   | Command state | Envelope status |
   |---|---|
   | all targets acknowledged success | `SUCCEEDED` |
   | any target acknowledged failure | `FAILED` |
   | deadline passed with a target silent | `FAILED`, code `ludwig.job.error.control-unacknowledged` |
   | none of the above | `RUNNING` |

   A command whose target list was empty (no live replica, which is impossible from an HTTP request, since the
   answering replica heartbeats first) is `SUCCEEDED` with zero entries.
6. **Cache area placement.** `job-core` gains an optional dependency on `cache-spring-boot-starter`. The new
   `CacheAdminAutoConfiguration` is
   `@ConditionalOnClass(LudwigCacheRegistry) @ConditionalOnBean(LudwigCacheRegistry, AdminOperationCatalog)`, and it
   declares the `caches` area and the `cacheevict` control.

   The cache module only gains `LudwigCacheRegistry.evictAll(String name)`. That method evicts the local tier and,
   through the existing `publishInvalidation(ALL_KEYS)`, the shared tier; the broadcast then makes the local tiers
   on other replicas consistent too.

   **Dependency-direction check.** Does `cache-spring-boot-starter` already depend on `job-core`, directly or
   transitively? `project-index.json` lists `cache-spring-boot-starter`'s in-repo dependencies as none (test scope
   only: `test-support`), so it does not, and no cycle is possible. `ModuleIndependenceTest` keeps the cache module
   free of in-repo dependencies, and this change adds none to it.

**POMs.** Only `sources/job-core/pom.xml` changes, gaining the optional `cache-spring-boot-starter` dependency. The
root `pom.xml`, `ludwig-bom` and `ludwig-service-parent` are unchanged. `scripts/manifest.sh build` is run.

## Risks / Trade-offs

- [Polling adds load.] → It is one indexed query per replica per 2 s. The interval is configurable, and the README
  gives the arithmetic (100 replicas means 50 queries a second, all index-only).
- [Clock skew between replicas affects liveness.] → Liveness compares `last_seen_at` with the database's `now()`,
  never a replica's clock.
- [A control's handler blocks the poller.] → Handlers run on the poller thread, with a per-control timeout (`PT10S`).
  A timeout is acknowledged as failure code `ludwig.job.error.control-timeout`. The SPI javadoc says a control must
  be quick and local.

## Migration Plan

Two changesets, `0002-create-instance.sql` and `0003-create-control.sql`, each with a rollback that drops its
tables. There is no data to migrate.
