# identity-projection-spring-boot-starter

***English** · [Русский](README.ru.md)*

The local, queryable answer to "who is this caller and what may they do here?", kept up to date from the
OIDC provider's Kafka user stream — and the database-backed implementations of
[`security-spring-boot-starter`](../security-spring-boot-starter/README.md)'s three SPIs that read from
it.

Add both modules and roles stop coming from token claims and start coming from your own tables:

```xml
<dependency>
    <groupId>ru.ludwigandreas</groupId>
    <artifactId>identity-projection-spring-boot-starter</artifactId>
    <version>1.0.0</version>
</dependency>
```

The security module's fallbacks back off automatically (`@ConditionalOnMissingBean`); there is no wiring
to write.

| SPI | Implementation | Source |
|---|---|---|
| `AuthorityResolver` | `DatabaseAuthorityResolver` | `security_user` (Kafka), `security_partner` (table), `ludwig.identity.service-roles` (config) |
| `DataScopeProvider` | `DatabaseDataScopeProvider` | `security_grant` — explicit, optionally time-boxed grants |
| `PartnerIdentityResolver` | `DatabasePartnerIdentityResolver` | `security_partner` — onboard and suspend partners without a release |

---

## Why a projection at all

The alternative is calling the identity provider on every authorization decision, which puts a network
hop and a third-party availability budget on your hottest path. The other alternative is putting roles in
the token, which is what this whole design avoids — see the security module's README for why revocation
latency is the argument that settles it.

A projection gives you a local join. "Every agent in this tenant", "which users still hold this role" and
"what did this subject look like at the time of that audit record" become queries, not API calls.

---

## The event contract

```json
{
  "eventId":   "5f1c...",
  "type":      "UPSERT",
  "subject":   "0b7c1b5e-...",
  "displayName": "Anna Schmidt",
  "tenantId":  "tenant-a",
  "roles":     ["CATALOG_ADMIN", "ORDER_AGENT"],
  "sourceVersion": "7",
  "occurredAt": "2026-03-01T10:00:00Z"
}
```

Three properties of this shape do the work:

**`roles` is the complete set, never a delta.** Applying the same event twice produces the same row, and
replaying the topic from the beginning converges on the correct state. Incremental "role added"/"role
removed" events would leave the projection permanently wrong after a single lost or duplicated message —
and "permanently wrong" here means someone keeps a role they no longer have.

**`occurredAt` is the ordering key, not the receipt time.** Kafka orders within a partition, and a user's
events move between partitions when the topic is scaled or the key changes. An event older than what is
stored is normal, not an error, and is dropped. Without that check, a replay silently restores a role that
was revoked days ago — a delayed, invisible privilege escalation.

**Unknown fields are ignored.** The provider owns this schema and will add to it. A consumer that fails on
a new field turns an upstream release into an outage in every service on the topic.

`type` is `UPSERT`, `DISABLE` or `DELETE`. `DELETE` is projected as a status change, never as a row
deletion: audit records and `created_by` references pointing at that subject have to keep resolving, and a
re-created account must not inherit the old one's history.

---

## Revocation latency

```
directory change -> Kafka -> projection commits -> authority cache evicted -> next request sees it
```

Eviction happens **after commit**, not during: evicting inside the transaction opens a window where
another thread re-reads the old row and re-populates the cache with stale roles, and the eviction would
have happened even if the transaction then rolled back.

`ludwig.security.authorities.cache.ttl` (default 60s) is therefore the backstop for a *missed* event, not
the normal path to consistency. In the normal path the change is visible on the next request.

---

## Schema

Shipped as a Liquibase changelog with namespaced changeset ids, applied either standalone or from your
own master changelog (`ludwig.identity.liquibase.enabled=false` — see the example service).

| Table | Holds | Owned by |
|---|---|---|
| `security_user` (+ `security_user_role`) | the projection; PK is the OIDC `sub` | the directory, via Kafka |
| `security_partner` (+ `security_partner_role`) | partner registry: code, certificate identifiers, status, roles | you, audited |
| `security_grant` | explicit data-scope grants, optionally expiring | you, audited |

`security_user` extends db-core's `ExternalEntity` because that is exactly what it is — a record whose id
and lifecycle belong to another system — and its `source_version`/`source_timestamp` columns are what the
ordering guard compares. Only what authorization needs is stored: this is directory data replicated into
every service that uses the module, so every extra column is another place a personal-data request has to
reach.

`security_partner` and `security_grant` are `AuditedEntity` instead, because nothing projects them: they
are granted, by someone, and *who granted what and when* is the first question an auditor asks.

---

## Grants vs. policy

Both widen a caller's data scope and they compose (`CompositeDataScopeProvider` unions them), but they
exist for different lifecycles:

| | Role policy (`ludwig.security.data.policies`) | Grant row (`security_grant`) |
|---|---|---|
| Says | "agents see their own cases" | "Anna covers Berlin while Bob is on leave" |
| Is | a rule about the system | data about a person |
| Changes by | a pull request and a deploy | an admin action |
| Expires | never | `expires_at`, enforced in the query |

A grant can only ever **widen** what the policy allows. Narrowing is done by removing roles — which keeps
"why can this person see this?" answerable by reading two places, not by tracing a chain of overrides.

Time-boxing is the difference between a coverage arrangement and a permanent privilege nobody remembers
granting, and it is enforced in the `WHERE` clause rather than by anything that has to come back and
clean up.

---

## Peer services

```yaml
ludwig:
  identity:
    service-roles:
      "[spiffe://mesh/ns/orders/sa/orders]":
        - ROLE_CATALOG_READER
```

In configuration rather than in a table, deliberately: which service may call which is part of the
deployment topology. It changes when code changes, and it should be reviewed in the same pull request as
the call it authorizes. Nobody grants a service access at runtime.

---

## Consumer behaviour

The listener takes the payload as a raw `String` and deserializes it itself rather than through a
configured `JsonDeserializer`. That keeps the module from dictating a consumer factory your service
already owns, and — more importantly — it puts deserialization failures inside application code:

- **Unparseable payload** → logged and acknowledged. Replaying it will fail identically forever, and a
  container-level deserialization failure is retried indefinitely with the default error handler: one bad
  message and the projection stops advancing for every user on that partition.
- **Failure while applying** (database down, constraint fired) → rethrown without acknowledging, so the
  container redelivers. Safe, because applying an event is idempotent.

The payload itself is never logged. It is directory data about a person.

Each service needs **its own consumer group** — sharing one would mean each service saw only a share of
the events.

---

## Configuration

```yaml
ludwig:
  identity:
    enabled: true
    grants-enabled: true              # register the grant-table DataScopeProvider
    partner-registry-enabled: true    # register the table-backed PartnerIdentityResolver
    kafka:
      enabled: true
      topic: corp.identity.users
      group-id: my-service-identity-projection
    liquibase:
      enabled: true                   # false when you include the changelog from your own master
    service-roles: {}
```

Everything is optional. With `kafka.enabled=false` the tables are still there and still queried — useful
for an admin service over the same schema, or a test.
