## Context

See `proposal.md` — Why. Requirements are in `specs/platform-announcement/spec.md`,
`specs/announcement-email-broadcast/spec.md` and `specs/enforcement-triad/spec.md`.

**This change assumes `add-in-app-notifications` is applied.** It needs `IN_APP`, `ChannelClass`, the
inbox and `PreferenceEvaluator`'s split rules. It also edits `PreferenceEvaluator` and the category
vocabulary, so the two changes must not be implemented concurrently.

The constraints that shape the approach:

| Constraint | Where it is stated | What it forbids |
|---|---|---|
| JPQL has no `UNION`, and QueryDSL-JPA generates JPQL | `data-access` capability; Querydsl's JPA module | a single merged inbox+announcement feed, without a third fenced SQL package |
| The inbox read path is safe because of one predicate, `owner = me` | `InboxService`, `InboxQueryRepositoryImpl` | adding a derived visibility term to it |
| `security_user_role(user_id, role_code)` with an index on `role_code` | `identity-0002-create-security-user-role.sql` | nothing — this is what makes a role audience cheap |
| Roles are already resolved onto the principal | `LudwigPrincipal.roles`, `SecurityPrincipals` | a second identity lookup per announcement |
| No module may restate `OperationStatus` | `long-running-operations` capability, `RuleGroup.OPERATIONS` | an `AnnouncementRunStatus` enum |
| There is no shared operation table and `web-core` carries no persistence | same capability | putting the fan-out run anywhere but this service |
| `dedupKey` is unique: idempotency key + channel + recipient | `NotificationDeliveryEntity` | needing a second exactly-once mechanism for a resumed run |
| A maintenance job runs under `job-core`'s leased mutex | `run-lock` capability, `RetentionScheduler` | a second locking mechanism for the fan-out |
| The transactional bypass is a category property, never a request flag | `PreferenceEvaluator` javadoc | `channels` or `alsoEmail` on a publish request |

`notification-service` already depends on everything needed. No new in-repo dependency.

## Goals / Non-Goals

**Goals:**

- One row per announcement whatever the audience size, and retention that does not scale with it.
- Revocation of a role takes effect on the next read, with no job and no rewrite.
- Reuse the delivery queue wholesale for email, so retry, backoff, rate limits and suppression are
  not reimplemented.
- Leave the inbox's read path exactly as safe as it is now.

**Non-Goals** (design level; product scope is in the proposal):

- No change to the claim query, the dispatcher or the inbox's queries. If this change touches
  `InboxQueryRepositoryImpl`, the design is wrong.
- No native SQL and no third fenced package.
- No cache. See the note under D8 for where one would first be justified and why its `CachePurpose`
  is unusually easy to get right if it ever is.

## Decisions

### D1 — Separate feed, not a merged one

`/api/v1/announcements` is its own controller, service and repository, beside the inbox rather than
inside it.

*Why.* Two reasons and the second is the one that decides it. A merged feed needs `UNION` to page,
sort and count across two sources; JPQL has none, so it would need a third native-SQL carve-out, and
`odata-query-contract` is explicit that a paged envelope must state the caller's absolute position —
which merging two queries in the service layer cannot do correctly. And the inbox's correctness
currently rests on `owner = me`, a predicate that is obviously right; an announcement's visibility is
*derived* from role membership, and putting a derived term on the service's most-polled endpoint
turns a trivially-correct filter into one that has to be reviewed. An ArchUnit rule forbids the inbox
path from depending on the announcement entity.

*Alternatives rejected.* **Native-SQL union** — buys one feed for a carve-out and a derived inbox
query; the carve-outs in this repository exist for statements QueryDSL cannot express at all, not for
convenience. **Fan-out-on-write** — the 150 000 rows and 200 MB the proposal opens with.

*Cost, stated.* The UI shows two feeds and sums two badge numbers. That is a real cost and it is
probably also correct: an announcement is a banner or a "what's new" panel, not a line in the bell
dropdown.

### D2 — The audience is two columns, resolved live

```
announcement
  id, category, template_key, audience_kind, audience_value,
  visible_from, visible_until, published_by, published_at, updated_at

visible to me =
      visible_from <= :now AND :now < visible_until
  AND ( audience_kind = EVERYONE
     OR (audience_kind = ROLE AND audience_value IN :myRoles) )
  AND NOT EXISTS (marker WHERE announcement_id = id AND owner_user_id = :me)
```

`:myRoles` comes off the already-resolved principal, so this is an `IN` over an in-memory list —
plain QueryDSL, one index on `(visible_until, visible_from)`, no join to `security_user_role` at all
on the read path. The role table matters for the *email* snapshot, not for visibility.

*Why live.* Revocation. This is the same argument `security-spring-boot-starter` uses to settle
reading roles from a projection rather than from a token claim, and it is the whole reason the
projection exists. A snapshot would mean a demoted administrator keeps seeing administrator
announcements until somebody writes a cleanup job.

*Alternative rejected:* a materialized `announcement_audience(announcement_id, user_id)` table. It
makes visibility a plain join and makes everything else worse — N rows again, stale on revocation,
and a new user invisible to it. An ArchUnit rule forbids it so that it is not reintroduced as an
optimisation.

### D3 — Dismissal is a lazy marker

```
announcement_marker
  announcement_id, owner_user_id, dismissed_at
  PRIMARY KEY (announcement_id, owner_user_id)
  FK announcement_id -> announcement ON DELETE CASCADE
```

Written on first dismissal and never in advance, so an announcement nobody dismissed has no
per-user rows. `CASCADE` because a marker without its announcement is unreachable, which is the
opposite of the inbox item's `ON DELETE SET NULL` and for the opposite reason — there the delivery is
purged first by design.

*Why no `seen_at` or `read_at` here.* The inbox has three instants because an item is a document
somebody works through. An announcement is either in your way or not; "seen" has no consumer, and a
column with no consumer is one somebody later writes a query against. Dismissal alone also keeps
`NOT EXISTS` as the only marker term on the read path.

### D4 — The catalogue decides channels and class; the request decides data

```yaml
ludwig:
  notification:
    announcements:
      enabled: true
      allowed-audiences: [EVERYONE, ROLE]
      targetable-roles: [ADMIN, SUPPORT, AUDITOR]
      max-visibility-window: 90d
      fan-out:
        batch-size: 500
        run-interval: 30s
      categories:
        platform-release:  { category-class: PLATFORM,  channels: [IN_APP] }
        platform-incident: { category-class: PLATFORM,  channels: [IN_APP, EMAIL] }
        feature-tips:      { category-class: MARKETING, channels: [IN_APP] }
```

A publish names `category` and carries no `channels` and no `alsoEmail`. *Why:* the argument
`PreferenceEvaluator` already makes — from inside any one team its own announcement always looks
important, so as a request field it would always be set to the most permissive value.

`targetable-roles` is an allowlist, not "any role". A free-form role target is an enumeration
primitive for the role space, and lets an announcer address a role whose membership is itself
sensitive. Resolution happens in **one** method and an ArchUnit rule forbids a second construction
path, because a second path that skipped the allowlist would work perfectly for every valid role and
silently accept every invalid one. A rejected role must not reveal whether it exists.

The catalogue is validated at **startup**, in the same shape as the existing retention relation
checks. A typo is otherwise found by an announcer at the moment they are trying to announce
something.

*Strings in the properties, enums at the boundary*, as for `preferences.fallback`: the `settings`
package is deliberately free of service-layer types because every layer reads it.

### D5 — `CategoryClass.PLATFORM`, and splitting the bypass

`PreferenceEvaluator` currently answers one question — bypass or not — with `TRANSACTIONAL`
bypassing both opt-out and quiet hours. That becomes two questions:

```java
TRANSACTIONAL(bypassesOptOut = true,  bypassesQuietHours = true)
PLATFORM     (bypassesOptOut = true,  bypassesQuietHours = false)
MARKETING    (bypassesOptOut = false, bypassesQuietHours = false)
```

Mandatory constructor arguments, as `ChannelClass` is on `ChannelType`, so a fourth class does not
compile until somebody has decided both answers. No `default` arm over the class — the in-app change
kept three pre-existing switches exhaustive for exactly this reason and this must not be the
exception.

*Why a third class rather than reuse.* The combination is not expressible: `TRANSACTIONAL` emails a
release note at 03:00, `MARKETING` lets half the estate opt out of an incident notice. That the
matrix has a genuinely empty cell is the argument.

### D6 — Email: a batched resumable run over the existing queue

```
POST /api/v1/announcements
  ├─ one transaction: render, insert announcement + content   → visible IMMEDIATELY
  └─ if category channels include EMAIL: insert a fan-out run (PENDING)
        └─ 202 + status-resource header for the run

scheduler (job-core RunLock, like digest and retention)
  └─ claim the run, then per batch of 500:
       page security_user (+ join security_user_role for a ROLE audience)
         ordered by id, after :cursor
       → create ordinary notification_delivery rows via the existing fan-out
       → commit, advance cursor, renew lease
```

- **The run table is this service's own** (`announcement_email_run`: announcement id, status,
  cursor, created/total counts, timestamps), because `long-running-operations` is explicit that there
  is no shared operation table and `web-core` carries no persistence. It maps onto
  `OperationResponse` at the edge, built with `OperationResponses`.
- **Status is the platform vocabulary**, not a new enum. `RuleGroup.OPERATIONS` already enforces
  this; this change relies on that rule rather than adding a copy.
- **The cursor is the subject id**, with a stable `ORDER BY id`. Resumable, and cheap — a keyset
  page, not `OFFSET`.
- **Exactly-once is already solved.** `dedupKey` is unique over idempotency key + channel +
  recipient, so a resumed batch that re-processes a recipient cannot create a second delivery. The
  run's idempotency key is derived from the announcement id, so it is stable across resumes. This is
  the reason the design does not need a per-recipient progress table.
- **Deliveries are created through the existing fan-out path**, so suppression, preferences, quiet
  hours and address resolution all apply per recipient. A broadcast is not a bypass.
- **202 with the announcement already terminal on the inbox side** is permitted and intended: the
  operations capability states that a 202 may carry a terminal envelope, and notification already
  does this when it fans out in the accepting transaction.

*Why not create the deliveries in the publish transaction.* 100 000 inserts in the request that
accepts the announcement is a multi-minute transaction pinning the oldest transaction id on the
busiest table in the service. The inbox half is one row and stays inline; the email half is
inherently N and is deferred. The split is not an inconsistency — it is the same
`PASSIVE`/`INTERRUPTING` boundary.

*Cancellation* is cooperative: the endpoint answers **202**, the loop checks between batches, and
already-created deliveries are **not** recalled. Dropping them would make the count the envelope
already reported untrue.

### D7 — One content row per supported locale, rendered at publish

```
announcement_content
  announcement_id, locale, subject, body_html, body_text, rendered_at
  PRIMARY KEY (announcement_id, locale)
```

At publish the template is rendered once per `supported-locales`, giving two rows today (`en`, `ru`).
A reader gets the row for their own locale, falling back to the default — which is the same rule
`TemplateCoordinates` already applies when resolving a template file, so the behaviour a recipient
sees is identical to a notification's.

*Why not one row in the default locale.* The platform already holds a per-user locale and already
renders notifications in it. One row would mean a Russian-speaking recipient reads an English
announcement, which is a visible regression against the channel next to it.

*Why not render per recipient at read time.* The same reason the inbox renders at fan-out:
`recipient-data-ttl` scrubs the variable map after seven days, so an announcement in week three could
no longer be rendered. Rendering at publish also means a template corrected afterwards does not
silently change what an announcement said, which is the promise already made about a sent email.

*Why the locale set is bounded and cheap.* It is `supported-locales`, not the set of recipients'
locales — so the row count is the number of languages the deployment ships bundles for, independent
of audience size. This keeps D1's property intact: one announcement is a constant number of rows.

*Cost.* A render failure in one locale must not silently publish a half-translated announcement. The
publish renders every locale **before** inserting anything, so the whole publish fails if any locale
cannot be rendered — which is the same strictness the renderer already applies within one locale.

### D8 — No cache, and what would change that

The announcement list and count are read straight from Postgres. The read is one index scan over
`(visible_until, visible_from)` plus a `NOT EXISTS`, with no join, so there is nothing yet worth
caching.

If the badge endpoint later needs one, the `CachePurpose` writes itself and is worth recording now
because it is the declaration the cache module says a module must get right: it is **`security`**,
because the TTL is literally *how long a revoked role keeps seeing an administrator announcement*.
That means a startup ceiling and no stale reads — not the `performance` tier. A future change that
reached for `performance` here because the endpoint is hot would be making exactly the mistake
`cache-purpose` exists to prevent.

### Dependency-direction check

**Does any module this change touches gain an in-repo dependency?** No. The work is confined to
`notification-service`, and every module it consumes —
`identity-projection-spring-boot-starter` (for `security_user` and `security_user_role`),
`web-core-spring-boot-starter`, `security-spring-boot-starter`,
`odata-filter-spring-boot-starter`, `job-core`, `db-core`, `observability-spring-boot-starter` — is
already a `compile` dependency per `scripts/manifest.sh module services/notification-service`.

**Does the target of any new dependency already depend on `notification-service`?** The question does
not arise, because no dependency is added. For completeness: `project-index.json`
`inRepoDependents` for `notification-service` is empty, so it cannot participate in a cycle in either
direction, and the gate is `mvn -q validate` then `mvn -pl :notification-service -am verify` with no
dependent builds.

**Reading another module's tables.** The audience snapshot queries `security_user` and
`security_user_role`, which belong to `identity-projection-spring-boot-starter`. That is reading
entities this service already has on its classpath and already queries for recipient resolution, not
a new coupling. What this change must **not** do is write to them.

### POM changes

**None.** No change to `pom.xml` (root), none to `build/ludwig-bom/pom.xml` (no new third-party
dependency), none to `build/ludwig-service-parent/pom.xml` (no new build decision).
`scripts/manifest.sh build` should not be needed; if a POM does change it must be re-run and
`scripts/manifest.sh stale` must be clean before the gate.

### Nothing centralised is re-implemented

- Operations → `web-core`'s envelope and `OperationResponses`, own run table, no second status enum.
- Locking → `job-core`'s `RunLock`, as the digest and retention schedulers use.
- Exactly-once → the existing unique `dedupKey`; no second idempotency mechanism.
- Dispatch, retry, backoff, rate limiting, suppression → the existing queue, unchanged.
- Audit → `audit-core`'s single `AuditSink` for publish, content correction and cancel; typed record
  with `toAuditEvent()`, no try/catch around `record`, no `*.audit` logger, no second redaction mask.
- Problems → `web-core`'s single pipeline; no `@RestControllerAdvice` here.
- Query options, paging, published filterable surface → `odata-filter-spring-boot-starter`.
- Caller locale and zone → `web-core`'s `UserPreferences`. Note the distinction the platform draws
  and keep it: an announcement is rendered once for an audience, so it has **no** single recipient
  whose stored preferences decide its language — see Open Questions.

## Risks / Trade-offs

- **The emailed snapshot and live visibility diverge.** Somebody who loses a role mid-run may still
  be emailed. → Specified and tested rather than hidden; the run is minutes not hours; and the
  README states it. Re-checking membership per row would mean a long run sends to a different
  audience than the one approved, which is worse.
- **`EVERYONE` + email is a foot-gun.** One request can create 100 000 deliveries. → The rate limit
  is already cluster-wide and applies; the fan-out is cancellable; publishing needs its own role; and
  the category catalogue decides which categories may email at all, so it cannot be done by a request
  flag. Not solved: nothing stops an authorised announcer from doing it deliberately.
- **Two feeds and two badges.** → Accepted; see D1. Probably the right product shape anyway.
- **A long-lived announcement accumulates markers.** An `EVERYONE` notice open for 90 days that most
  people dismiss approaches one row per user. → Bounded by the window, purged with the announcement
  in one cascade, and still an order of magnitude below fan-out-on-write because only *dismissers*
  get a row.
- **`PreferenceEvaluator` changes for every existing caller.** → The split is behaviour-preserving
  for `TRANSACTIONAL` and `MARKETING`, both arms are asserted unchanged, and exhaustiveness makes a
  future class a compile error rather than a silent default.
- **Reading another module's tables for the snapshot.** → Already done for recipient resolution;
  read-only; and the alternative — an audience SPI in `identity-projection` — would be a shared
  abstraction with one consumer, which this repository's own rule says to defer.

## Migration Plan

1. Four changesets, formatted SQL, ids `notification-00NN-<slug>`, author `ludwig-notification`,
   each `dbms:postgresql` with a real `--rollback`: `announcement`, `announcement_content` (keyed
   `(announcement_id, locale)`), `announcement_marker`, `announcement_email_run`. Appended as
   `<include>` elements in order. Numbers continue from 0019, so 0020-0023.
2. Deploy. Announcements are inert: `announcements.enabled` defaults false, the catalogue is empty,
   and `PLATFORM` is used by no existing category. Behaviourally a no-op.
3. Enable per deployment: set `enabled`, declare the catalogue and `targetable-roles`, grant the
   publishing role.

**Rollback.** Roll the image back; the four tables are additive and unreferenced by any existing
query, so an older image runs against the newer schema unchanged. A full schema rollback is the four
`--rollback` statements and is safe only while no announcement is live, which is why step 2 ships the
feature off.

## Open Questions

- Should an expired announcement remain fetchable by identifier until retention removes it, or 404 at
  the moment its window closes? Deferrable — one predicate on one endpoint, no schema impact.
- Should the fan-out emit a lifecycle event on the outbox topic, as deliveries do? Deferrable;
  additive, and this deployment has no broker.
