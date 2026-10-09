## Why

Every transport `notification-service` ships — SMTP, internal chat, signed webhook — pushes a
message at a person and is done. There is no way to notify somebody *inside the product*, which is
the one destination that works when a recipient has declined every push channel, has no verified
contact address, or is simply at their desk. Today a recipient who has opted out of email for a
category is unreachable for that category, and a `TRANSACTIONAL` notification to a user with no
verified address becomes a terminal delivery with nowhere to land.

An in-app notification is not another push transport, and that is why this is a change rather than
a bean. It is **stored and read back** — it has an owner, a read state the *recipient* mutates, and
a lifetime that ends when they read it rather than when a retention window closes. Three invariants
the current design states explicitly conflict with it (a channel never touches the database; a
rendered body is purged at seven days; quiet hours defer a delivery because a delivery
interrupts), so adding it by writing a fourth `NotificationChannel` bean would quietly break all
three. This change adds the destination and makes the conflicts explicit and checked.

## What Changes

### The channel side

- **A fourth transport, `IN_APP`**, added to the enum triad `ChannelType` / `ChannelKind` /
  `ChannelTypeDto`. Callers request it like any other channel.
- **`IN_APP` is settled in the fan-out transaction, not dispatched from the work queue.** Its
  delivery goes `ACCEPTED → DELIVERED` in the transaction that accepts the request, alongside the
  existing in-transaction settlements `SUPPRESSED`, `BATCHED` and `DEAD`. It is never claimed,
  leased, retried or backed off, and there is **no `NotificationChannel` bean for it**. The work
  queue exists to keep a slow third-party network off a pooled database connection; `IN_APP` has
  no third-party network, and deferring a row insert out of the transaction that is already open
  is strictly less reliable than doing it there.
- **A channel is classified `INTERRUPTING` or `PASSIVE`**, declared once on `ChannelType` and read
  by the four places that currently assume every delivery interrupts somebody. For a `PASSIVE`
  channel: quiet hours do not apply (an inbox item waits by construction, so deferring it to 08:00
  is pure loss), digest collapsing does not apply (folding an inbox is the inbox UI's job), and
  the address-suppression list does not apply (a hard bounce is a fact about an address, and there
  is no address). Per-category opt-out **does** still apply. One declaration rather than an
  `IN_APP` branch in six evaluators.
- **A category class may name a fallback channel.** When every `INTERRUPTING` delivery for a
  recipient settles as `SUPPRESSED`, a configured fallback produces an `IN_APP` delivery instead,
  so a notification the platform owner has declared undeclinable lands somewhere. Configuration
  per category class, never a flag on the request — the same argument
  `PreferenceEvaluator` already makes for why the `TRANSACTIONAL` bypass is a property of the
  category.
- **An `IN_APP` delivery requires a resolvable `userId`.** An `ADDRESS` recipient has no inbox, so
  that pair is a terminal delivery decided at fan-out rather than an accepted request that fails
  later.

### The inbox side

- **A new aggregate, `notification_inbox_item`**, in its own table with its own retention window.
  It is not columns on `notification_delivery`: that row is a hot work-queue row deliberately kept
  narrow and claimed with `FOR UPDATE SKIP LOCKED` thousands of times a minute, written by the
  dispatcher; an inbox item is a document written by the recipient. This is the same argument the
  service already makes one level up for why retry state lives on the delivery and not the
  request, and jamming both roles into one table forces the retention purge to pick one answer for
  two different questions.
- **The rendered body is stored on the inbox item, not in `notification_delivery_content`.** That
  table is documented as the most sensitive thing the service holds and is purged at
  `content-ttl: 7d`, with a startup check that a body never outlives its delivery. An unread inbox
  item has to survive someone's holiday. An inbox body is also a different hazard class: it is
  what the recipient is *meant* to read.
- **A fifth retention window**, `retention.inbox-ttl`, measured from read rather than from
  creation, so an unread item is never purged out from under its owner.
- **A recipient-facing read API**: list the caller's own inbox with the platform's OData query
  contract, an unread count, mark one read, mark all read, dismiss. Scoped to `self` through the
  security module's existing data-scope mechanism — a caller can only ever address their own
  inbox, and an inbox item's owner is not a filterable field.

### Enforcement

- Service-local ArchUnit and Checkstyle rules that fail the build on the three mistakes this
  design makes possible: a `NotificationChannel` bean that claims to support `IN_APP`, an inbox
  read-state field appearing on a delivery entity, and an `IN_APP` body written to the delivery
  content table. Rules that cannot be mechanised are recorded as comments at the point of the rule.

No breaking change: every existing channel, state, endpoint and property keeps its current
meaning, and `IN_APP` is inert until a caller asks for it or a deployment configures the fallback.

## Non-goals

- **Real-time push.** No SSE, no WebSocket, no STOMP — there is none anywhere in this repository
  today, and freshness by polling the unread count is honest and free. Push is decoration over a
  durable inbox and must never become the delivery mechanism; it is a later change.
- **A separate `ui-notification-service`.** Considered and rejected for now: its only real win is
  leaving the `NotificationChannel` contract untouched, bought with a second deploy unit, a second
  database and an eventual-delivery window. The in-transaction settle is the seam to cut along if
  inbox read volume or product surface later justifies it.
- **An `inbox-spring-boot-starter` library.** One consumer means service code. Promote it when a
  second consumer exists, the way the idempotency store and the run lock were promoted.
- **Mobile/browser push notifications (APNs, FCM, Web Push).** Those are `INTERRUPTING` transports
  and would be separate channels with their own providers, retries and failure classification.
- **Inbox actions beyond read/dismiss.** No CTA buttons, no deep-link contract, no threading, no
  attachments. A deep link would be a template contract change, not a channel change.
- **Per-tenant template overrides** and **provider receipt adapters** remain the known gaps they
  already are.
- **A platform-wide notion of "passive channel".** The `INTERRUPTING`/`PASSIVE` classification is
  service-local to `notification-service`; nothing outside it has a channel vocabulary.

## Capabilities

### New Capabilities

- `in-app-notification`: the `IN_APP` transport — its in-transaction settlement rather than queued
  dispatch, the `INTERRUPTING`/`PASSIVE` channel classification and what each of quiet hours,
  digest and address suppression does under it, per-category opt-out still applying, the
  suppression fallback, and the recipient-resolution requirement.
- `notification-inbox`: the inbox aggregate — ownership, the `seen`/`read`/`dismissed` state and
  who may move it, body storage separate from delivery content, the read-anchored retention
  window, and the recipient-facing read API with its data scoping and non-filterable surface.

### Modified Capabilities

- `enforcement-triad`: records which of this change's rules are mechanised and by which of the
  three tools, and which cannot be and why — the rules introduced here (no `IN_APP` channel bean,
  no inbox read-state on a delivery entity, no `IN_APP` body in the delivery content table) are
  exactly the kind that get broken silently if they exist only as prose.

## Impact

### Modules

| Module | POM tier | In-repo dependents |
|---|---|---|
| `notification-service` | service (parented by `ludwig-service-parent`) | none |

One module. `notification-service` has no in-repo dependents, so the verification gate is
`mvn -q validate` then `mvn -pl :notification-service -am verify`, plus
`scripts/check_migrations.sh` for the new changesets and `openspec validate add-in-app-notifications`.
No POM changes are expected, so `scripts/manifest.sh build` should not be needed; every module the
work consumes — `web-core-spring-boot-starter`, `security-spring-boot-starter`,
`odata-filter-spring-boot-starter`, `db-core`, `identity-projection-spring-boot-starter` — is
already a `compile` dependency, so no new in-repo edge is added and the reactor DAG is unaffected.

### Shared contracts in `openspec/specs/`

The change **modifies one**: `enforcement-triad`, as declared above.

It **consumes without modifying**: `odata-query-contract` and `odata-filter-metadata` (the inbox
list endpoint uses the standard options object, envelope and published filterable surface),
`data-access` (the inbox repository is QueryDSL against generated Q-types — mark-all-read is a
QueryDSL update clause, so this change claims **no** native-SQL carve-out and adds no fenced
package), `database-migration` (new changesets in the one shipped shape),
`problem-detail-pipeline` (inbox errors map through `web-core`'s single pipeline),
`i18n-bundles` (new keys in both locales), `test-layout`, `repository-layout` and `pom-topology`.

It touches **none** of: `long-running-operations` (an inbox write is not a long-running
operation and must not grow an `OperationResponse`), `cache-purpose` (no cache is proposed; an
unread count read straight from Postgres is correct before it is fast), `audit-envelope`,
`run-lock`, `idempotency-claim`, `messaging-envelope`, `object-store`,
`container-image-pinning`, `module-dependency-direction`, `interactive-file-action`.

### APIs

New, recipient-facing and additive: list own inbox, unread count, mark read, mark all read,
dismiss. Existing endpoints change only in that a request may now name `IN_APP` among its
`channels` and a response may report an `IN_APP` delivery already `DELIVERED` at 202 — which the
contract already permits, since the service fans out in the transaction that accepts the request.

### Data

Two new tables (`notification_inbox_item` and its body) and new Liquibase changesets. No column is
dropped or re-typed, and no existing row is rewritten, so the change is roll-forward safe and its
rollback is real SQL rather than `NOT REQUIRED`.

### Documentation

`services/notification-service/README.md` and `README.ru.md`: the state machine diagram, the
channel table, the add-a-channel section (which currently tells the reader that adding a transport
is adding a bean — true for every `INTERRUPTING` channel and now explicitly not the whole story),
the preferences section, the retention table, and the known-gaps list.
