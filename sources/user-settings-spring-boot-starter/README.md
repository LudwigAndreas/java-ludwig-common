# user-settings-spring-boot-starter

***English** · [Русский](README.ru.md)*

The platform's engine for per-user settings, preferences and consents - the data an OIDC provider does not own. It
supplies storage, layered resolution (user → role → tenant → platform → default) that reports which layer answered,
one query per subject, a cache evicted after commit, validation, an audit trail, an append-only consent ledger, PII
redaction, an owner/projection split with change events, and a backfill for seeding new projections. The consuming
service supplies **what settings exist**, as typed constants in its own code.

| | |
|---|---|
| Role | starter |
| Depends on (in-repo) | `audit` starter, `cache`, `db-core`, `hot-reload`, `messaging`, `observability`, `odata-filter`, `outbox`, `security`, `web-core` (several optional at runtime) |
| Used by | `notification-service` |
| Surfaces | optional REST `/me/settings`, `/admin/v1/settings`; Kafka (owner publishes, projection consumes) |

## Usage

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

**The schema is code; only the engine is shared.** A `SettingDefinition<T>` is typed at every call site, visible to
"find usages" and renameable. Storage is generic (text plus a type discriminator), acceptable **only because nothing
joins, filters or reports on a setting value** - there is no index on the value column. Adding a setting needs a
deploy of the declaring service; no service is handed a setting it does not understand.

### Owner and projection modes

| | Owner | Projection |
|---|---|---|
| Owns the tables | yes | keeps a replica |
| `SettingsLookup` | yes | yes (same interface) |
| `SettingsWriter` | yes | **no bean at all** (injecting it fails startup) |
| `ConsentService` | read and write | read only |
| Change events | publishes through the outbox | consumes |

```yaml
ludwig.user-settings.owner.enabled: true        # the service that owns them
ludwig.user-settings.projection.enabled: true   # a service that reads them
```

Neither is on by default; both on is a startup failure.

### Resolution

```
USER  →  ROLE  →  TENANT  →  PLATFORM  →  DEFAULT
```

| Layer | Source | Set by |
|---|---|---|
| `USER` | a row for the subject | the subject or an administrator |
| `ROLE` | a row per role the subject holds | an administrator |
| `TENANT` | a row for the tenant or a configured tenant default | administrator / configuration |
| `PLATFORM` | configuration only (hot-reloadable) | operations |
| `DEFAULT` | the definition | the developer |

```java
ResolvedValue<ZoneId> zone = settings.getAll(ref).resolved(AccountSettings.TIMEZONE);
zone.value();    // Europe/Moscow
zone.layer();    // TENANT
zone.scopeId();  // acme
```

- **`getAll` is one query** against the settings tables (the scope set is computed first; a test counts JDBC
  statements). `get` uses the same path. Use `getAll` whenever you need more than one setting for a subject.
- **Role order** is the `SettingScopeResolver`'s policy; the shipped one keeps `AuthorityLookup`'s order.
- **Tenant scoping is mandatory**: every row has a non-null `tenant_id`; `SettingsSubject` cannot be built without a
  tenant; an unresolvable tenant is refused (`TenantUnresolvableException`); an administrator reads within their own
  tenant; the cache key includes the tenant. Outside a request use the `SettingsSubject` overloads:

```java
settings.getAll(new SettingsSubject(PrincipalRef.user(subject), tenantId));
```

### Declaring settings

```java
SettingDefinition.of("user.timezone", ZoneId.class)   // key and type required
        .defaultValue(ZoneId.of("UTC"))
        .category("locale")        // UI grouping and the per-category write metric
        .userEditable(true)        // false by default
        .pii(false)                // true redacts the value everywhere but the settings table
        .validator(SettingValidator.range(1, 100))
        .jsonEncoded(false)        // true stores the value as JSON
        .description("...")
        .build();
```

Keys match `^[a-z][a-z0-9]*([.-][a-z0-9]+)*$`. **Startup refuses**: a duplicate key with a different declaration (the
same declaration twice is fine), a type nothing can convert, a default that fails its own validation, a default that
does not survive a storage round trip. A reference to a definition nobody contributed fails at first use with
`UnknownSettingException`.

Built-in types: `String`, `Boolean` (strict parsing), `Integer`, `Long`, `Double`, `BigDecimal`, `Instant`, `Duration`,
`LocalTime`, `ZoneId` (region id, not offset), `Locale`, `UUID`, any `enum`. Others need a `SettingValueConverter`
bean or `jsonEncoded(true)` (JSON is never an automatic fallback).

### Writing

```java
writer.set(ref, AccountSettings.TIMEZONE, ZoneId.of("Europe/Moscow"));
writer.reset(ref, AccountSettings.TIMEZONE);
writer.setAll(ref, List.of(
        SettingUpdate.of(AccountSettings.TIMEZONE, ZoneId.of("Europe/Moscow")),
        SettingUpdate.reset(AccountSettings.LOCALE)));

writer.setForScope(adminSubject, SettingScope.tenant("acme"), AccountSettings.TIMEZONE, zone);
```

- Every write validates, audits, publishes and evicts in one transaction; a bulk update validates **all** changes
  before writing any and reports every rejection.
- `reset` clears the subject's value (the lower layers answer again) and leaves a **tombstone** so the projection
  stays order-tolerant; tombstones are purged by retention.
- Two concurrent writes to one setting: the loser gets `DataIntegrityViolationException` (no silent last-writer-wins).
- **Access**: a subject reads/writes their own settings; anything else needs the admin authority **and** the same
  tenant, enforced inside the module. `userEditable(false)` stops the subject, not an administrator.
- **Administrative reads of another subject are audited** (self-service reads are not).

### Consents

Consents are evidence and accumulate; settings are state and are overwritten.

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
consents.stateAsOf(ref, whenTheEmailWentOut);      // what they had consented to at that moment
```

- **Append-only, enforced three times**: `SnapshotEntity` listener, a database trigger rejecting `UPDATE` (not
  `DELETE`, so a retention policy can be carried out), and no update path in code.
- **Text version required**: the record answers *what* was accepted.
- **Current state is derived** by folding the ledger; there is no current-consent table.
- The owner's primary key is reused by projections, so redelivery collides instead of duplicating.

### Projection

- **Idempotent** (a setting event carries the complete row; a consent event carries the owner's id).
- **Order-tolerant**: an event older than the stored `changed_at` is dropped (`projection.dropped{reason=stale}`).
- **After-commit cache eviction.**
- Container from [messaging](../messaging-spring-boot-starter): per-record acknowledgement, backoff, dead-letter
  topic. Unparseable payloads and unknown event types are dropped/ignored.
- Dispatch on the `ludwig-event-type` header (`MessageHeaders`).

| Event type | Payload | Published when |
|---|---|---|
| `UserSettingChanged` | `UserSettingChangedEvent` | a value is set, changed or reset at any scope |
| `ConsentGranted` | `ConsentChangedEvent` | a consent is granted |
| `ConsentRevoked` | `ConsentChangedEvent` | a consent is withdrawn |

Ordering key `tenant/LAYER/scopeId`. Declared as `OutboxEventTypes` in owner mode with publication on.

### Backfill (seeding a new projection)

The change stream carries **changes only**; a new consumer of a two-year-old setting would resolve defaults for
everyone who set it long ago. Replaying the topic is limited by retention; log compaction does not work (the record
key is the subject, not subject + setting). `SettingsBackfillService` (owner mode, always present) republishes the
owner's current state as ordinary events:

```java
backfill.backfill(SettingsBackfillRequest.forSettings("acme", "user.timezone"));   // one setting, one tenant
backfill.backfill(SettingsBackfillRequest.forTenant("acme"));                       // a whole tenant
SettingsBackfillResult result = backfill.backfill(SettingsBackfillRequest.builder()
        .batchSize(1000).maxRows(100_000).build());                                  // every tenant, budgeted
if (!result.complete()) { /* run it again; there is no cursor */ }
```

- **Safe at any time**: events carry the original `changed_at`, so a current replica drops them. Re-running resumes.
- Tombstones are republished as removals; consents are republished row by row without the outbox idempotency key.
- No audit rows (nothing changes); PII values are republished as the originals were.
- Keyset scan, one transaction per batch. `maxRows` is a budget shared by both scans (settings first) - with a budget,
  run settings and consents separately (`includeConsents(false)` / `includeSettings(false)`).
- `tenantId == null` means every tenant (not expressible through REST).
- `POST /admin/v1/settings/backfill` (`{ "settingKeys": [...], "includeConsents": false, "maxRows": 50000 }` →
  `{ "settingRows": 41230, "consentRows": 0, "batches": 83, "complete": false }`) requires `web.enabled`,
  `web.backfill-enabled` and owner mode, plus the `settings.operate` operation; it pins the caller's tenant.
- It cannot recover intermediate values a consumer missed - only current state (and the full consent ledger).

### Caching

The platform cache `settings` (`SettingsCaches`, `purpose: performance`, default TTL 5 min) via
[cache](../cache-spring-boot-starter). One entry holds a subject's **whole** resolved set. Eviction after commit is the
consistency mechanism; the TTL is the backstop. A write at a role, tenant or platform scope clears the whole cache.
Evicting a subject across tenants is `SettingsCaches.evictAcrossTenants` (an O(n) `evictByScan`; the shared Redis tier
is therefore refused for this cache).

### Caller locale and zone for web-core

`StoredUserPreferenceSource` contributes `user.locale` / `user.timezone` to web-core's caller preferences, so every
`Instant` in a response renders in the user's zone. It answers only when the value was set at `USER`, `ROLE` or
`TENANT`; at `PLATFORM`/`DEFAULT` it abstains so `Accept-Language` and configuration still apply. Without those
definitions declared the bean still exists and says so at startup. `ludwig.user-settings.preferences.enabled=false`
switches it off.

### Well-known settings (opt-in)

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

Nothing is auto-registered (`getAll` returns exactly what this service declares). `QuietHours` is one setting, not
three, so layers cannot mix a start from one layer with an end from another.

### REST endpoints (off by default, `web.enabled`)

| Method | Path | Notes |
|---|---|---|
| `GET` | `/me/settings` | the caller's own, resolved |
| `PUT` | `/me/settings` | bulk update; values as encoded text |
| `POST` | `/me/settings/reset` | keys in the body (they contain dots) |
| `GET` | `/me/settings/consents` | current state |
| `POST` | `/me/settings/consents/grant` \| `/revoke` | evidence taken from the request, never the body |
| `GET` | `/admin/v1/settings/subjects/{subject}` | PII redacted; audited |
| `PUT` | `/admin/v1/settings/scopes/{scopeType}/{scopeId}` | owner mode only |
| `GET` | `/admin/v1/settings/subjects/{subject}/consents[/asof?at=…]` | the ledger at any instant |

The self-service controller takes no subject from the request. Administrative OData search exposes no setting value
(`value_text` and consent evidence are not `@Filterable`).

### Extension points

All `@ConditionalOnMissingBean`: `SettingScopeResolver` (inheritance via groups/org units), `SettingsTenantResolver`,
`SettingValueSource`, `SettingValueConverter`, `SettingsMetrics`, `SettingsEventPublisher`.

## Configuration

| Property | Default | Meaning |
|---|---|---|
| `ludwig.user-settings.enabled` | `true` | Master switch |
| `ludwig.user-settings.default-tenant` | `default` | Tenant for callers without one. **Set it empty in multi-tenant deployments** |
| `ludwig.user-settings.owner.enabled` | `false` | Own the tables, serve writes, publish |
| `ludwig.user-settings.owner.publish-events` | `true` | Publish through the outbox (nothing if the outbox is absent) |
| `ludwig.user-settings.projection.enabled` | `false` | Keep a read-only replica |
| `ludwig.user-settings.projection.topic` | `user.settings` | Owner's change topic |
| `ludwig.user-settings.projection.group-id` | `${spring.application.name}-user-settings-projection` | One group per replica service |
| `ludwig.user-settings.projection.source-system` | `user-settings-owner` | `source_system` on projected consent rows |
| `ludwig.cache.caches.settings.enabled` / `ttl` / `maximum-size` | `true` / `5m` / `10000` | The settings cache |
| `ludwig.user-settings.access.allow-unauthenticated` | `true` | In-process callers without a principal (workers, jobs) |
| `ludwig.user-settings.web.enabled` | `false` | Mount the REST controllers |
| `ludwig.user-settings.web.base-path` | `/me/settings` | The administrative endpoints are at the fixed `/admin/v1/settings` |
| `ludwig.user-settings.web.backfill-enabled` | `false` | Mount the backfill endpoint |
| `ludwig.user-settings.retention.enabled` | `false` | Run the tombstone purge |
| `ludwig.user-settings.retention.tombstones` | `30d` | Tombstone retention |
| `ludwig.user-settings.retention.batch-size` / `interval` | `500` / `1h` | Purge batches |
| `ludwig.user-settings.metrics.enabled` | `true` | |
| `ludwig.user-settings.liquibase.enabled` | `true` | Own `SpringLiquibase` bean |
| `ludwig.user-settings.preferences.enabled` | `true` | Contribute locale/zone to web-core |
| `ludwig.user-settings.platform-defaults[<key>]` | - | Deployment default (encoded text) |
| `ludwig.user-settings.tenant-defaults[<tenant>][<key>]` | - | Per-tenant default |

```yaml
ludwig:
  user-settings:
    platform-defaults:
      "[user.timezone]": Europe/Moscow     # keys contain dots: bracket notation
    tenant-defaults:
      acme:
        "[user.locale]": ru-RU
```

A configured default for an undeclared setting is skipped, not fatal. Tenant and platform defaults are hot-reloadable
with [hot-reload](../hot-reload-spring-boot-starter) (the cache is cleared on reload); definitions are not.
`ludwig.user-settings.retention.audit` is bound but not read: the change trail's retention is
`ludwig.audit.retention.by-category.settings` (default 7 years).

## Deployment

- **Schema** `db/changelog/user-settings/user-settings-changelog.xml` (ids `user-settings-NNNN`, own bean or include it
  with `liquibase.enabled=false`): `user_setting_value` (one value per tenant, scope type, scope id, key, plus
  tombstones) and `user_consent` (append-only ledger with an update-blocking trigger). The same tables serve both
  modes. A legacy `user_setting_audit` table, if present, is migrated into `audit_event` by the audit starter's
  `audit-0002` and left for you to verify and drop.
- **Kafka** (projection): the topic and its dead-letter topic `<topic>.dlt` (default `user.settings.dlt`); a distinct
  group id per consuming service.
- **Owner**: the outbox route for the three event types.
- **Audit starter** must be present: `settings` audit writes go to `audit_event` in the caller's transaction.

## Observability

| Metric | Type | Tags | Meaning |
|---|---|---|---|
| `ludwig.user.settings.cache` | counter | `result` (`hit`/`miss`) | Cache effectiveness (also the generic `ludwig.cache.*` meters) |
| `ludwig.user.settings.resolution` | timer (percentile histogram) | | Resolution latency |
| `ludwig.user.settings.resolution.size` | summary | | Settings per resolution |
| `ludwig.user.settings.write` | counter | `category`, `layer`, `action` (`set`/`reset`) | Writes |
| `ludwig.user.settings.consent` | counter | `consent`, `decision` | Consent decisions |
| `ludwig.user.settings.admin.read` | counter | | Administrative reads of other subjects |
| `ludwig.user.settings.projection.dropped` | counter | `reason` (`stale`) | Stale projected events (spikes during a backfill - expected) |
| `ludwig.user.settings.value.unreadable` | counter | `layer` | Stored values this build cannot read |
| `ludwig.user.settings.backfill.published` | counter | `kind` (`setting`/`consent`) | Backfilled rows |

No metric is tagged with a setting value or subject id.

**Logs.** `INFO`: definitions registered, config reload clearing the cache, tombstone purge, backfill start/finish.
`WARN`: malformed/unorderable events discarded, cross-tenant access refused, unreadable stored values, explicit consent
purge. `ERROR`: unparseable event.

**Recommended alerts:** `ludwig_user_settings_value_unreadable_total` increasing (a value written by a newer build or
a corrupt row); messaging dead-letter alerts for the projection topic; a falling cache hit ratio; projection lag.

## Audit

Category `settings`, written **inside the caller's transaction**; the default failure policy for `settings` is
`FAIL_OPERATION`, so a failed audit write rolls the change back and the caller gets `AuditWriteFailedException` →
**503** (retrying is correct).

| Action | When |
|---|---|
| `setting.set` | value set at any scope |
| `setting.reset` | value reset |
| `setting.admin-read` | an administrator read another subject's settings |

Recorded: who (`actor_subject`), for whom (`on_behalf_of`), definition, old → new, layer, correlation id. Values of
`pii(true)` definitions are `***REDACTED***` (also in logs, problem documents and admin responses); rejected values are
never echoed in validation problems. This module contributes the `DeclaredSensitivityClassifier` to platform
redaction. **Consents are evidenced by the `user_consent` ledger**, not by `audit_event`.

| Data | Retention | Purged automatically |
|---|---|---|
| `audit_event` (`category=settings`) | 7 years | yes, by the audit starter |
| `user_setting_value` tombstones | 30 days | when `retention.enabled=true` |
| `user_consent` | - | **never** (`SettingsRetentionService.purgeConsents` exists for an operator acting on a written legal policy) |

## Administration

Operator endpoints are on the platform's administrative surface, `/admin/v1/{area}`, and exist only where `security-spring-boot-starter`'s surface does. Each is guarded by one operation; which of the service's roles hold it is the service's own `ludwig.admin.grants` (roles from the authority resolver, never token claims), and an operation granted to no role is refused to everyone. A `privileged` call needs an `Admin-Reason` header, and every call is audited under category `admin` with the operation id as its action. `GET /admin/v1/me/operations` lists what the caller holds.

| Operation | Endpoints | Allows |
|---|---|---|
| `settings.read` | `GET /admin/v1/settings/subjects/{subject}`<br>`GET /admin/v1/settings/subjects/{subject}/consents`<br>`GET /admin/v1/settings/subjects/{subject}/consents/asof` | Read another subject's resolved settings and consent history. |
| `settings.operate` | `PUT /admin/v1/settings/scopes/{scopeType}/{scopeId}`<br>`POST /admin/v1/settings/backfill` | Write settings at a user, role or tenant scope, and run a backfill. |
| `settings.privileged` | none in this release | No operation yet; reserved so that grants for this area stay the same across releases. |

**Removed:** `/admin/settings/**`, `ludwig.user-settings.access.admin-authority` and `ludwig.user-settings.web.admin-base-path`; `consents/as-of` is now `consents/asof`. Mounted only with `ludwig.user-settings.web.enabled=true` in owner mode (the backfill also needs `web.backfill-enabled=true`). Another tenant's subject is `404` (it was `403`); the self-service endpoints keep their `403`.

Grant block (the role names are the service's own):

```yaml
ludwig:
  admin:
    grants:
      "[settings.read]": [ORDERS_ADMIN, ORDERS_SUPPORT]
      "[settings.operate]": [ORDERS_ADMIN]
      "[settings.privileged]": [ORDERS_ADMIN]
```

## Runbook

| Symptom | Likely cause | What to do |
|---|---|---|
| New consumer resolves defaults for users who set values | Projection seeded only by changes | Run a backfill for the setting/tenant (safe to repeat) |
| Settings revert after a change | Out-of-order delivery should be dropped; check the projection is on the same topic and `changed_at` is set | Inspect `ludwig_user_settings_projection_dropped_total` and the listener log |
| Settings changes fail with 503 | `audit_event` insert failing | Fix the audit sink (see audit starter); changes were rolled back |
| `TenantUnresolvableException` | Caller without tenant in a multi-tenant deployment, or a job using the principal overloads | Use `SettingsSubject` with an explicit tenant |
| `UnknownSettingException` | Definition not contributed by any `SettingDefinitionSource` | Register it |
| Startup fails on a definition | Conflicting duplicates, unconvertible type, bad default | Fix the declaration |
| `value.unreadable` increasing | Stored encoding from another build | `SELECT tenant_id, scope_type, scope_id, setting_key, value_type FROM user_setting_value WHERE setting_key = '<key>';` align converters/types |
| Changed tenant default not visible | Cache | Reload clears it; otherwise wait for TTL |
| A user wants their data erased | - | Delete their `user_setting_value` rows; consents follow the legal retention policy |

## Testing

Unit (resolution precedence, startup validation, conversion, cache coalescing, access policy, projection ordering,
backfill paging and budget, context refusing a broken definition) and Testcontainers integration with PostgreSQL and a
broker (owner and projection modes, out-of-order and replayed events, after-commit eviction, tenant isolation, consent
immutability against the trigger, `getAll` = exactly one statement, backfill against real outbox rows), plus
`ArchitectureTest`.

## Design decisions

- **Typed definitions in code**, never an EAV schema edited at runtime.
- **No default mode**: guessing owner or projection is wrong either way.
- **`SettingsWriter` absent in projection mode**, so wrong wiring fails at startup.
- **Consents are a separate append-only ledger**, never an upserted setting.
- **Layer reported with every value**, for "inherited from your organization" and for support.
- **No value is filterable, indexed, or a metric tag.**
