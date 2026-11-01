# identity-projection-spring-boot-starter

***English** · [Русский](README.ru.md)*

A local, queryable projection of the users and roles a service's identity providers know, fed through **one
provider-neutral change feed** by any number of declared **sources**, plus the database-backed implementations of
[`security-spring-boot-starter`](../security-spring-boot-starter)'s three SPIs that read from it: users, roles, a
partner registry and time-boxed data grants in PostgreSQL; ordered, idempotent application; source ownership and
role mapping; threshold-guarded full sweeps; authority-cache eviction after commit; and opt-in projection of verified
contact data for the one service that writes to people.

| | |
|---|---|
| Role | starter |
| Depends on (in-repo) | [`db-core`](../db-core), [`security-spring-boot-starter`](../security-spring-boot-starter) |
| Used by | `crud-service-example`, `notification-service` |
| Feeds | [`identity-source-kafka`](../identity-source-kafka-spring-boot-starter) (push), [`identity-source-directory`](../identity-source-directory-spring-boot-starter) (pull), or a service's own code |

| SPI | Implementation | Source |
|---|---|---|
| `AuthorityResolver` | `DatabaseAuthorityResolver` | `security_user` (the feeds), `security_partner` (table), `ludwig.identity.service-roles` (config) |
| `DataScopeProvider` | `DatabaseDataScopeProvider` | `security_grant` - explicit, optionally time-boxed grants |
| `PartnerIdentityResolver` | `DatabasePartnerIdentityResolver` | `security_partner` - onboard and suspend partners without a release |

A local projection avoids a network hop to the identity provider on every authorization decision, and makes "every
agent in this tenant" or "who still holds this role" a query. The projection knows nothing about any provider: a
provider needs a feed, never a fork of this module.

## Usage

```xml
<dependency>
    <groupId>ru.ludwigandreas</groupId>
    <artifactId>identity-projection-spring-boot-starter</artifactId>
</dependency>
<!-- and the feed for your provider, for example: -->
<dependency>
    <groupId>ru.ludwigandreas</groupId>
    <artifactId>identity-source-kafka-spring-boot-starter</artifactId>
</dependency>
```

```yaml
ludwig:
  identity:
    sources:
      oidc: {}          # every source this service accepts is declared; a feed naming another fails startup
```

The security starter's fallbacks back off (`@ConditionalOnMissingBean`); there is nothing else to wire.

### Sources and role mapping

A **source** is one identity provider. Every change names its source, and a user belongs to the source that first
projected them (`security_user.source_system`): a change for that subject from another source is refused, counted
(`outcome="foreign-source"`) and logged at error level, never applied. A service with two providers - staff and
customers, say - declares both.

```yaml
ludwig:
  identity:
    sources:
      staff:
        role-mapping:                         # provider role or group -> platform roles
          okta-catalog-admins: [CATALOG_ADMIN, ORDER_AGENT]
          okta-support: [ORDER_SUPPORT]
        unmapped-roles: DROP                  # KEEP (default) keeps a role the mapping does not name
      customers: {}
```

Role names are compared without the `ROLE_` prefix; the stored set is the mapped one. A source name is lower-case
letters, digits and hyphens. A declared source no feed serves is logged once at startup and is legitimate (an
administrative service reading the tables, a source only seeded locally).

### Writing a feed

Everything gets into the projection through `IdentityProjection`:

```java
@RestController
class DirectoryWebhook {

    private final IdentityProjection projection;

    DirectoryWebhook(IdentityProjection projection) {
        this.projection = projection;
    }

    @PostMapping("/hooks/directory")
    void changed(@RequestBody ProviderUser user) {
        projection.apply(new IdentityChange.Upsert("staff", user.id(), user.updatedAt(), user.etag(),
                new UserProfile(user.name(), user.orgId(), user.groups(), null)));
    }
}
```

- **`Upsert`** carries the profile. A role set it carries is the **complete** set, never a delta (empty withdraws
  every role); any field left null - the role set included - keeps its stored value, because providers send partial
  profiles.
- **`Disable`** and **`Remove`** set the status to `DISABLED` / `REMOVED`; the row stays, so audit records and
  `created_by` references keep resolving.
- **`occurredAt` is the ordering key**: a change whose time is not newer than the stored one is skipped as `STALE`
  (normal under replay and reordering), so a replay never restores a revoked role.
- `applyAll(list)` applies a batch in one transaction, joining an active one - so a pull feed can commit a page with
  its own watermark. Each call returns an `ApplyOutcome` per change.
- Register an `IdentityFeed` bean (`source()`, `kind()`, `status()`) so startup validates the source and the
  sources listing shows the feed.

### Full sweeps

A feed that can list a source completely runs a sweep: `beginSweep(source)`, `listed(subjects)` for each page, then
`complete()`. Every active user of that source the sweep did not list is disabled - this is what repairs a deletion
whose event was lost. A sweep that would disable more than `ludwig.identity.sweep.max-disable-ratio` (`0.1`) of the
source's active users is **aborted with no change**, logged at error level and counted, because an empty or truncated
listing must not lock out an organisation. A user a sweep disabled is restored by their next upsert, whatever its
`occurredAt`: the verdict rested on absence, not on a change at the source. Listed subjects are staged in
`identity_sweep_listed`, so a directory of a million users costs no heap.

### Contact data (opt-in)

Contact fields are stored **only** with `ludwig.identity.contact.enabled=true`, and cleared from a user's row on
their next change when the flag is off. Turn it on only in the service that sends messages to people (this projection
is replicated into every service using the module, and each copy is another place a deletion request must reach).
No index on contact columns (lookups are by subject). `emailVerified` / `phoneVerified` are nullable: null means
"the provider did not say", which differs from "not verified".

### Grants vs. roles

| | Role scope (`ludwig.security.roles`) | Grant row (`security_grant`) |
|---|---|---|
| Says | "agents see their own cases" | "Anna covers Berlin while Bob is on leave" |
| Is | a rule about the system | data about a person |
| Changes by | pull request and deploy | an administrator action |
| Expires | never | `expires_at`, enforced in the query |

A grant can only **widen** what the roles allow; narrowing is done by removing roles. Time-boxing is enforced in
the `WHERE` clause, so nothing has to come back and clean up.

### Peer services

```yaml
ludwig:
  identity:
    service-roles:
      "[spiffe://mesh/ns/orders/sa/orders]":
        - ROLE_CATALOG_READER
```

Configuration, not a table: which service may call which is deployment topology, reviewed with the code that makes
the call.

### Revocation latency

```
source change -> feed -> projection commits -> authority cache evicted (after commit) -> next request sees it
```

Eviction uses `cache.evictAfterCommit(ref)` from [cache](../cache-spring-boot-starter). The authority cache TTL
(`ludwig.cache.caches.authorities.ttl`, default 30s) is the backstop for a *missed* change.

## Configuration

| Property | Default | Meaning |
|---|---|---|
| `ludwig.identity.enabled` | `true` | Master switch |
| `ludwig.identity.sources.<name>.role-mapping` | `{}` | Provider role or group → platform roles |
| `ludwig.identity.sources.<name>.unmapped-roles` | `KEEP` | `KEEP` or `DROP` a role the mapping does not name |
| `ludwig.identity.sweep.max-disable-ratio` | `0.1` | Largest share of a source's active users one sweep may disable |
| `ludwig.identity.grants-enabled` | `true` | Register the grant-table `DataScopeProvider` |
| `ludwig.identity.partner-registry-enabled` | `true` | Register the table-backed `PartnerIdentityResolver` |
| `ludwig.identity.liquibase.enabled` | `true` | `false` when including the changelog from your own master |
| `ludwig.identity.contact.enabled` | `false` | Project verified contact data |
| `ludwig.identity.service-roles` | `{}` | Workload id → roles |

**Removed:** `ludwig.identity.kafka.topic` fails startup, naming `ludwig.identity.kafka.subscriptions.<source>.topic`
in [`identity-source-kafka`](../identity-source-kafka-spring-boot-starter); the projection no longer contains a
consumer.

## Deployment

- **Schema**: `db/changelog/identity/identity-changelog.xml` (namespaced changeset ids), standalone or included.

  | Table | Holds | Owned by |
  |---|---|---|
  | `security_user` (+ `security_user_role`) | the projection; PK = the source's subject (`ExternalEntity`), `source_system` = its source | the sources, via the feeds |
  | `identity_sweep_listed` | subjects staged by a running sweep; empty between sweeps | the projection |
  | `security_partner` (+ `security_partner_role`) | partner registry: code, certificate identifiers, status, roles | administrators (`AuditedEntity`) |
  | `security_grant` | explicit data-scope grants, optionally expiring | administrators (`AuditedEntity`) |

- **Data protection**: `security_user` holds personal data; include it in deletion procedures. With contact data on,
  it holds addresses.

## Observability

| Metric | Tags | Meaning |
|---|---|---|
| `ludwig_identity_changes_total` | `source`, `type` (`upsert`, `disable`, `remove`), `outcome` (`applied`, `stale`, `foreign-source`, `discarded`) | Every change a feed delivered, by what was done with it |
| `ludwig_identity_sweeps_total` | `source`, `outcome` (`completed`, `aborted`) | Complete listings |
| `ludwig_identity_sweep_disabled_total` | `source` | Users disabled because a listing no longer contained them |

Feed-specific metrics (consumer lag, sync runs) are the feed modules'.

**Logs.** `ERROR Refusing a ... change for subject ... from source ...: the subject belongs to source ...`;
`ERROR Aborted the sweep of identity source ...`; `WARN Discarding a ... change ... with no subject`. Profiles are
never logged (directory data about a person).

**Recommended alerts:** any `outcome="foreign-source"` (two providers issue one subject); any aborted sweep; a rise
in `outcome="discarded"`.

## Audit

The projection records no audit events: it mirrors the providers' directories, which have their own audit trails.

Changes to `security_partner` and `security_grant` made through the administrative endpoints
(`/admin/v1/identity/`) are recorded in the audit trail (category `admin`) in the transaction that makes them, so
"who granted what and when" has a history, not only the latest `updated_by`. A change made by hand in SQL still has
only the stamps.

## Administration

Operator endpoints are on the platform's administrative surface, `/admin/v1/{area}`, and exist only where `security-spring-boot-starter`'s surface does. Each is guarded by one operation; which of the service's roles hold it, and with what scope, is the service's own `ludwig.security.roles` (roles from the authority resolver, never token claims), and an operation no role holds is refused to everyone. A `PRIVILEGED` operation needs an `Operation-Reason` header and refuses a personal access token, and every call is audited under category `admin` with the operation id as its action. `GET /me/operations` lists what the caller holds, and `GET /admin/v1/security/operations/{id}/holders` which roles hold an operation.

| Operation | Sensitivity | Endpoints | Allows |
|---|---|---|---|
| `identity.partner.read` | `READ` | `GET /admin/v1/identity/partners`<br>`GET /admin/v1/identity/partners/{code}` | See the registered partners: code, name, status, certificate identifiers and roles. |
| `identity.partner.onboard` | `PRIVILEGED` | `POST /admin/v1/identity/partners` | Onboard a partner. |
| `identity.partner.replace` | `PRIVILEGED` | `PUT /admin/v1/identity/partners/{code}` | Replace a partner's display name, certificate identifiers and roles. |
| `identity.partner.suspend` | `PRIVILEGED` | `POST /admin/v1/identity/partners/{code}/suspend` | Suspend a partner, so that it can no longer authenticate. |
| `identity.partner.activate` | `PRIVILEGED` | `POST /admin/v1/identity/partners/{code}/activate` | Reactivate a suspended partner. |
| `identity.grant.read` | `READ` | `GET /admin/v1/identity/grants` | See the per-subject data-scope grants. |
| `identity.grant.create` | `PRIVILEGED` | `POST /admin/v1/identity/grants` | Grant a subject a data scope, optionally until an expiry. |
| `identity.grant.revoke` | `PRIVILEGED` | `POST /admin/v1/identity/grants/{id}/revoke` | Revoke a subject's data-scope grant, keeping its history. |
| `identity.authority.read` | `READ` | `GET /admin/v1/identity/subjects/{subject}/authorities` | See what a subject is entitled to now: roles, attributes and active grants. |
| `identity.source.read` | `READ` | `GET /admin/v1/identity/sources` | See the identity sources: their feeds, user counts by status, and each feed's own state such as a watermark or last sync. |

`PUT .../partners/{code}` requires `If-Match` and answers `412` on a stale version. A `USER` caller is confined to subjects of their own tenant (another tenant's subject is `404`); the partner registry is not tenant-scoped.

Role block (the role names are the service's own; `ALL` is the scope):

```yaml
ludwig:
  security:
    roles:
      ORDERS_SUPPORT:
        operations:
          identity.partner.read: ALL
          identity.grant.read: ALL
          identity.authority.read: ALL
      ORDERS_ADMIN:
        includes: [ORDERS_SUPPORT]
        operations:
          identity.partner.onboard: ALL
          identity.partner.replace: ALL
          identity.partner.suspend: ALL
          identity.partner.activate: ALL
          identity.grant.create: ALL
          identity.grant.revoke: ALL
```

Role block (the role names are the service's own; `ALL` is the scope):

```yaml
ludwig:
  security:
    roles:
      ORDERS_SUPPORT:
        operations:
          identity.partner.read: ALL
          identity.grant.read: ALL
          identity.authority.read: ALL
          identity.source.read: ALL
      ORDERS_ADMIN:
        includes: [ORDERS_SUPPORT]
        operations:
          identity.partner.onboard: ALL
          identity.partner.replace: ALL
          identity.partner.suspend: ALL
          identity.partner.activate: ALL
          identity.grant.create: ALL
          identity.grant.revoke: ALL
```

## Runbook

| Symptom | Likely cause | What to do |
|---|---|---|
| New user has no roles | Change not delivered yet, or the source's role mapping drops them | Check the feed (`GET /admin/v1/identity/sources`); `SELECT id, source_system, status, source_timestamp FROM security_user WHERE id = '<sub>';`; check `role-mapping` and `unmapped-roles` |
| Role revoked at the provider still works | Change not delivered, or within the authority cache TTL | Check the feed; wait ≤ TTL |
| Revoked role came back after a replay | Should not happen (ordering guard) | Compare `source_timestamp` with the change's `occurredAt`; report a bug |
| `foreign-source` counted | Two sources issue the same subject | Fix the provider configuration; the projection never merges subjects |
| Sweep aborted | The listing was empty or truncated, or a real mass offboarding | Check the provider; for a real offboarding raise `max-disable-ratio` for one run |
| Grant has no effect | Expired (`expires_at`), wrong resource/action, or `grants-enabled=false` | `SELECT * FROM security_grant WHERE subject = '<sub>';` |
| Partner rejected | `security_partner` row missing, suspended, or certificate identifier changed | Update the row (status, identifiers) |

## Testing

`IdentityProjectionServiceTest` (apply, ordering, ownership, delete-as-status, contact scrubbing),
`RoleMappingTest`, `IdentitySourceValidationTest`, `RemovedIdentityKeysTest`, `IdentityMetricsTest`, and against
PostgreSQL `IdentitySweepIntegrationTest` (set-wise disable, threshold, source isolation, restoration) and
`IdentityAdminIntegrationTest` (the `identity` area, the sources listing).

## Design decisions

- **A projection, not a remote call or token claims**: local joins, no third-party availability on the hot path,
  revocation in seconds.
- **One write path, many feeds**: ordering, staleness, ownership, role mapping, contact policy and eviction live in
  `IdentityProjection`, so every provider gets them identically and a new provider is a feed, not a fork.
- **Complete role sets and `occurredAt` ordering**: replays converge and never re-grant.
- **Sweeps repair, the threshold protects**: an event stream alone has no repair path; a listing alone can be wrong.
- **A subject belongs to one source**: silently merging two providers' subjects would let one person hold another's
  roles.
- **Only what authorization needs is stored by default**; contact data is opt-in.
- **Service roles in configuration**, reviewed with code.
