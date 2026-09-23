# user-settings-spring-boot-starter

***English** · [Русский](README.ru.md)*

The platform's engine for per-user settings, preferences and consents — the data an OIDC provider does
not own. It supplies the storage, the layered resolution, the cache, the validation, the audit trail and
the consent ledger; the consuming service supplies **what settings exist**, as typed constants in its own
code.

```xml
<dependency>
    <groupId>ru.ludwigandreas</groupId>
    <artifactId>user-settings-spring-boot-starter</artifactId>
</dependency>
```

```java
public final class AccountSettings {

    public static final SettingDefinition<ZoneId> TIMEZONE = SettingDefinition
            .of("user.timezone", ZoneId.class)
            .defaultValue(ZoneId.of("UTC"))
            .category("locale")
            .userEditable(true)
            .build();
}

@Bean
SettingDefinitionSource accountSettings() {
    return SettingDefinitionSource.of(AccountSettings.TIMEZONE);
}
```

```java
ZoneId zone = settings.get(PrincipalRef.user(subject), AccountSettings.TIMEZONE);
```

---

## The design constraint this module exists to satisfy

A settings engine has to be reusable across platforms whose settings differ completely. The obvious way
to get there is to make the schema data — a table of keys, types and defaults, editable at runtime — and
that is exactly how a settings service becomes an entity-attribute-value store nobody can refactor. Every
key is a string literal, every value is untyped at the call site, renaming one is a search-and-hope, and
deleting one is unknowable.

**So the schema is code and only the engine is shared.** A `SettingDefinition<T>` is a compile-time
artifact of the service that declares it: referenced rather than spelled, typed at every call site,
visible to "find usages", and renameable by a refactoring. The storage underneath stays generic — a value
is text plus a type discriminator, converted at the boundary through `SettingValueConverter` — and that
trade is acceptable **only because nothing joins, filters or reports on a setting value**. There is no
index on the value column and there will not be one. Do not reach for this pattern for data anyone
reports on.

The cost is honest: adding a setting requires a deploy of the service that declares it. In exchange, no
service is ever handed a setting it does not understand.

---

## Two modes, one read interface

A service that *reads* settings is usually not the service that *owns* them, and they do not share a
database.

| | Owner mode | Projection mode |
|---|---|---|
| Owns the tables | yes | keeps a replica |
| `SettingsLookup` | yes | yes — the same interface |
| `SettingsWriter` | yes | **no bean at all** |
| `ConsentService` | read and write | read only |
| Publishes change events | through the transactional outbox | no |
| Consumes change events | no | yes |

```yaml
# The account service, which owns them
ludwig.user-settings.owner.enabled: true

# The notification service, which reads them
ludwig.user-settings.projection.enabled: true
```

Neither is on by default and a service has to say which one it is. There is no sensible default: guessing
owner would make a read-only service start writing tables it does not own, and guessing projection would
make the owning service silently refuse every write. Enabling both is a startup failure.

Because both modes publish the same `SettingsLookup`, a consuming service is written once and does not
know which mode it runs in — moving it from one to the other is a configuration change.

`SettingsWriter` deliberately does **not** exist in projection mode, rather than existing and throwing.
Business code that injects one fails to start in the wrong mode, instead of failing on the first user who
tries to save a preference.

---

## Resolution

Five layers, most specific first:

```
USER  →  ROLE  →  TENANT  →  PLATFORM  →  DEFAULT
```

| Layer | Where it comes from | Set by |
|---|---|---|
| `USER` | a row for the subject | the subject, or an administrator |
| `ROLE` | a row per role the subject holds | an administrator |
| `TENANT` | a row for the tenant, or a configured tenant default | an administrator / configuration |
| `PLATFORM` | configuration only, so it can be hot-reloaded | operations |
| `DEFAULT` | the `SettingDefinition` itself | the developer |

Every resolved value reports **which layer supplied it**:

```java
ResolvedValue<ZoneId> zone = settings.getAll(ref).resolved(AccountSettings.TIMEZONE);
zone.value();    // Europe/Moscow
zone.layer();    // TENANT
zone.scopeId();  // acme
```

Returning a bare value would answer "what is this user's timezone" and not "why", which is the question
both a settings screen ("inherited from your organization") and an operator debugging a support ticket
actually ask.

**Ordering within the role layer is a policy decision**, handed to the `SettingScopeResolver`: a user in
three roles that each set the same key takes the value from whichever role the resolver listed first. The
shipped resolver preserves the order `AuthorityLookup` returned. "Alphabetical" and "most permissive" are
both defensible and neither is universal, so the engine does not pick.

### `getAll` is one query

```java
ResolvedSettings all = settings.getAll(ref);   // one settings query, every setting
```

The scope set — the subject, each of their roles, their tenant, the platform — is computed *before*
anything is fetched, so the sources are asked once for all of it. The naive implementation, walking the
layers and stopping at the first hit, issues a query per layer per setting; that N+1 would be this
module's defining performance bug, and the `SettingValueSource` SPI is shaped so it cannot happen.

`get(ref, definition)` goes through the same path, so reading five settings in a row, or reading one
inside a loop over recipients, still costs one query. There is an integration test that counts JDBC
statements and asserts exactly one.

To be precise about what "one" counts: **one query against the settings tables**. The role layer asks
`AuthorityLookup` which roles the subject holds, and in a deployment whose roles come from a database
that is a second query — served from the authority cache on any warm request, and already made by the
request's own authorization. The settings module adds none of its own.

**Use `getAll` whenever you need more than one setting for the same subject** — a page, a notification
fan-out, an export.

### Tenant scoping is mandatory

A lookup never crosses a tenant boundary, and there is no overload that could run unscoped:

- every stored row has a non-nullable `tenant_id`, so the query predicate is a plain equality rather than
  `tenant = ? OR tenant IS NULL`;
- `SettingsSubject` — the internal key — cannot be constructed without a tenant;
- a lookup whose tenant cannot be determined is **refused** (`TenantUnresolvableException`) rather than
  run unscoped;
- the tenant an administrator reads within is **their own**, not the target's, so a cross-tenant read
  finds nothing instead of finding data and then being refused;
- the cache is keyed on subject *and* tenant, so one tenant's answer is never served to another's.

Code running outside a request — a queue worker, a scheduled job — has no security context to take a
tenant from and must use the `SettingsSubject` overloads, naming the tenant it already knows:

```java
settings.getAll(new SettingsSubject(PrincipalRef.user(subject), tenantId));
```

---

## Declaring settings

```java
SettingDefinition.of("user.timezone", ZoneId.class)   // key and type are required
        .defaultValue(ZoneId.of("UTC"))
        .category("locale")        // grouping for UIs and for the per-category write metric
        .userEditable(true)        // false by default: self-service editing is a decision
        .pii(false)                // true redacts the value everywhere but the settings table
        .validator(SettingValidator.range(1, 100))
        .jsonEncoded(false)        // true stores the value as JSON; see below
        .description("...")
        .build();
```

Keys are lowercase dot-separated segments (`^[a-z][a-z0-9]*([.-][a-z0-9]+)*$`), enforced rather than
recommended: a key is a wire identifier that appears in events, audit rows and a projection's tables, and
a deployment allowing both `User.TimeZone` and `user.timezone` would have two settings everyone would
call one.

### What is checked at startup

The registry is built from every contributed `SettingDefinitionSource` and validated before the context
finishes starting. Each of these is a deploy that fails rather than a user who finds out:

| Check | Why it is fatal |
|---|---|
| duplicate key with a different declaration | which one wins would depend on bean ordering |
| a type nothing can convert | would fail on the first write, to whoever made it |
| a default that fails its own validation | every read succeeds, returning a value the module would refuse to store |
| a default that does not survive a storage round trip | the value changes the first time anybody saves it |

The same key declared **identically** by two sources is fine, so a definition built by a factory (one
opt-out per notification category, say) may appear in more than one list.

The one thing that cannot move to startup is a definition nobody contributed: there is nothing to
validate. Referencing one fails loudly and specifically with `UnknownSettingException` at first use,
rather than resolving to a default and looking like a data problem.

### Value types

Built in: `String`, `Boolean`, `Integer`, `Long`, `Double`, `BigDecimal`, `Instant`, `Duration`,
`LocalTime`, `ZoneId`, `Locale`, `UUID`, and any `enum`.

Anything else needs either a `SettingValueConverter` bean for that exact type, or `jsonEncoded(true)` on
the definition. JSON is **not** an automatic fallback, deliberately: if it were, no type would ever be
unconvertible and "a definition whose type has no converter" would stop being a startup failure.

`ZoneId` is stored as a region id (`Europe/Moscow`) rather than an offset, because an offset does not
survive a daylight-saving change. Boolean parsing is strict — `Boolean.parseBoolean` maps every
unrecognized string to `false`, and a stored `"yes"` silently becoming "opted out" is exactly the class of
bug this module must not have.

---

## Writing

```java
writer.set(ref, AccountSettings.TIMEZONE, ZoneId.of("Europe/Moscow"));
writer.reset(ref, AccountSettings.TIMEZONE);
writer.setAll(ref, List.of(
        SettingUpdate.of(AccountSettings.TIMEZONE, ZoneId.of("Europe/Moscow")),
        SettingUpdate.reset(AccountSettings.LOCALE)));

// administrative, at another scope
writer.setForScope(adminSubject, SettingScope.tenant("acme"), AccountSettings.TIMEZONE, zone);
```

Every write validates, audits, publishes and evicts, in that order, inside one transaction. A bulk update
validates **all** of its changes before writing **any**, so a settings form that rejects one field leaves
the user looking at a screen that matches the stored state — and reports every rejection, not just the
first.

`reset` does not write the default. It clears the subject's own value so the layers below supply one
again; storing the default would pin the setting against a later change to the tenant default, which is
the opposite of what "reset" means.

A reset leaves a **tombstone** rather than deleting the row. That is what makes the projection
order-tolerant: with the row gone there would be nothing to compare a late "set" event against, and the
value would come back from the dead. Tombstones are purged by the retention job.

Two concurrent writes to the same setting race on the unique constraint and one gets a
`DataIntegrityViolationException`. That is left to propagate rather than retried: the loser's value would
otherwise overwrite the winner's under a last-writer-wins rule nobody chose.

### Who may write what

A subject may read and write **their own** settings. Anything else needs the configured administrative
authority **and** the same tenant. Enforced inside the module rather than in each caller, because a rule
reimplemented per endpoint is a rule one endpoint will get wrong — and the one that gets it wrong is the
endpoint that takes a subject id from the path and passes it straight through.

`userEditable(false)` stops the *subject* editing their own value. An administrator managing that setting
is unaffected: "the user may not change this" and "nobody may change this" are different statements.

**Administrative reads of another subject's settings are audited**, whether or not they were served from
the cache. Self-service reads are not — a row per page view would bury the ones that matter.

---

## Consents

Consents are not settings. A setting is current state and is meant to be overwritten; a consent is
evidence and is meant to accumulate. Giving them one interface would have meant one storage model, and
the model that suits a setting — upsert in place, last write wins — destroys the only thing a consent is
for.

```java
consents.grant(ref, ConsentGrant.builder()
        .consentKey("marketing.email")
        .textVersion("2026-01-v3")     // required
        .locale("ru-RU")
        .evidenceIp(request.getRemoteAddr())
        .evidenceUserAgent(request.getHeader(USER_AGENT))
        .build());

consents.revoke(ref, grant);                       // a new row, never an update
consents.currentState(ref).isGranted("marketing.email");
consents.currentState(ref).isGrantedForVersion("marketing.email", "2026-02-v4");
consents.stateAsOf(ref, whenTheEmailWentOut);      // "prove what they consented to on date X"
```

**Append-only, enforced three times.** `UserConsentEntity` extends db-core's `SnapshotEntity`, so the
`SnapshotImmutabilityListener` rejects any `@PreUpdate`; a database trigger rejects any `UPDATE` that
bypasses the ORM; and no code path in this module updates a consent row. Append-only is a claim made to
auditors, and one enforced only by convention is not worth making. The trigger guards `UPDATE` and
deliberately not `DELETE`, so a retention policy can still be carried out.

**The text version is required** because without it the record answers "did they accept?" and not "what
did they accept?", and only the second one is a defence. A grant against `v3` does not answer for `v4`.

**Current state is derived, not stored.** There is no current-consent table and no boolean column: either
would be a second copy of a fact the ledger already holds, and the two would eventually disagree — a bad
position to be in about consent specifically. A subject accumulates a handful of decisions over their
lifetime, so folding them on read is cheaper than the machinery keeping a summary in step would need.

The primary key is assigned by the owner and reused verbatim by every projection, so a redelivered event
collides on the key instead of appending a second copy of the same decision.

---

## Projection mode

The consumer mirrors `identity-projection-spring-boot-starter`'s discipline exactly, and for the same
reasons:

- **Idempotent.** A setting event carries the complete state of one row; a consent event carries the
  owner's primary key. Applying either twice changes nothing.
- **Order-tolerant.** Kafka orders only within a partition, and a subject's events move between
  partitions when the topic is scaled. An event older than what is stored is dropped by comparing
  `occurredAt` against the stored `changed_at` — without that a replay would restore a preference the
  user has since changed, which the user sees as their settings spontaneously reverting.
- **After-commit eviction.** The cache is cleared once the projecting transaction commits. Evicting
  inside it opens a window in which another thread re-reads the old row and repopulates the cache with
  the stale value — and the eviction would still have happened had the transaction rolled back.
- **Manual acknowledgement.** The module runs its own listener container with `AckMode.MANUAL`. A record
  that was applied or deliberately dropped is acknowledged; one whose application *failed* is not, so the
  container redelivers it. With the default batch acknowledgement the offset would advance regardless,
  and a change lost to a database outage would be lost for good.
- An unparseable payload is dropped, because replaying it will fail identically forever. An event type
  this replica does not know is ignored, which is what lets the owner add a fifth event type without
  coordinating a release.

Events are dispatched on the `event-type` header the outbox's Kafka dispatcher writes — not guessed from
the payload's shape, which works until two types overlap and then silently applies the wrong one.

| Event type | Payload | Published when |
|---|---|---|
| `UserSettingChanged` | `UserSettingChangedEvent` | a value is set, changed or reset at any scope |
| `ConsentGranted` | `ConsentChangedEvent` | a consent is granted |
| `ConsentRevoked` | `ConsentChangedEvent` | a consent is withdrawn |

The ordering key is the scope (`tenant/LAYER/scopeId`), so every change to one user, one role or one
tenant is delivered in the order it was made.


---

## Seeding a new projection: the backfill

The change stream carries **changes**. Nothing else. That is exactly right for keeping a replica current
and exactly wrong for creating one, and the gap between those two is the single most common way to get a
wrong answer out of this module.

Consider the case that motivates the whole feature. A service that has never read settings now needs
`user.timezone`, a setting that has existed for two years and is already used elsewhere. Declaring it
costs nothing:

- it is a constant from the shared definitions jar, so the new service imports the same
  `SettingDefinition` the owner declares;
- the registry accepts the same key declared by several sources as long as the declarations are
  identical, and fails at startup naming both if they have drifted;
- **there is no migration.** A setting is a row in `user_setting_value`, not a column. Adding the
  fortieth setting to a service that read thirty-nine changes nothing about its schema.

Then the service starts, and its replica is empty. Every user who set their timezone two years ago and
has not touched it since produces no event, ever — so the new service resolves the definition default for
exactly the people who configured it most deliberately, and nothing anywhere reports a problem.

### Why the obvious workarounds do not close it

**Replaying the topic from the earliest offset** works, and is the cheapest thing to try: the projection
is idempotent and order-tolerant, so replayed events either insert or are dropped as stale. Its limit is
retention. A topic that keeps seven days gives you seven days of *changes*, not current state.

**Log compaction does not work here**, and it is worth saying so plainly because it is the first thing
most people reach for. The outbox's Kafka dispatcher uses the ordering key as the record key, and for
settings that key is `tenant/LAYER/scopeId` — the *subject*, not the subject and the setting together. A
compacted topic would therefore keep each subject's most recently changed setting and discard the rest.
Keying per setting instead would make compaction work and would give up the per-subject ordering the
projection relies on; that trade is winnable, but it is a change to the event contract, not a broker
setting.

### What the backfill does instead

`SettingsBackfillService` reads the owner's current state and republishes it as ordinary
`UserSettingChanged` and consent events, so a projection reaches the right state through the only code
path it has — no second consumer, no import format, no table copied by hand.

```java
@RequiredArgsConstructor
class SettingsRunbook {

    private final SettingsBackfillService backfill;

    void seedNewConsumer() {
        // One tenant, one setting: the new-consumer case.
        backfill.backfill(SettingsBackfillRequest.forSettings("acme", "user.timezone"));

        // Everything for one tenant: a replica rebuilt after a bad deploy.
        backfill.backfill(SettingsBackfillRequest.forTenant("acme"));

        // Every tenant, in bounded chunks so a long run can be watched.
        SettingsBackfillResult result = backfill.backfill(SettingsBackfillRequest.builder()
                .batchSize(1000)
                .maxRows(100_000)
                .build());
        if (!result.complete()) {
            // Run it again. There is no cursor to carry.
        }
    }
}
```

The bean exists in owner mode without any property being set, deliberately: the moment an operator needs
it is a poor moment to discover it needs a redeploy first.

### Why it is safe to run at any time

**Every republished event carries the owner's original `changed_at` as its `occurredAt`.** This is the one
line the whole feature rests on. A projection compares that against what it holds and drops anything that
is not newer, so a backfill against an already-current replica publishes a great deal of traffic and
changes nothing. There is no "is it safe yet" question to answer first — which is the property that makes
it usable during an incident.

The same property makes it resumable by simply running it again. A run that is interrupted has published
valid events for the rows it reached; a re-run starts from the beginning and the projection drops them.

Three more decisions worth knowing about:

- **Tombstones are republished as removals.** A replica seeded with only the live rows, having previously
  been told about a value that was later reset, would serve the resurrected value forever.
- **Consents go out through a separate publisher method that omits the outbox idempotency key.** The
  normal path stamps the consent id as that key so an at-least-once handler cannot queue a decision
  twice — which is precisely wrong for a replay, because the original publication's row may still be
  there to swallow it. Dropping the key is safe: the projection keys consent rows on the owner's id and
  never updates them, so de-duplication lives at the consumer where it belongs.
- **No audit rows, and no redaction.** A backfill changes nothing, so a change-trail entry per row would
  record millions of non-events on a table the retention job already exists to keep down. And a
  republished event carries PII-flagged values just as the original change event did — replicating the
  data is the point. A deployment that does not want a setting's values leaving the owner should not
  project them at all.

### Scanning, batching and the tenant

The scan is keyset pagination on the primary key, resumed from the last id read. An `OFFSET` scan over a
large table re-walks everything it has already skipped and, worse, silently loses rows when a concurrent
write shifts the ordering underneath it.

Each batch is its own transaction. A backfill can walk millions of rows, and doing that in one
transaction would hold it open for minutes against tables that are also serving live writes. (This is
also why the batch publisher is a separate bean from the service: a method calling a `@Transactional`
method on the same object bypasses the proxy, so the loop and the batch have to live in different beans
for the annotation to mean anything.)

`maxRows` is a budget, not a cap — the scan stops after the batch that crosses it, so a run can publish
up to one batch more than asked.

It is also **shared across both scans, and settings are scanned first.** A budget smaller than the
settings table will therefore never reach the consents, no matter how many times it is re-run, because a
re-run starts from the beginning as well. Whenever you set a budget at all, run the two halves
separately:

```java
backfill.backfill(SettingsBackfillRequest.builder()
        .tenantId("acme").maxRows(100_000).includeConsents(false).build());
backfill.backfill(SettingsBackfillRequest.builder()
        .tenantId("acme").maxRows(100_000).includeSettings(false).build());
```

`tenantId` is the one field in this module that may be null, and null means every tenant. Rebuilding a
replica means rebuilding it for everybody, and an operator who had to enumerate tenants first would need
a query this module deliberately does not offer. The REST endpoint cannot express it: its request body
has no tenant field and the controller pins the caller's own.

### The endpoint

```
POST /admin/settings/backfill
```

Mounted only when `ludwig.user-settings.web.enabled=true` **and**
`ludwig.user-settings.web.backfill-enabled=true` **and** the service is in owner mode. Three conditions
for one endpoint, because it is the only one here whose blast radius is a whole tenant rather than one
subject. It requires the administrative authority outright — a backfill is not about a subject, so there
is no self-service case that could apply.

```json
{ "settingKeys": ["user.timezone"], "includeConsents": false, "maxRows": 50000 }
```

```json
{ "settingRows": 41230, "consentRows": 0, "batches": 83, "complete": false }
```

`complete: false` means the row budget was reached; call again. The call runs to completion before it
responds, which is fine with `maxRows` set and a poor idea without it on a large estate.

### Running it will spike `projection.dropped`

That counter is the backfill working — a replica that is already current rejects everything it is sent.
Any alert on stale drops should be written to tolerate a backfill, or it will page somebody every time
one runs.

### The one thing it cannot fix

A backfill republishes what the owner **currently holds**. It cannot recover a value that was changed
twice while a consumer was down: the replica catches up to the current value, not to the intermediate one
it missed. For settings that is the right answer — current state is all a resolution needs. For the
consent ledger it matters more, which is why consents are republished row by row rather than as a
current-state snapshot: the ledger *is* the history, so replaying every row restores it in full.

---

## Caching

A `SettingsCache` SPI modelled on `security-spring-boot-starter`'s `AuthorityCache`, with Caffeine and
no-op implementations, the same coalescing `get(subject, loader)` — concurrent misses for one subject run
the loader once, rather than releasing one query per in-flight request at the moment traffic is highest.

**The TTL here is a performance decision, not a security window.** `AuthorityCache`'s TTL is how long a
revoked role keeps working, which is why that one is measured in seconds. A stale setting means a user
briefly sees an old preference, with no privilege attached — so the default is five minutes, and raising
it trades freshness for load rather than lengthening an attack window.

The TTL is **not** the consistency mechanism. Eviction is: after commit on a local write, and after
commit on a projected event. The TTL is the backstop for when one of those is missed — a dropped event, a
consumer that was down — so a wrong value heals on its own instead of persisting until the next restart.

One cache entry holds a subject's *whole* resolved set. One entry per (subject, setting) would make
`getAll` uncacheable: a page needing twenty settings would find twenty entries and still have no way to
know it had them all.

A write at a role, tenant or platform scope clears the whole cache. Who is affected is "everybody holding
that role", which the writer cannot enumerate without querying the directory — and these writes are rare
and administrative.

---

## PII and audit

A definition carrying `pii(true)` has its value **redacted everywhere except the settings table itself**:
in the audit trail, in logs, in problem documents and in administrative API responses. Never in a metric
tag — no metric in this module is tagged with a setting value or a subject id, both of which would be
unbounded, caller-controlled cardinality and, for a value, personal data published into a store no
erasure request will ever reach.

Redaction is a fixed marker, not a hash or a truncation: a hash is reversible for any value drawn from a
small set, and a truncation leaks exactly the part of an identifier that identifies.

The rejected value is omitted from a validation problem **unconditionally**, flagged or not. A rule that
omitted it only for flagged settings would depend on every definition having been flagged correctly.

Every change records who, when, which definition, old → new, which layer, and the request correlation id
— which is what joins an audit row to the logs, traces and downstream calls of the same request.

### Retention

| Table | Default retention | Purged automatically |
|---|---|---|
| `user_setting_audit` | 365 days | yes, when `retention.enabled=true` |
| `user_setting_value` tombstones | 30 days | yes, when `retention.enabled=true` |
| `user_consent` | — | **never** |

The purge runs in bounded batches on a fixed delay: a single unbounded delete over a year of rows takes a
long lock and a lot of WAL on a table that is also on the write path of every settings change. Each pass
removes at most `batch-size` rows and the scheduler comes back for the rest.

**Consents are never purged on a schedule.** `SettingsRetentionService.purgeConsents` exists, works, and
is called by nothing in this module. How long consent evidence must be kept is a legal question with a
different answer per jurisdiction and per consent, and a default that quietly deleted it on a timer would
be destroying the evidence the ledger was designed to preserve. An operator acting on a written retention
policy calls it; a scheduler does not.

---

## Well-known settings

An **opt-in** package of the definitions the platform shares:

```java
@Bean
SettingDefinitionSource platformSettings() {
    return WellKnownSettings.source();   // user.locale, user.timezone, quiet hours, digest
}

@Bean
SettingDefinitionSource notificationOptOuts() {
    return SettingDefinitionSource.of(
            WellKnownSettings.channelOptOut("billing", "email"),
            WellKnownSettings.channelOptOut("billing", "chat"));
}
```

Nothing there is auto-registered. Auto-registering would have been one line and would have broken the
module's central promise — that `getAll` returns exactly the settings *this* service declares. A service
with four settings of its own would suddenly resolve nine, four of which it has no screen for and no
intention of honouring, and a user editing one would watch their preference be ignored.

Several of these describe notification behaviour, and **this module does not depend on
`notification-service`** — the dependency runs the other way. The preference belongs with the user's
other settings; what a service does with it is that service's business.

`QuietHours` is one setting rather than three (`enabled`, `start`, `end`) because in a layered engine
each would resolve independently, so a user overriding only the end time would silently inherit the
tenant's start time and get a window nobody configured.

---

## Platform integration

| Module | What this one uses it for |
|---|---|
| `security-spring-boot-starter` | `PrincipalRef` as the lookup key, `LudwigPrincipal` for the self-or-admin check, `AuthorityLookup` for the role layer |
| `db-core` | `AuditedEntity`, `SnapshotEntity` + its immutability listener, `BaseRepository` |
| `web-core-spring-boot-starter` | every failure is a `LocalizedException`; the module contributes a message bundle and one `ExceptionProblemMapper`, and ships no `@RestControllerAdvice` |
| `outbox-spring-boot-starter` *(optional)* | owner-mode change events, in the same transaction as the change |
| `observability-spring-boot-starter` *(optional)* | the correlation id on every audit row |
| `odata-filter-spring-boot-starter` *(optional)* | administrative search; see below |
| `hot-reload-spring-boot-starter` *(optional)* | tenant and platform defaults reloadable without a redeploy |
| `micrometer` *(optional)* | `SettingsMetrics`, with a no-op pair |

### Administrative search cannot expose a value

OData filtering is a deny-by-default allow-list, and `value_text` carries no `@Filterable` — nor do the
consent evidence fields or the audit trail's old/new values. Whether a value is personal data is a
property of its *definition*, not of the column (one column holds every setting's value), so there is no
per-row rule that could let the safe ones through and hold the flagged ones back. The only enforceable
answer is that no setting value is filterable — which is also no real loss, since "find every user whose
timezone is Europe/Moscow" is a reporting question and this schema is explicitly not built to answer
reporting questions.

### Hot-reloadable defaults

With `hot-reload-spring-boot-starter` present, the tenant and platform default layers are read through a
live view and the settings cache is cleared on every reload — so "new users should get the weekly digest
from now on" is an edit rather than a release. The definitions themselves are **not** reloadable: a
definition is a compile-time artifact, and a reload that could add or retype one would be the runtime
schema this module was built to avoid.

### REST endpoints

Off by default (`ludwig.user-settings.web.enabled`). A service has to be able to own its own API shape —
paths, DTOs, versioning, OpenAPI groups — and a starter that mounted endpoints without being asked would
be deciding that for it. What they are for is the service that has no opinion yet.

| Method | Path | Notes |
|---|---|---|
| `GET` | `/me/settings` | the caller's own, resolved |
| `PUT` | `/me/settings` | bulk update; values are encoded text |
| `POST` | `/me/settings/reset` | a body rather than a path variable, because keys contain dots |
| `GET` | `/me/settings/consents` | current state |
| `POST` | `/me/settings/consents/grant` \| `/revoke` | evidence is taken from the request, never from the body |
| `GET` | `/admin/settings/subjects/{subject}` | PII values redacted; audited |
| `PUT` | `/admin/settings/scopes/{scopeType}/{scopeId}` | owner mode only |
| `GET` | `/admin/settings/subjects/{subject}/consents[/as-of?at=…]` | the ledger, and any past instant |

The self-service controller takes no subject from the request at all — there is no path variable to get
wrong, which is a stronger guarantee than checking one.

---

## Configuration

| Property | Default | What it does |
|---|---|---|
| `ludwig.user-settings.enabled` | `true` | master switch for the module's autoconfiguration |
| `ludwig.user-settings.default-tenant` | `default` | tenant used when the caller has none. **Set it empty in a multi-tenant deployment**, so a caller with no tenant is refused rather than filed under a shared key |
| `ludwig.user-settings.owner.enabled` | `false` | own the tables, serve writes, publish events |
| `ludwig.user-settings.owner.publish-events` | `true` | publish through the transactional outbox; falls back to publishing nothing when the outbox is absent |
| `ludwig.user-settings.projection.enabled` | `false` | keep a read-only replica of another service's settings |
| `ludwig.user-settings.projection.topic` | `user.settings` | the owner's change topic |
| `ludwig.user-settings.projection.group-id` | `${spring.application.name}-user-settings-projection` | every replica needs its own group |
| `ludwig.user-settings.projection.source-system` | `user-settings-owner` | recorded as `source_system` on projected consent rows |
| `ludwig.user-settings.cache.enabled` | `true` | falls back to always-miss when Caffeine is absent |
| `ludwig.user-settings.cache.ttl` | `5m` | backstop for a missed eviction, not the consistency path |
| `ludwig.user-settings.cache.maximum-size` | `10000` | bound on cached subjects |
| `ludwig.user-settings.access.admin-authority` | `ROLE_SETTINGS_ADMIN` | accepted with or without the `ROLE_` prefix |
| `ludwig.user-settings.access.allow-unauthenticated` | `true` | allow in-process callers with no principal (queue workers, jobs) |
| `ludwig.user-settings.web.enabled` | `false` | mount the shipped REST controllers |
| `ludwig.user-settings.web.base-path` | `/me/settings` | self-service base path |
| `ludwig.user-settings.web.admin-base-path` | `/admin/settings` | administrative base path |
| `ludwig.user-settings.web.backfill-enabled` | `false` | mount `POST /admin/settings/backfill`. Separate from `web.enabled` because it is the one endpoint whose blast radius is a whole tenant; the `SettingsBackfillService` bean exists in owner mode either way |
| `ludwig.user-settings.retention.enabled` | `false` | run the audit and tombstone purge |
| `ludwig.user-settings.retention.audit` | `365d` | how long a change-trail entry is kept |
| `ludwig.user-settings.retention.tombstones` | `30d` | how long a reset tombstone is kept |
| `ludwig.user-settings.retention.batch-size` | `500` | rows removed per pass |
| `ludwig.user-settings.retention.interval` | `1h` | fixed delay between passes |
| `ludwig.user-settings.metrics.enabled` | `true` | Micrometer instead of the no-op metrics |
| `ludwig.user-settings.liquibase.enabled` | `true` | apply the shipped changelog as an independent `SpringLiquibase` |
| `ludwig.user-settings.platform-defaults[<key>]` | — | deployment-wide default, as encoded text |
| `ludwig.user-settings.tenant-defaults[<tenant>][<key>]` | — | per-tenant default, as encoded text |

Setting keys contain dots, so configured defaults need bracket notation:

```yaml
ludwig:
  user-settings:
    platform-defaults:
      "[user.timezone]": Europe/Moscow
    tenant-defaults:
      acme:
        "[user.locale]": ru-RU
```

A configured default for a setting no definition declares is skipped rather than fatal: configuration
outlives deployments, and a retired setting's leftover YAML should not stop a service.

---

## Schema

Three tables, applied by an independent `SpringLiquibase` alongside the application's own changelog —
the same pattern `outbox-spring-boot-starter` uses, with changeset ids namespaced `usrset-NNN`.

| Table | Holds |
|---|---|
| `user_setting_value` | one value per `(tenant, scope type, scope id, setting key)`, plus tombstones |
| `user_setting_audit` | who changed what, when, from what to what, at which layer |
| `user_consent` | the append-only consent ledger |

The same three tables serve both modes. A projection is a replica, not a different shape, and giving it
its own schema would mean two sets of migrations to keep in step forever — and a bug fixed in one of them.

To control migration order across modules, set `ludwig.user-settings.liquibase.enabled=false` and include
`classpath:db/changelog/user-settings/user-settings-changelog.xml` from your own master changelog.

---

## Extension points

Every bean is `@ConditionalOnMissingBean`, so each of these is a replacement rather than a fork:

| SPI | Replace it when |
|---|---|
| `SettingScopeResolver` | inheritance follows groups, org units or something else |
| `SettingsTenantResolver` | your tenants are not the security module's tenants |
| `SettingValueSource` | values also come from somewhere this module does not know about |
| `SettingValueConverter` | a type the built-ins do not cover, or a different encoding for one they do |
| `SettingsCache` | a different cache implementation |
| `SettingsMetrics` | a different metrics backend |
| `SettingsEventPublisher` | events go somewhere other than the outbox |

Replacing any of them leaves precedence, caching, validation and auditing alone.

---

## Metrics

| Meter | Tags |
|---|---|
| `ludwig.user.settings.cache` | `result` = `hit` \| `miss` |
| `ludwig.user.settings.resolution` | — (timer, with a percentile histogram) |
| `ludwig.user.settings.resolution.size` | — (summary of settings per resolution) |
| `ludwig.user.settings.write` | `category`, `layer`, `action` = `set` \| `reset` |
| `ludwig.user.settings.consent` | `consent`, `decision` |
| `ludwig.user.settings.admin.read` | — |
| `ludwig.user.settings.projection.dropped` | `reason` = `stale` |
| `ludwig.user.settings.value.unreadable` | `layer` |
| `ludwig.user.settings.backfill.published` | `kind` = `setting` \| `consent` |

A falling cache hit ratio usually means eviction is firing more than it should. A rising
`projection.dropped` means the owner is republishing or the topic was repartitioned — expect it to spike
whenever a backfill runs, which is the backfill working. Resolution latency
rising without a traffic change usually means the scope set per subject has grown — normally roles.

---

## Testing

173 tests: unit coverage of resolution precedence, startup validation, conversion, cache coalescing,
access policy, projection ordering, backfill paging and its row budget, and context startup refusing a
broken definition; Testcontainers integration tests against a real PostgreSQL and a real broker covering
owner mode, projection mode, out-of-order and replayed events, after-commit eviction, tenant isolation,
consent immutability against the database trigger, a statement-counting assertion that `getAll` issues
exactly one query, and a backfill asserted against real outbox rows — that it republishes the stored
`changed_at` rather than the current time, that it carries tombstones, that it pages through more rows
than one batch holds, and that a consent replay is not swallowed by the original publication's
idempotency key; and an `ArchitectureTest` over the shared ArchUnit rule library.
