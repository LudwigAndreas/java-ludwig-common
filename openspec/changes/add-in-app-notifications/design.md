## Context

See `proposal.md` — Why. The requirements are in `specs/in-app-notification/spec.md`,
`specs/notification-inbox/spec.md` and `specs/enforcement-triad/spec.md`; this document explains
how, and why the alternatives were rejected.

The constraints that actually shape the approach, all of them pre-existing and all of them stated
in the module's own code:

| Constraint | Where it is stated | What it forbids |
|---|---|---|
| A transport implementation runs with no transaction open and must not open one, and never reads the database | `NotificationChannel` javadoc | writing an inbox row from a transport bean |
| `notification_delivery_content` is purged at `content-ttl: 7d`, and a startup check refuses a body window wider than its delivery window | `DeliveryContentEntity` javadoc, `RetentionService` | storing an unread inbox body there |
| The delivery row is claimed with `FOR UPDATE SKIP LOCKED` continuously and is deliberately narrow; it is not `AuditedEntity` because its claim step is a native `UPDATE` Hibernate never sees | `NotificationDeliveryEntity` javadoc, README "The two aggregates" | read-state columns on the delivery |
| A delivery is created `ACCEPTED` and settled in the same transaction into `SUPPRESSED`, `BATCHED` or `DEAD` | README "The delivery state machine" | nothing — this is the seam the design uses |
| The transactional bypass is a property of the *category*, never a request field | `PreferenceEvaluator` javadoc | a per-request fallback flag |
| Repository queries are QueryDSL against generated Q-types; the two native-SQL carve-outs are closed | `data-access` capability | a hand-written merge or upsert here |
| `web-core` carries no persistence; the long-running-operation envelope is for operations that run | `long-running-operations` capability | wrapping an inbox write in `OperationResponse` |

`notification-service` already depends on everything needed: `web-core-spring-boot-starter`,
`security-spring-boot-starter`, `odata-filter-spring-boot-starter`, `db-core`,
`identity-projection-spring-boot-starter`, `observability-spring-boot-starter`.

## Goals / Non-Goals

**Goals:**

- Reach a recipient who has no verified address or has declined every push channel.
- Add the destination without weakening any of the seven constraints above — each conflict is
  resolved by a stated decision, not by relaxing the constraint.
- Replace the six `IN_APP` special cases this feature would otherwise scatter through the
  preference path with one declaration.
- Leave the eventual split into a separate service as a clean cut, should read volume justify it.

**Non-Goals** (design level; product scope is in the proposal):

- No change to the claim query, the lease, the backoff or the retry schedule. If this change
  touches `DeliveryQueue`'s claim SQL at all, the design is wrong.
- No change to the three shipped transports' failure classification.
- No caching of the unread count. Correct before fast; `cache-purpose` is available when a measured
  need exists, and guessing a `CachePurpose` now would be guessing the wrong one.
- No new in-repo module and no promotion to a starter.

## Decisions

### D1 — `IN_APP` is a settlement, not a `NotificationChannel`

`IN_APP` deliveries are settled inside `NotificationServiceImpl`'s fan-out transaction, next to
where `SUPPRESSED`, `BATCHED` and `DEAD` are already settled, moving `ACCEPTED → DELIVERED` and
writing the inbox item in the same transaction. `ChannelRegistry` never resolves `IN_APP`; the
dispatch poller never sees it because the row is never written `PENDING`.

*Why.* The work queue exists to keep a slow third-party network off a pooled database connection —
that is the stated reason a transport may not hold a transaction. The inbox has no third-party
network: the "provider" is the same Postgres the fan-out transaction is already writing to.
Deferring the row out of that transaction would convert an exact write into an eventual one, add a
retry path that can only ever retry a local insert, and require the `IN_APP` body to be rendered
and parked somewhere until the poller picked it up — which is the seven-day table.

*Alternatives rejected.*

- **A fourth `NotificationChannel` bean.** Requires either a transport that reads and writes the
  database (contract violation, and it is the *written* contract, not a convention) or a self-HTTP
  call. Strictly worse than an insert.
- **A separate `ui-notification-service`.** Preserves the transport contract word for word, and is
  the honest long-term answer at scale — the transport then genuinely is a signed HTTP provider
  like `WebhookChannel`. Rejected now: a second deploy unit, a second database and a second image
  to pin, bought to protect an interface's purity. The repository has exactly one real service
  today. D1 is the seam to cut along later, because everything on the inbox side of it is already
  a separate aggregate with its own tables.
- **Enqueue `IN_APP` as `PENDING` and settle it from the poller** for dispatch-path uniformity.
  Rejected: uniformity is a means, and here it buys a strictly less reliable write plus a window in
  which a delivered-looking notification is not yet readable.

*Cost, stated plainly.* The README currently tells a reader that adding a transport is adding a
bean. After this change that is true for every interrupting transport and explicitly not the whole
story, and the README must say so — a carve-out with a boundary and a check, in the same shape as
the two SQL carve-outs.

### D2 — `ChannelClass` on the `ChannelType` enum, as a mandatory constructor argument

```java
EMAIL(INTERRUPTING), CHAT(INTERRUPTING), WEBHOOK(INTERRUPTING), IN_APP(PASSIVE)
```

`PreferenceEvaluator`, the quiet-hours branch, the digest eligibility check and
`SuppressionService`'s call site each ask `channel.channelClass()` instead of naming a constant.

*Why a constructor argument rather than a lookup table or a `Set<ChannelType> PASSIVE`.* A new
transport constant then does not compile without a classification. A set or a map would default
silently, and the default would be whichever the author of the set happened to pick — which is the
failure mode this decision exists to prevent. The compiler is a better check than any rule in the
triad, so it is the one used.

*Why only two values.* A third ("interrupting but not time-sensitive") was considered and dropped:
the three behaviours that key off this are quiet hours, digest and address suppression, and no
transport wants a different combination of them than the two classes already give. A dimension with
one hypothetical member is a dimension that gets misused.

*Scope.* Service-local. `web-core` has no channel vocabulary and must not grow one; this is not a
platform contract and the proposal says so.

### D3 — Two new tables, mirroring the delivery/content split for the same reason

```
notification_inbox_item                      notification_inbox_item_content
  id              uuid pk                      item_id    uuid pk/fk -> item
  owner_user_id   uuid        not null          subject    varchar(998)
  delivery_id     uuid        null, fk          body_html  text
  category        varchar     not null          body_text  text
  category_class  varchar     not null
  priority        smallint    not null        0018-inbox-item.sql
  locale          varchar     not null        0019-inbox-item-content.sql
  created_at      timestamptz not null        ids: notification-0018-…, author ludwig-notification
  seen_at         timestamptz null
  read_at         timestamptz null
  dismissed_at    timestamptz null
```

- `delivery_id` is **nullable with `ON DELETE SET NULL`**. The delivery window is 90d and the
  inbox window is anchored on read, so the delivery is the row that disappears first. An item that
  outlives its delivery is the normal case, not an error, and a `NOT NULL` here would make the
  delivery purge fail or cascade away a live inbox.
- `(owner_user_id, dismissed_at, read_at, created_at DESC)` is the index the list, the default
  filter and the unread count all use.
- Content is a second table for the same two reasons the delivery does it: the item row is the one
  the list and count queries scan, and content is the single cheap purge target. Identical shape,
  different retention window — which is precisely why it cannot be the *same* table.

*Alternative rejected:* read-state columns on `notification_delivery`. The README's own argument
for why retry state lives on the delivery rather than the request applies again one level down. It
would also make one table simultaneously a hot work-queue row and a user-mutated document, forcing
the retention purge to give one answer to two questions — and the delivery row is deliberately not
`AuditedEntity`, so a recipient's write would not be audited the way a user-owned mutation should
be.

### D4 — The fallback is evaluated once per recipient, after that recipient's fan-out has settled

`ludwig.notification.preferences.fallback.<category-class>: IN_APP`. After a recipient's
interrupting deliveries are settled and before the transaction commits: if every one is
`SUPPRESSED`, the class declares a fallback, the caller did not already request that channel, and
the recipient has not opted out of it, one more delivery is created and settled.

*Why there.* It is the only point where "every channel was suppressed" is known, and it is still
inside the transaction, so the fallback cannot half-apply. Per recipient, not per request: in a
request to five people, one person's total suppression must not create fallbacks for the other four.

*Why configuration keyed by category class, not by category or by request field.* A request field
would be set by every caller — the argument `PreferenceEvaluator` already makes. Category class is
the coarsest key that still distinguishes "undeclinable, must land somewhere" from "marketing,
must not be forced on anybody", and `MARKETING` falling back to an inbox the recipient has
deliberately emptied is exactly the behaviour to make hard to configure by accident.

### D5 — Rendering reuses the existing template path with a new part set

`RenderRequest` gains `IN_APP` as a target; the template resolves `subject` + `body_html` +
`body_text` as today. No new template engine, no new bundle mechanism, no per-channel renderer.
Content is rendered **in the fan-out transaction** rather than at dispatch, which is a real
difference from the other transports: a template error on `IN_APP` surfaces as a terminal delivery
at `202` time rather than a failed dispatch later. That is acceptable and arguably better, but it
must be bounded — a FreeMarker render is CPU-only and already strict, so it cannot block on a
network the way a transport can.

### D6 — The read API is a recipient-facing controller using the platform query contract

```
GET    /api/v1/inbox                    -> page envelope, newest first, excludes dismissed
GET    /api/v1/inbox/metadata           -> published filterable surface
GET    /api/v1/inbox/unread-count       -> { "count": n }
GET    /api/v1/inbox/{id}
POST   /api/v1/inbox/{id}/read          -> 200, idempotent
POST   /api/v1/inbox/read               -> 200 { "moved": n }
POST   /api/v1/inbox/{id}/dismiss       -> 200, idempotent
```

- The owner is resolved from the authenticated principal. There is no owner path variable, no
  owner query parameter and no `@Filterable` on `owner_user_id` — three independent reasons the
  endpoint cannot be turned into an existence oracle for somebody else's notifications.
- Another subject's item and a nonexistent item return the **same** refusal.
- `POST` for the transitions, not `PATCH`: they are named idempotent commands over a derived state
  machine, not partial document edits, and the service already uses `POST` for operator actions on
  a delivery.
- These are **not** long-running operations. No `OperationResponse`, no status resource, no
  `Retry-After` — `long-running-operations` is explicit that a richer thing maps onto the envelope
  only when it actually runs, and an inbox write completes in the request.
- Mark-all-read is a **QueryDSL update clause** (`update(item).where(owner.eq(…), readAt.isNull())
  .set(readAt, now)`), not native SQL. This change claims no carve-out and adds no fenced package.

### D7 — A fifth retention window, anchored on read, plus a separate unread ceiling

| Property | Default | Anchor |
|---|---|---|
| `retention.inbox-ttl` | 90d | `coalesce(read_at, dismissed_at)` |
| `retention.inbox-content-ttl` | 90d | same |
| `retention.inbox-unread-max-age` | unset | `created_at` |

`RetentionService` gains the sweeps; `NotificationConfigurationValidator` gains the relation check,
in the same shape as the four existing windows — content must not outlive its item.

The unread ceiling is deliberately **unset by default and a separate property**. Purging an unread
notification discards something the recipient was meant to receive, so it must be a decision
somebody made, not a value inherited from the window next to it. Its purge is counted and logged
distinctly, because "we discarded N unread notifications" is the one retention number an operator
must be able to see.

### D8 — Enforcement

Full table in `specs/enforcement-triad/spec.md`. In summary, and following "encode every rule
twice":

| Rule | Mechanism | Owner |
|---|---|---|
| Every transport has a classification | mandatory enum constructor argument | the compiler |
| The three channel vocabularies stay in step | existing compile-time mapper | the compiler |
| No read-state field on a delivery entity | ArchUnit, service-local | `architecture-rules` (ArchUnit), module-local test |
| In-app settlement never touches delivery content | ArchUnit, service-local | same |
| No preference rule reads the `IN_APP` constant | ArchUnit, service-local | same |
| `owner_user_id` is not `@Filterable` | ArchUnit, service-local | same |
| No transport implementation supports `IN_APP` | context test | tests — a predicate's return value is neither bytecode shape nor source text |
| The poller never claims `IN_APP` | integration test, named `…IT` | tests |
| Changeset shape | `scripts/check_migrations.sh` | gate script — a changelog is a resource |
| i18n key-set parity | existing bundle check | existing |

The four ArchUnit rules live in `notification-service`'s own architecture test sources, **not** in
`architecture-rules`. A rule about a channel vocabulary that exists in one module would pass
vacuously in every other service that inherits the shared jar, and a rule that passes vacuously
reads as coverage. This is the precedent the two `SqlConfinementTest` carve-outs already set.

Four rules cannot be mechanised — a transport's declared class being *true*, the fallback being the
right product call, an unread ceiling being deliberate, and a client polling the count rather than
paging. Each gets a comment at the point of the rule, enumerated in the spec.

### Dependency-direction check

**Does any module this change touches gain an in-repo dependency?** No. The work is confined to
`notification-service`, and every module it consumes — `web-core-spring-boot-starter`,
`security-spring-boot-starter`, `odata-filter-spring-boot-starter`, `db-core`,
`identity-projection-spring-boot-starter`, `observability-spring-boot-starter` — is already a
`compile` dependency of it per `scripts/manifest.sh module services/notification-service`.

**Does the target of any new dependency already depend on `notification-service`?** The question
does not arise, because no dependency is added. For completeness: `project-index.json`
`inRepoDependents` for `notification-service` is empty — nothing in the repository depends on it —
so it cannot participate in a cycle in either direction, and the verification gate is
`mvn -q validate` then `mvn -pl :notification-service -am verify` with no dependent builds.

### POM changes

**None.** Specifically: no change to `pom.xml` (root), none to `build/ludwig-bom/pom.xml` (no new
third-party dependency — FreeMarker, QueryDSL, Liquibase and the OData filter are all already
present and versioned), and none to `build/ludwig-service-parent/pom.xml` (no new build decision).
`scripts/manifest.sh build` should therefore not be needed; if a POM does end up changing, it must
be re-run and `scripts/manifest.sh stale` must be clean before the gate.

### Nothing centralised is re-implemented

- Audit → `audit-core`'s single `AuditSink` if an inbox mutation is auditable at all; no
  module-local SPI, no `*.audit` logger, no second redaction mask, no `try`/`catch` around `record`.
- Problem responses → `web-core`'s single RFC 9457 pipeline; no `@RestControllerAdvice` here.
- Query options, paging and the published filterable surface → `odata-filter-spring-boot-starter`;
  no hand-rolled paging or metadata document.
- Caller locale and zone → `web-core`'s `UserPreferences`; no `Locale.getDefault()`,
  `ZoneId.systemDefault()` or second type holding the pair. Note the distinction the platform draws
  and keep it: the **recipient's** stored preferences remain `RecipientPreferences`, which is a
  subject's preferences resolved on a worker with no caller, and must not be consolidated with the
  caller's ambient ones — a recipient reading their own inbox is a *caller*, and those are two
  different resolutions that happen to be the same person.
- Long-running operations → not used, deliberately; see D6.
- Caching → not used; see Non-Goals.

## Risks / Trade-offs

- **The fan-out transaction grows.** It now renders a template and writes two more rows per in-app
  recipient. A 500-recipient request does 500 renders inside the accepting transaction. → Bound it:
  render is CPU-only and already strict; the batch endpoint's existing per-request recipient cap
  applies unchanged; measure fan-out duration split by whether the request contained `IN_APP`, and
  if it becomes the dominant cost that is the signal to revisit the separate-service option, not to
  add a queue hop.
- **The dispatch path is no longer uniform across transports.** A reader who knows the README will
  expect a bean. → The carve-out is documented at the enum, at the settlement site and in the
  README's add-a-channel section, and the "no implementation supports `IN_APP`" context test makes
  the wrong instinct fail at `verify` rather than in review.
- **Unread items accumulate.** The read-anchored window means a dormant account's inbox never
  shrinks. → The unread ceiling exists for exactly this, off by default and reported distinctly;
  and the per-owner index keeps a large inbox cheap to page.
- **The fallback can surprise.** A deployment enabling it for a broad category class can start
  filling inboxes nobody expected. → Keyed by category class only, off by default, never applied
  over an explicit opt-out, and counted as its own metric so the first day it fires is visible.
- **An in-app template error now fails at `202` time.** → It is a terminal delivery with a reason,
  not a rejected request, so the other recipients and the other channels are unaffected — the same
  shape the service already uses for an unreachable recipient.
- **Two more tables to purge.** The retention sweep grows two statements. → Same `RunLock`, same
  batched shape, same schedule; the relation check at startup refuses an inconsistent set of
  windows rather than letting the purge orphan content.

## Migration Plan

1. Changesets `0018-inbox-item.sql` and `0019-inbox-item-content.sql`, formatted SQL, ids
   `notification-0018-inbox-item` / `notification-0019-inbox-item-content`, author
   `ludwig-notification`, each `dbms:postgresql` with a real `--rollback` (both are `CREATE TABLE`,
   so the rollback is a `DROP TABLE` — `NOT REQUIRED` would be wrong here), appended as
   `<include>` elements to `db.changelog-master.xml` in that order.
2. Deploy. `IN_APP` is inert: no caller requests it, no fallback is configured, both retention
   properties default to values that purge nothing that exists. The release is behaviourally a
   no-op until somebody opts in.
3. Enable per consumer: a calling service adds `IN_APP` to its `channels`, or a deployment sets
   `preferences.fallback.TRANSACTIONAL: IN_APP`.

**Rollback.** Roll the image back; the two tables are additive and unreferenced by any existing
query, so an older image runs against the newer schema unchanged. A full schema rollback is the two
`--rollback` statements, and is only safe once no inbox item is live — which is why step 2 ships the
feature off. Nothing existing is dropped, re-typed or rewritten, so there is no window in which two
versions disagree about a column.

## Open Questions

- **Does the unread count need a cap** (`"99+"`) rather than an exact count on a very large inbox?
  A presentation decision that changes neither the specs nor the schema; the endpoint can gain a
  bounded-count option later without a migration.
- **Should a dismissed item be purgeable immediately** rather than after `inbox-ttl`? Deferrable:
  the anchor already covers `dismissed_at`, so this is one property's default value, not a design
  change.
