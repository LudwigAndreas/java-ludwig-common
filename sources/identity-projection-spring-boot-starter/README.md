# identity-projection-spring-boot-starter

***English** · [Русский](README.ru.md)*

A local, queryable projection of the OIDC provider's Kafka user stream, plus the database-backed implementations of
[`security-spring-boot-starter`](../security-spring-boot-starter)'s three SPIs that read from it: users, roles, a
partner registry and time-boxed data grants in PostgreSQL; idempotent, order-tolerant consumption; authority-cache
eviction after commit; and opt-in projection of verified contact data for the one service that writes to people.

| | |
|---|---|
| Role | starter |
| Depends on (in-repo) | [`db-core`](../db-core), [`messaging-spring-boot-starter`](../messaging-spring-boot-starter), [`security-spring-boot-starter`](../security-spring-boot-starter) |
| Used by | `crud-service-example`, `notification-service` |
| Surfaces | Kafka (inbound user stream) |

| SPI | Implementation | Source |
|---|---|---|
| `AuthorityResolver` | `DatabaseAuthorityResolver` | `security_user` (Kafka), `security_partner` (table), `ludwig.identity.service-roles` (config) |
| `DataScopeProvider` | `DatabaseDataScopeProvider` | `security_grant` - explicit, optionally time-boxed grants |
| `PartnerIdentityResolver` | `DatabasePartnerIdentityResolver` | `security_partner` - onboard and suspend partners without a release |

A local projection avoids a network hop to the identity provider on every authorization decision, and makes "every
agent in this tenant" or "who still holds this role" a query.

## Usage

```xml
<dependency>
    <groupId>ru.ludwigandreas</groupId>
    <artifactId>identity-projection-spring-boot-starter</artifactId>
</dependency>
```

The security starter's fallbacks back off (`@ConditionalOnMissingBean`); there is nothing to wire.

### The event contract

```json
{
  "eventId":   "5f1c...",
  "type":      "UPSERT",
  "subject":   "0b7c1b5e-...",
  "displayName": "Anna Schmidt",
  "tenantId":  "tenant-a",
  "roles":     ["CATALOG_ADMIN", "ORDER_AGENT"],
  "email":     "anna.schmidt@example.com",
  "emailVerified": true,
  "alternateEmail": "anna@personal.example",
  "phoneNumber": "+49301234567",
  "phoneVerified": true,
  "chatHandle": "@anna",
  "sourceVersion": "7",
  "occurredAt": "2026-03-01T10:00:00Z"
}
```

- **`roles` is the complete set, never a delta**: applying an event twice gives the same row; replaying the topic
  converges.
- **`occurredAt` is the ordering key**: an event older than the stored one is dropped (normal, not an error), so a
  replay never restores a revoked role.
- **Unknown fields are ignored**: the provider owns the schema.
- `type` is `UPSERT`, `DISABLE` or `DELETE`. `DELETE` becomes a status, never a row deletion: audit records and
  `created_by` references must keep resolving, and a re-created account must not inherit the old one's history.

### Contact data (opt-in)

Contact fields are stored **only** with `ludwig.identity.contact.enabled=true`, and cleared from a user's row on
their next event when the flag is off. Turn it on only in the service that sends messages to people (this projection
is replicated into every service using the module, and each copy is another place a deletion request must reach).
No index on contact columns (lookups are by subject). `emailVerified` / `phoneVerified` are nullable: null means
"the provider did not say", which differs from "not verified".

### Grants vs. policy

| | Role policy (`ludwig.security.data.policies`) | Grant row (`security_grant`) |
|---|---|---|
| Says | "agents see their own cases" | "Anna covers Berlin while Bob is on leave" |
| Is | a rule about the system | data about a person |
| Changes by | pull request and deploy | an administrator action |
| Expires | never | `expires_at`, enforced in the query |

A grant can only **widen** what the policy allows; narrowing is done by removing roles. Time-boxing is enforced in
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
directory change -> Kafka -> projection commits -> authority cache evicted (after commit) -> next request sees it
```

Eviction uses `cache.evictAfterCommit(ref)` from [cache](../cache-spring-boot-starter). The authority cache TTL
(`ludwig.cache.caches.authorities.ttl`, default 30s) is the backstop for a *missed* event.

## Configuration

| Property | Default | Meaning |
|---|---|---|
| `ludwig.identity.enabled` | `true` | Master switch |
| `ludwig.identity.grants-enabled` | `true` | Register the grant-table `DataScopeProvider` |
| `ludwig.identity.partner-registry-enabled` | `true` | Register the table-backed `PartnerIdentityResolver` |
| `ludwig.identity.kafka.enabled` | `true` | Consume the user stream (off: tables still queried - admin service, tests) |
| `ludwig.identity.kafka.topic` | `oidc.users` | User stream topic |
| `ludwig.identity.kafka.group-id` | - | **Own consumer group per service** (a shared group would see only a share of events) |
| `ludwig.identity.liquibase.enabled` | `true` | `false` when including the changelog from your own master |
| `ludwig.identity.contact.enabled` | `false` | Project verified contact data |
| `ludwig.identity.service-roles` | `{}` | Workload id → roles |

The listener's consumer settings (retry, dead letter, cluster) are under
`ludwig.messaging.consumers.<identity consumer>.*` (see [messaging](../messaging-spring-boot-starter)).

## Deployment

- **Schema**: `db/changelog/identity/identity-changelog.xml` (namespaced changeset ids), standalone or included.

  | Table | Holds | Owned by |
  |---|---|---|
  | `security_user` (+ `security_user_role`) | the projection; PK = OIDC `sub` (`ExternalEntity`) | the directory, via Kafka |
  | `security_partner` (+ `security_partner_role`) | partner registry: code, certificate identifiers, status, roles | administrators (`AuditedEntity`) |
  | `security_grant` | explicit data-scope grants, optionally expiring | administrators (`AuditedEntity`) |

- **Topics**: the user topic and its dead-letter topic **`<topic>.dlt`** (default `oidc.users.dlt`) must exist.
- **Consumer group**: a distinct `group-id` per service.
- **Data protection**: `security_user` holds personal data; include it in deletion procedures. With contact data on,
  it holds addresses.

## Observability

Consumption metrics come from [messaging](../messaging-spring-boot-starter#observability)
(`ludwig.messaging.consumed`, `.retries`, `.dead.lettered`, `.dropped`, consumer lag, `silent`).

**Logs.** `ERROR Discarding unparseable user event` (committed, not retried); `WARN Discarding user event with no
subject`. Payloads are never logged (directory data about a person).

**Recommended alerts:** messaging's dead-letter and drop alerts on the identity topic; `ludwig_messaging_silent` for
the identity consumer (set a `silence-threshold` matching the directory's change rate).

**Known gaps:**

- Discarded events (unparseable, no subject) are only logged, not counted: a person's directory data can go missing
  with no metric.
- No metrics for applied events, stale events dropped by the ordering guard, or projection size.

## Audit

The projection records no audit events: it mirrors the provider's directory, which has its own audit trail.

**Known gap:** changes to `security_partner` and `security_grant` (the administrator-owned tables) are stamped with
`created_by` / `updated_by` but produce no `AuditEvent`, so "who granted what and when" has only the latest
modification, not a history. A service that edits these tables should record an `AuditEvent` (category `access`) in
the same transaction.

## Runbook

| Symptom | Likely cause | What to do |
|---|---|---|
| New user has no roles | Event not consumed yet, consumer stuck, or wrong topic/group | Check lag and `silent` for the identity consumer; `SELECT subject, status, source_timestamp FROM security_user WHERE subject = '<sub>';` |
| Role revoked in the directory still works | Event not consumed, or within the authority cache TTL | Check consumer lag; wait ≤ TTL |
| Revoked role came back after a replay | Should not happen (ordering guard) | Compare `source_timestamp` with the event's `occurredAt`; report a bug |
| `Discarding unparseable user event` | Provider schema change broke parsing | Fix parsing; ask the provider to republish affected users |
| Records on `oidc.users.dlt` | Apply failed repeatedly (database down, constraint) | Fix the cause; replay the dead letters to the topic (applying is idempotent) |
| Grant has no effect | Expired (`expires_at`), wrong resource/action, or `grants-enabled=false` | `SELECT * FROM security_grant WHERE subject = '<sub>';` |
| Partner rejected | `security_partner` row missing, suspended, or certificate identifier changed | Update the row (status, identifiers) |

## Testing

`IdentityProjectionServiceTest` (apply, ordering, delete-as-status, contact scrubbing) and `IdentityKafkaOrderingTest`.

**Known gap:** no integration test against a real PostgreSQL and Kafka for the projection, the SPIs or the
changelog; consuming services cover it indirectly.

## Design decisions

- **A projection, not a remote call or token claims**: local joins, no third-party availability on the hot path,
  revocation in seconds.
- **Full role sets and `occurredAt` ordering**: replays converge and never re-grant.
- **Only what authorization needs is stored by default**; contact data is opt-in.
- **Service roles in configuration**, reviewed with code.
- **The listener reads `String` payloads and parses them itself**: unparseable payloads are logged and committed;
  apply failures are rethrown, retried with backoff and dead-lettered (applying is idempotent). The container factory
  comes from messaging's builder; the listener declares no `Acknowledgment`.
