## Why

The platform has no way to tell *everybody* something. Release notes, an incident notice, a
deprecation warning — each is one message for an audience defined by a rule ("all users", "everyone
with `ADMIN`") rather than by a list of names, and today the only way to send one is for a calling
service to enumerate its users and submit two hundred requests of five hundred recipients each.

That works and is the wrong shape, measurably. One 4 KB release note to 50 000 users becomes 50 000
delivery rows, 50 000 inbox items and **50 000 copies of the same body** — about 200 MB per
announcement. Worse than the size: a user who joins tomorrow never sees it, a typo cannot be
corrected, and the inbox's read-anchored retention (deliberately immune to age, which is right for
"your invoice is ready") means the 40 000 copies nobody opens are kept forever.

An announcement is a different shape from a notification. A notification is **N documents with one
reader each**; an announcement is **one document with N readers**. Modelling it as the former is what
produces all of the above. Modelled as the latter it is one row, new users see it automatically,
correcting it is an `UPDATE`, and retention is a deletion.

This change assumes `add-in-app-notifications` is applied: it builds on `IN_APP`, the inbox, and the
`INTERRUPTING`/`PASSIVE` classification.

## What Changes

### The announcement aggregate

- **One row per announcement**, carrying its audience as a *predicate* — `audience_kind` plus
  `audience_value` — rather than a materialized list of subjects. Plus a visibility window
  (`visible_from`, `visible_until`), the template key, the category and the rendered content.
- **Two audience kinds**: `EVERYONE`, and `ROLE(code)` resolved against
  `identity-projection`'s `security_user_role`, which already carries an index on `role_code`.
- **Visibility is evaluated live, at read time.** The caller's roles are already resolved onto the
  principal, so the query is an `IN` over values held in memory — plain QueryDSL, no `UNION`, no
  native SQL. A person who loses `ADMIN` stops seeing an admin-only announcement immediately; a
  person who gains it sees the backlog. Revocation latency is the same argument the security module
  uses to settle reading roles from a projection rather than from a token claim.
- **A recipient can dismiss an announcement**, which writes one lazy `read_marker` row. An
  announcement nobody has touched has no per-user rows at all.

### Its own feed, not the inbox's

- `GET /api/v1/announcements`, `GET /api/v1/announcements/unread-count`, and
  `POST /api/v1/announcements/{id}/dismiss` — separate from `/api/v1/inbox`.
- Two reasons, and the second is the important one. JPQL has no `UNION` and QueryDSL-JPA generates
  JPQL, so a single merged feed cannot page, sort and count across two sources without a third
  native-SQL carve-out. And the inbox read path is currently safe because of one predicate,
  `owner = me`; an announcement has no owner row and its visibility is *derived*, so threading it
  into that path replaces a trivially-correct filter with a computed one on the endpoint every user
  polls. Keeping it separate leaves the inbox's safety property intact and gives the derived query
  its own deliberately-written home.

### How a broadcast is configured, and by whom

The split is the same argument `PreferenceEvaluator` already makes about the transactional bypass:
anything an announcer can set, every announcer will set to the most permissive value.

- **Data, per announcement, via the API**: the audience, the template, the variables, the window.
- **Policy, per deployment, via configuration**: `allowed-audiences`, a **`targetable-roles`
  allowlist**, `max-visibility-window`, and a **category catalogue** that decides the channels and
  the category class. A request carries no `channels` field and no `alsoEmail` flag.
- `targetable-roles` is an allowlist rather than "any role" deliberately: a free-form role target is
  an enumeration primitive for the role space, and lets an announcer address a role whose membership
  is itself sensitive.

### A third category class

`PLATFORM` — undeclinable, but not urgent:

| class | per-category opt-out | quiet hours (email) |
|---|---|---|
| `TRANSACTIONAL` | bypassed | bypassed |
| **`PLATFORM`** | **bypassed** | **honoured** |
| `MARKETING` | honoured | honoured |

This is a behaviour combination neither existing class can express, which is the argument for adding
one rather than reusing. Forced into `TRANSACTIONAL` a release note emails people at 03:00; forced
into `MARKETING`, half the estate has opted out of the incident notice. It requires splitting
`PreferenceEvaluator`'s single bypass into its two halves.

### Email fires from the announcement

- Where the category's channels include `EMAIL`, publishing starts a **resumable, batched fan-out**
  that pages the audience and creates ordinary `notification_delivery` rows — so the existing
  dispatcher, retry, backoff, rate limiting and suppression all apply unchanged.
- The email audience is a **snapshot**, necessarily: an email is physically sent at an instant. The
  inbox half stays live. This is the `INTERRUPTING`/`PASSIVE` split deciding a question it was not
  designed for, for the third time.
- **This one genuinely is a long-running operation**, unlike anything in the inbox: it runs for
  minutes, has progress, and can be cancelled. It therefore maps onto `web-core`'s
  `OperationResponse` through its own run table, built with `OperationResponses`, with a cancel
  endpoint answering 202. The inbox deliberately does *not* use the envelope; this does, and the
  contrast is the point.
- Exactly-once across a resume comes free from the existing uniquely-indexed `dedupKey`
  (idempotency key + channel + recipient), so a run that resumes after a lost lease cannot
  double-send.

### Enforcement

Service-local ArchUnit and Checkstyle rules for the mistakes this design makes possible: an
announcement status enum restating `OperationStatus`, a materialized audience on the announcement
row, an announcement visibility query that does not constrain the audience, and a `targetable-roles`
bypass.

**BREAKING**: none. Announcements are inert until a category is configured and an announcer
publishes.

## Non-goals

- **`ORG_UNIT` audiences.** The IdP owns the org structure (`ou_id`, `organization` on its
  `UserEntity`), and `identity-projection` does not carry it yet. Adding it is a separate change
  that touches a shared starter and pulls `crud-service-example` into the gate. The audience model
  here has the hole shaped for it: one more kind, one more `OR` branch.
- **Notification-local user groups.** Deliberately not built. A durable, meaningful grouping of
  people is directory data and belongs in the IdP; a one-off "tell these forty people" is already
  `recipients: [...]` on an ordinary request. The middle case — a reusable, notification-only list —
  is the one that rots, because no leaver process touches it and it quietly announces things to
  ex-employees eighteen months later. Both ends are already served; this change does not buy the
  middle.
- **Acknowledgement.** Dismissal is in scope; "this must stay until acknowledged, and report who has
  not" is a different feature with its own reporting surface.
- **A merged inbox+announcement feed.** See above: it needs a `UNION`, and it would make the inbox's
  read path derived.
- **Editing a published announcement's audience.** Correcting its *content* is in scope and is the
  point; moving it between audiences after publication would change who has already been emailed.
- **Real-time push**, still. Unchanged non-goal from `add-in-app-notifications`.
- **Per-tenant announcement scoping** beyond what the audience predicate already gives.

## Capabilities

### New Capabilities

- `platform-announcement`: the aggregate and its audience predicate, the two audience kinds, live
  read-time visibility, the visibility window, dismissal and its lazy marker, the recipient-facing
  feed and unread count, the `targetable-roles` allowlist, the category catalogue, and the
  `PLATFORM` category class with its distinct bypass semantics.
- `announcement-email-broadcast`: the snapshot audience, the resumable batched fan-out over the
  existing delivery queue, exactly-once across a resume, its mapping onto the long-running-operation
  envelope, cooperative cancellation, and what happens when the audience changes mid-run.

### Modified Capabilities

- `enforcement-triad`: records which of this change's rules are mechanised and by which tool, and
  which cannot be — in particular that no build can check whether a configured category's class is
  the right product answer, which is the same unmechanisable shape as a cache's `CachePurpose`.

## Impact

### Modules

| Module | POM tier | In-repo dependents |
|---|---|---|
| `notification-service` | service (parented by `ludwig-service-parent`) | none |

One module, as `project-index.json` spells it. `notification-service` has no in-repo dependents, so
the gate stays `mvn -q validate` then `mvn -pl :notification-service -am verify`, plus
`scripts/check_migrations.sh`, `scripts/check_image_pins.sh` and
`openspec validate add-platform-announcements`.

**No new in-repo dependency and no POM change expected.** `identity-projection-spring-boot-starter`,
`web-core-spring-boot-starter`, `security-spring-boot-starter`, `odata-filter-spring-boot-starter`,
`job-core` and `db-core` are already `compile` dependencies, so the reactor DAG is unaffected.

### Shared contracts in `openspec/specs/`

**Modifies one**: `enforcement-triad`.

**Consumes without modifying**: `long-running-operations` (the email fan-out is a real operation and
maps onto the envelope through its own run table, which is exactly what that capability prescribes —
and `web-core` stays persistence-free), `odata-query-contract` and `odata-filter-metadata` (the
announcement feed uses the standard options, envelope and published surface), `data-access`
(QueryDSL against generated Q-types; this change claims **no** carve-out and adds no fenced
package), `database-migration`, `problem-detail-pipeline`, `run-lock` (the fan-out job runs under
`job-core`'s leased mutex, as the digest and retention jobs do), `audit-envelope` (publishing and
cancelling are admin actions and go through the single `AuditSink`), `i18n-bundles`, `test-layout`,
`repository-layout`, `pom-topology`.

**Touches none of**: `cache-purpose` (no cache is proposed — but see the design's note on where one
would first be justified and what its `CachePurpose` would have to be), `idempotency-claim`,
`messaging-envelope`, `object-store`, `container-image-pinning`, `module-dependency-direction`,
`interactive-file-action`.

### APIs

New and additive: publish an announcement, list the caller's visible announcements, their unread
count, dismiss one, and poll or cancel an announcement's email fan-out. Nothing existing changes
shape; `PreferenceEvaluator` gains a third category class, which is additive for existing callers
because no existing category uses it.

### Data

Three new tables — the announcement, its rendered content, and the per-user marker — plus one run
table for the email fan-out. No existing column is dropped or re-typed, so the change is
roll-forward safe.

### Documentation

`services/notification-service/README.md` and `README.ru.md`: a new section for announcements beside
the inbox one, the category-class table (now three rows), the configuration block, the retention
table, and the known-gaps list.
