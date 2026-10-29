# pat-spring-boot-starter

***English** · [Русский](README.ru.md)*

The issuing half of the platform's personal access tokens (PATs), for the identity-provider service: the
`ludwig_pat` table, the management API, the RFC 8693 token exchange, RFC 7662 introspection, audit, metrics,
i18n and problem details. The **verifying** half is in
[`security-spring-boot-starter`](../security-spring-boot-starter), in every service. The format is in
[`pat-core`](../pat-core).

| | |
|---|---|
| Role | starter (identity provider only) |
| Depends on (in-repo) | [`audit-core`](../audit-core), [`cache`](../cache-spring-boot-starter), [`db-core`](../db-core), [`pat-core`](../pat-core), [`security`](../security-spring-boot-starter), [`web-core`](../web-core-spring-boot-starter) |
| Used by | the identity-provider service (not in this repository) |
| Surfaces | REST: `POST /oauth2/token`, `POST /introspect`, `/api/v1/personal-access-tokens` |

```
  CI / curl / notebook
        │  Authorization: Bearer lpat_<keyId>_<secret>_<crc>     ← one static header
        ▼
  EDGE (Envoy / gateway, not in this repository)
        │  prefix "lpat_"? → cache[(digest, audience)] hit → reuse assertion
        │                                               miss → exchange, cache for expires_in - skew
        ▼  POST /oauth2/token
  identity provider (this starter)
        parse → checksum → rate limit → keyId point read → constant-time digest compare
        → not revoked / expired → source allowed → audience permitted
        → mint JWT: sub=<owner>, ludwig_pat={id, scopes}, 5m
        ▼  short-lived, audience-scoped assertion
  ANY service (security starter)
        owner authorities = AuthorityLookup.lookup(sub)  ← live
        effective         = owner ∩ ludwig_pat.scopes
```

The client sends one static header and never performs an exchange itself.

## Usage

```xml
<dependency>
    <groupId>ru.ludwigandreas</groupId>
    <artifactId>pat-spring-boot-starter</artifactId>
</dependency>
```

```yaml
ludwig:
  pat:
    exchange:
      issuer: https://idp.internal            # required - startup fails without it
      audiences: [deploy-service, billing-service]
  security:
    public-paths:
      - /actuator/health
      - /oauth2/token                          # required
      - /introspect                            # if services authenticate tokens directly
```

Plus an `RSAKey` bean for the default minter, or a `PatAssertionMinter` bean that delegates to the provider's
existing signing key (preferred: one key to rotate, one JWKS).

### Management API (`ludwig.pat.web.base-path`, default `/api/v1/personal-access-tokens`)

| Method | Path | Does |
|---|---|---|
| `POST` | `/` | Issue a token (secret shown **once**) |
| `GET` | `/` | List the caller's tokens |
| `GET` | `/{id}` | One token (no secret, no digest) |
| `POST` | `/{id}/rotations` | Rotate: new secret; the old one keeps working for `rotation-overlap` |
| `DELETE` | `/{id}` | Revoke |

Authorities: `pat:manage` to manage one's own tokens - the self-service API acts on nobody else's. Issuing for or
revoking another owner's token is the `pat.privileged` operation of the [administrative area](#administration)
(grant sparingly: it lets a credential outlive the person who created it). A request authenticated **by a PAT** is
refused on every management and administrative endpoint (`PatCredentialGuard`): a token cannot mint, rotate or
revoke a token.

Issuance refuses missing expiry, lifetime over `max-lifetime`, empty scopes and empty audiences, each with its own
localized message.

### The exchange (`POST /oauth2/token`)

```
grant_type=urn:ietf:params:oauth:grant-type:token-exchange
&subject_token=<the lpat_ value>
&subject_token_type=urn:ludwig:params:oauth:token-type:pat
&resource=<destination service>
```

Unauthenticated by design (it consumes the token as its subject). Every failure returns the same refusal; the
reason is only in metrics and audit.

### Introspection (`POST /introspect`)

For platforms whose edge cannot perform the exchange (e.g. a session gateway that only does cookie → JWT): a
**service** asks about a token it received.

```
POST /introspect
token=lpat_...&resource=deploy-service     ← the caller's own audience
```

```json
{ "active": true, "sub": "alice", "scope": "orders:read deploy:write",
  "aud": ["deploy-service"], "patId": "pat-42", "exp": "2026-09-01T00:00:00Z" }
```

and `{ "active": false }` with `200` for every failure (RFC 7662). It reuses the exchange's verifier unchanged
(parse, checksum, point read, constant-time compare, revocation, expiry, CIDR allowlist, rotation overlap, the
security-purpose cache). It needs no signing key. The consuming side is enabled with
`ludwig.security.pat.filter.enabled=true` in each service; when the edge supports the exchange, switch that off and
`ludwig.pat.introspection.enabled=false` here.

`/introspect` must be in `public-paths`: a service authenticating a token calls it with no credential of its own.
It discloses less than the public exchange; both need the full secret. Expose it only inside the mesh.

### What the edge must do (not checkable from this repository)

1. **Exchange per destination audience and cache per `(secret digest, audience)`** for `expires_in` minus a skew.
   A digest-only key would serve one service's assertion to another. Without caching, every request becomes an
   issuer request.
2. **Render a failed exchange as a client `401`** with `WWW-Authenticate: Bearer error="invalid_token"` and the
   `PatProblemTypes` body (`manageCredentialsAt`). **Disclose no cause.**
3. **Strip the `lpat_` credential** before forwarding; services receive only the assertion.

### Revocation window

The security starter computes two windows at startup in every service and refuses to start when either exceeds
`ludwig.security.pat.max-revocation-window` (15m):

```
request/response callers:  assertion lifetime + edge cache lifetime + authority cache TTL   (5m + 5m + 60s = 11m)
long-lived connections:    revalidation interval + authority cache TTL                      (1m + 60s = 2m)
```

To shorten it, lower `ludwig.pat.exchange.assertion-lifetime` here **and** the edge cache together, and keep
`ludwig.security.pat.assertion-lifetime` / `edge-cache-lifetime` in services equal to them.

## Configuration

| Property | Default | Meaning |
|---|---|---|
| `ludwig.pat.enabled` | `true` | Master switch |
| `ludwig.pat.max-lifetime` | `90d` | Longest lifetime at issuance |
| `ludwig.pat.allow-non-expiring` | `false` | Allow tokens without expiry |
| `ludwig.pat.rotation-overlap` | `24h` | Old secret still accepted after a rotation |
| `ludwig.pat.retention` | `365d` | Terminal records kept before purge |
| `ludwig.pat.last-used-debounce` | `5m` | Minimum interval between `last_used_at` writes |
| `ludwig.pat.inactivity-expiry` | `180d` | Unused tokens are revoked after this (`0` disables) |
| `ludwig.pat.dormancy-threshold` | `7d` | Gap after which a use is audited as `pat.dormant-wake` |
| `ludwig.pat.sweep-batch-size` | `500` | Rows per maintenance batch |
| `ludwig.pat.exchange.enabled` | `true` | Mount the exchange |
| `ludwig.pat.exchange.path` | `/oauth2/token` | |
| `ludwig.pat.exchange.issuer` | - | **Required**; `iss` of minted assertions |
| `ludwig.pat.exchange.audiences` | `[]` | Audiences this issuer will mint for (empty logs a warning) |
| `ludwig.pat.exchange.assertion-lifetime` | `5m` | Lifetime of minted assertions |
| `ludwig.pat.rate-limit.enabled` | `true` | |
| `ludwig.pat.rate-limit.per-key-failures` | `10` | Failures per key id per window |
| `ludwig.pat.rate-limit.per-source-failures` | `100` | Failures per source per window |
| `ludwig.pat.rate-limit.window` | `1m` | |
| `ludwig.pat.introspection.enabled` / `.path` | `true` / `/introspect` | |
| `ludwig.pat.web.base-path` | `/api/v1/personal-access-tokens` | Management API |
| `ludwig.pat.liquibase.enabled` | `true` | Apply the changelog as an own bean |
| `ludwig.cache.caches.pat-verification.*` | purpose `security` | Verification cache (see cache starter) |

## Deployment

1. **Register the `lpat_` pattern with every secret scanner before the first token is issued** (`pat-core`'s
   `pat-secret-detection.properties` has a ready `gitleaks` rule).
2. Schema: `db/changelog/pat/pat-changelog.xml` (table `ludwig_pat`, unique indexes on `key_id` and
   `previous_key_id`, indexes on `owner_subject` and `expires_at`), included or run by
   `PatLiquibaseAutoConfiguration`.
3. A signing key (`RSAKey` bean) or a `PatAssertionMinter` delegating to the provider.
4. `/oauth2/token` (and `/introspect` if used) in `ludwig.security.public-paths`; reachable only inside the mesh.
5. Grant `pat:manage` to token holders, and `pat.read` / `pat.privileged` in `ludwig.admin.grants` only to administrators.
6. **Schedule the maintenance operations** - see Known gaps below.
7. Configure the edge as described above.

## Observability

**Metrics:**

| Metric | Type | Tags | Meaning |
|---|---|---|---|
| `ludwig.pat.exchange` | counter | `audience` | Successful exchanges. Should track *distinct tokens per assertion lifetime*, not request volume |
| `ludwig.pat.exchange.failure` | counter | `reason` (`malformed`, `unknown-key`, `bad-secret`, `revoked`, `expired`, `source-not-allowed`, `audience-not-permitted`, `audience-not-issuable`, `audience-missing`, `rate-limited`) | Refused exchanges |
| `ludwig.pat.rate-limited` | counter | `limit` | Rate-limit trips |
| `ludwig.pat.last-used.write-failed` | counter | | `last_used_at` could not be recorded (exchange still succeeded) |

**Logs.** `WARN` when the exchange or introspection path is missing from `public-paths`, when `audiences` is empty,
when a PAT-authenticated request is refused on the management surface, when last use cannot be recorded. `INFO` when a
disabled owner's tokens are revoked.

**Recommended alerts:**

| Condition | Why |
|---|---|
| `increase(ludwig_pat_exchange_failure_total{reason="revoked"}[15m]) > 0` | Something still holds a revoked credential |
| `rate(ludwig_pat_exchange_total[5m])` proportional to service request volume | The edge is not caching |
| `increase(ludwig_pat_exchange_failure_total{reason=~"bad-secret|unknown-key"}[5m])` high | Guessing / brute force |
| `ludwig_pat_rate_limited_total` rising | Same, or a misconfigured client |

**Known gaps:**

- `PatService.destroyExpiredSecrets()`, `endElapsedRotationOverlaps()`, `revokeInactive()` and `purge()` are **not
  scheduled by the starter**. Verification still enforces expiry and the rotation overlap by time, but without a
  scheduler: unused tokens are never revoked after `inactivity-expiry`, digests of expired secrets stay in the table,
  and terminal records are never purged after `retention`. A deployment must run them on one replica (for example
  a `job-core` `ScheduledJob` under a `RunLock`).
- No gauge of live tokens by state, and no metric for the maintenance operations.

## Audit

Category `credential`; actions:

| Action | When |
|---|---|
| `pat.issued` / `pat.issuance-refused` | Issuance and refused issuance (with reason) |
| `pat.rotated` | Rotation |
| `pat.revoked` | Revocation (manual, owner disabled, inactivity) with reason |
| `pat.expired` | Expiry processed |
| `pat.first-use` | First successful exchange of a token |
| `pat.dormant-wake` | Use after more than `dormancy-threshold` of inactivity |
| `pat.unseen-source` | Use from a source not seen before |
| `pat.revoked-token-presented` | A revoked token was presented |
| `pat.purged` | Terminal record deleted by retention |

Secrets and digests never appear in events.

## Administration

Operator endpoints are on the platform's administrative surface, `/admin/v1/{area}`, and exist only where `security-spring-boot-starter`'s surface does. Each is guarded by one operation; which of the service's roles hold it is the service's own `ludwig.admin.grants` (roles from the authority resolver, never token claims), and an operation granted to no role is refused to everyone. A `privileged` call needs an `Admin-Reason` header, and every call is audited under category `admin` with the operation id as its action. `GET /admin/v1/me/operations` lists what the caller holds.

| Operation | Endpoints | Allows |
|---|---|---|
| `pat.read` | `GET /admin/v1/pat/tokens`<br>`GET /admin/v1/pat/tokens/{id}` | See any owner's personal access tokens, without a secret or its hash. |
| `pat.operate` | none in this release | No operation yet; reserved so that grants for this area stay the same across releases. |
| `pat.privileged` | `POST /admin/v1/pat/tokens`<br>`POST /admin/v1/pat/tokens/{id}/revoke` | Issue a personal access token for another owner, and revoke anybody's. |

**Removed:** `pat:manage:any` (`PatAuthorities.MANAGE_ANY`); the self-service API acts only on the caller's own tokens and refuses (and audits) a request naming another owner. A caller presenting a personal access token is refused here with the credential-not-permitted problem even when `ludwig.admin.allow-personal-access-tokens=true`: a token may not mint, rotate or revoke a token.

**Filtering `GET /admin/v1/pat/tokens`.** The listing takes an OData `$filter` and no other filter parameter; it pages with `cursor` and `size` in a fixed newest-first order, so `$orderby`, `$top`, `$skip` and `$count` are `400`, as is a field not listed here. The filter is applied under the area's own rules and can only narrow them. Filterable fields: `ownerSubject`, `expiresAt`, `revokedAt`, `lastUsedAt`, `useCount`. A live token is `revokedAt eq null and (expiresAt eq null or expiresAt gt <now>)`. Not filterable: the secret digests, the key ids, the name, the scopes, the last-used address, the revocation reason. Example: `$filter=revokedAt eq null and lastUsedAt lt 2026-07-31T00:00:00Z`.

Grant block (the role names are the service's own):

```yaml
ludwig:
  admin:
    grants:
      "[pat.read]": [ORDERS_ADMIN, ORDERS_SUPPORT]
      "[pat.operate]": [ORDERS_ADMIN]
      "[pat.privileged]": [ORDERS_ADMIN]
```

## Runbook

| Symptom | Likely cause | What to do |
|---|---|---|
| Every PAT fails at the edge with 401 | `/oauth2/token` not public, issuer/signing key misconfigured, or edge not exchanging | Check startup `WARN`s, `ludwig_pat_exchange_failure_total` reasons, the edge config |
| `audience-not-permitted` / `audience-not-issuable` | Token not issued for that service, or service not in `exchange.audiences` | Re-issue with the audience, or add it to `audiences` |
| A token must be killed now | Leak | `DELETE /api/v1/personal-access-tokens/{id}` (owner) or `POST /admin/v1/pat/tokens/{id}/revoke` (`pat.privileged`, with `Admin-Reason`); effective within the revocation window (≤ 11m by default) |
| Owner left the company | - | Disabling the principal (`PrincipalDisabledEvent`) revokes their tokens (`PatOwnerDisabledListener`); verify with `SELECT id, revoked_at, revocation_reason FROM ludwig_pat WHERE owner_subject = '<sub>';` |
| Old tokens never get revoked for inactivity / table grows | Maintenance not scheduled | See Known gaps; run `revokeInactive()` and `purge()` on a schedule |
| `last-used.write-failed` rising | Database contention on `ludwig_pat` | Raise `last-used-debounce`; check DB |

## Testing

Unit: service rules, last-used tracking, cache definition, audit events, owner-disabled revocation. Integration
(Docker): `TokenExchangeIT`, `UniformExchangeFailureIT` (every failure is indistinguishable), `ExchangeRateLimitIT`,
`PatIntrospectionIT`, `UniformIntrospectionFailureIT`, `PatDirectAuthenticationIT`, `PatManagementIT`,
`PatRetentionIT`, `PatSchemaIT`, `CredentialRejectedProblemIT`. `SqlConfinementTest` (no SQL/JDBC) and
`PatArchitectureRulesTest`.

## Design decisions

| Rule | Enforced by |
|---|---|
| Effective authority = owner ∩ scopes, never union | single construction site, `credentials.one-attenuation-path` |
| No second credential store, SPI or token format | `credentials.no-second-credential-store`, `...-spi` |
| The raw secret never leaves the credential modules | `credentials.secret-carrier-stays-inside`, fenced accessor, masked `toString()` |
| A token cannot manage tokens | `PatCredentialGuard` on every endpoint |
| Verification cache is a bounded revocation window | `CachePurpose.SECURITY` |
| No weak digest algorithm | Checkstyle `WeakDigestAlgorithm` |

- **Edge exchange, not client exchange**: OAuth logic stays out of every `curl` and CI script.
- **Introspection is the fallback route** until the edge can exchange; table, API, rotation, revocation, audit and
  attenuation are the same either way.
- **No shared credential table** for deploy keys or webhook secrets: different lifecycles and authorities.
- **The last-use write touches only its own columns**: one version-less `UPDATE` of `last_used_at`, `last_used_ip`
  and `use_count`, never moving the time backwards, and `PatEntity` writes only changed columns. A revoke or rotation
  that overlaps a use therefore neither fails nor writes a stale last use back (`PatUsageRaceIT`).
- **No streaming-transport enforcement yet**: `revalidation-interval` exists; the first streaming transport owes the
  enforcing test.
