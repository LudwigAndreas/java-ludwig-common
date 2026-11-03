# security-spring-boot-starter

***English** · [Русский](README.ru.md)*

Authentication and authorization for Spring Boot services behind an nginx/Envoy edge. One principal
(`LudwigPrincipal`) for browser users (session cookie exchanged for a JWT at the edge), mTLS partners (Envoy
`x-forwarded-client-cert`) and peer services; roles resolved per service from a local projection instead of from
token claims; data-level authorization compiled into type-safe QueryDSL predicates plus a single-object check;
personal access token attenuation; localized RFC 9457 401/403; audit and metrics. Every default fails closed.

| | |
|---|---|
| Role | starter |
| Depends on (in-repo) | [`audit-core`](../audit-core), [`cache-spring-boot-starter`](../cache-spring-boot-starter), [`pat-core`](../pat-core), [`web-core-spring-boot-starter`](../web-core-spring-boot-starter) |
| Used by | `export`, `file-action`, `grpc-client`, `grpc-server`, `identity-projection`, `pat`, `user-settings` starters, `test-support-security`; both services |
| Surfaces | REST (filter chain, method security); outbound introspection call (optional PAT filter) |

Deployment model this module assumes:

- browser users hold an **opaque session cookie**; the edge exchanges it for a short-lived JWT per request;
- partners call the REST API over **mTLS**, terminated by Envoy;
- peer services call with their own **workload identity**;
- the OIDC provider issues **identity only** (no roles) and streams the user directory over Kafka into each
  service's database ([identity-projection](../identity-projection-spring-boot-starter));
- access is restricted **by resource** (may this caller call this endpoint) and **by data** (which rows).

### Edge vs service

| Concern | Where | Why |
|---|---|---|
| TLS/mTLS termination, client certificate validation | **Edge** | Holds the keys and the partner trust bundle |
| Session cookie → JWT, CSRF, session revocation | **Edge** | The session store lives there; a service that never sees a cookie cannot be CSRF'd |
| Stripping client-supplied identity headers | **Edge and service** | One misconfigured route would otherwise be privilege escalation |
| Coarse routing denials, rate limits, size caps | **Edge** | Cheap; keeps abuse off the service |
| Token validation (signature, issuer, **audience**, expiry) | **Service** | Audience is per service; a service must not assume it is only reached through the gateway |
| Role / permission checks | **Service** | Roles are the service's own vocabulary |
| Data scope (which rows) | **Service** | The gateway does not know what a row is |

A gateway cannot do row-level authorization, and gateway-only enforcement fails open for every path that does not
traverse it (service-to-service calls, Kafka consumers, schedulers, port-forwards). The gateway is defence in depth,
never the decision point.

```mermaid
flowchart LR
    subgraph Browser
        B["Browser<br/>session cookie"]
    end
    subgraph Partner
        P["Partner system<br/>client certificate"]
    end
    subgraph Edge
        N["nginx + session layer<br/>cookie -> short-lived JWT<br/>CSRF, strips identity headers"]
        E["Envoy<br/>mTLS termination<br/>sets x-forwarded-client-cert"]
    end
    subgraph Service["Your service (this module)"]
        F["Filter chain<br/>strip headers -> mTLS -> JWT"]
        A["AuthorityResolver<br/>roles from local projection"]
        R["@PreAuthorize<br/>resource level"]
        D["DataAccessGuard<br/>row level"]
        Q[("Postgres")]
    end
    B --> N --> E
    P -- mTLS --> E
    E --> F --> A --> R --> D --> Q
```

### The three callers

| | Browser user | Partner | Peer service |
|---|---|---|---|
| Credential at the edge | session cookie | client certificate | workload certificate |
| Credential at the service | JWT minted by the edge | `x-forwarded-client-cert` | JWT (client credentials) or XFCC |
| Identity | OIDC `sub` | partner code | SPIFFE workload id |
| `PrincipalType` | `USER` | `PARTNER` | `SERVICE` |
| Roles from | local projection of the OIDC stream | partner registry (table or config) | configuration |
| Typical data scope | `OWN`, `TENANT` or `ALL` | `PARTNER` | `ALL` for its narrow purpose |

### Why roles are not in the token

The JWT carries `sub`, a name and maybe a tenant. Roles come from `AuthorityResolver`, implemented by
identity-projection against the Kafka-fed user table:

- **revocation is bounded by the authority cache TTL (seconds), not the token lifetime**;
- tokens stay small; granting a role needs no re-issue;
- each service owns its vocabulary;
- a token carrying a `roles` claim is harmless - nothing reads it.

The cost is a lookup per request; `AuthorityLookup` caches it in the platform cache `authorities`
(`purpose: security`, see [cache](../cache-spring-boot-starter)), and identity-projection evicts on every user change.

## Usage

```xml
<dependency>
    <groupId>ru.ludwigandreas</groupId>
    <artifactId>security-spring-boot-starter</artifactId>
</dependency>
<!-- roles and grants from the OIDC stream; without it nobody has any role -->
<dependency>
    <groupId>ru.ludwigandreas</groupId>
    <artifactId>identity-projection-spring-boot-starter</artifactId>
</dependency>
```

### Operations and roles

Code authorizes by **operation**, never by role. A module declares what it does; the service's configuration alone
says which roles may do it, and on which rows.

```java
@Bean
OperationContributor orderOperations() {
    return () -> List.of(
            Operation.business("sales.order.read", Sensitivity.READ, "sales.op.order.read").on("order", DataAction.READ),
            Operation.business("sales.order.update", Sensitivity.CHANGE, "sales.op.order.update").on("order", DataAction.WRITE),
            Operation.business("sales.order.purge", Sensitivity.PRIVILEGED, "sales.op.order.purge"));
}

@GetMapping("/{id}")
@RequiresOperation("sales.order.read")
public OrderResponse get(@PathVariable UUID id) { ... }
```

```yaml
ludwig:
  security:
    roles:
      ORDER_AGENT:
        operations:
          sales.order.read: OWN          # only rows they created
          sales.order.update: OWN
      ORDER_ADMIN:
        includes: [ORDER_AGENT]
        operations:
          sales.order.read: ALL
          sales.order.update: ALL
          sales.order.purge: ALL
```

| Piece | What it is |
|---|---|
| Operation id | `<area>.<object>.<verb>`, three lower-case segments. A read covers listing and fetching one kind of object; each distinct change is its own operation |
| Sensitivity | `READ` (changes nothing), `CHANGE` (changes state), `PRIVILEGED` (can lose data, changes who may do what, or decides whether a dependency is called). `PRIVILEGED` needs an `Operation-Reason` header (1-500 characters, `400 ludwig.security.reason-required` otherwise) and refuses personal access tokens unless `ludwig.security.operations.allow-personal-access-tokens=true` |
| Surface | `BUSINESS` (the service's own API) or `ADMIN` (only under `/admin/v1/`; see below). A mismatch with the handler's path fails startup |
| `@RequiresOperation("<id>")` | The only authorization code declares. On a controller, service or gRPC method; enforced through method security, the administrative guard, or the gRPC interceptor - one evaluation |
| `@RequiresAuthentication` | A method any authenticated caller may use |
| `ludwig.security.roles.<ROLE>` | `operations` (operation to scope) and `includes` (roles whose operations it also holds, transitively). A role is matched with or without `ROLE_` |
| Scope | `ALL`, or dimensions joined by `+` (`OWN+TENANT`) - AND within a grant, OR across roles. `OBJECT` is granted per subject (see the administrative surface) |

Fails closed: an operation no role lists is refused to everyone and logged once; a role naming an unknown operation, a
wildcard, `NONE`, an `includes` cycle or undefined role, a dimension the operation's resource does not bind, an
annotation naming an uncatalogued operation and a description key in no bundle each fail startup. The keys this
replaced (`ludwig.admin.grants`, `ludwig.security.data.policies`, `ludwig.security.data.default-access`,
`ludwig.admin.allow-personal-access-tokens`) fail startup when still set. Roles are this service's resolved roles -
the identity projection for people, `ludwig.identity.service-roles` for services - never a token claim, and the PAT
intersection still applies.

`GET /me/operations` lists what the caller holds - id, sensitivity, surface, scope and a localized description - so a
UI knows which controls to show; it never lists what the caller does not hold. `GET
/admin/v1/security/operations/{id}/holders` (`security.operation.read`) lists the roles holding an operation, with
their scope and, when inherited, the role it came from. Method security is enabled by the module whenever it is,
whatever `ludwig.security.data.enabled` says.

### Data level

Declare which columns carry which scope dimension:

```java
@Bean
DataScopeMapping<OrderEntity> orderScopeMapping() {
    QOrderEntity order = QOrderEntity.orderEntity;
    return DataScopeMapping.forResource("order", OrderEntity.class)
            .owner(order.createdBy, OrderEntity::getCreatedBy)
            .tenant(order.tenantId, OrderEntity::getTenantId, UUID::fromString)
            .partner(order.partnerId, OrderEntity::getPartnerId)
            .build();
}
```

An operation that names the resource (`.on("order", DataAction.READ)`) is scoped by it, and each role's scope for that
operation in `ludwig.security.roles` is what its rows are filtered by. Use it where rows are reached:

```java
// 1. Query time - the scope is part of the WHERE clause.
queryFactory.selectFrom(order)
        .where(guard.predicate("sales.order.read").and(criteria))
        .fetch();

// 2. Load time - the safety net for paths a query predicate never sees.
OrderEntity entity = repository.getByIdOrThrow(id);
guard.check("sales.order.read", entity, OrderEntity::getId);
```

or as an annotation backed by the same mapping: `@PostAuthorize("hasPermission(returnObject, 'sales.order.read')")`
- the permission is an operation, and the object's class must belong to its resource.

**Both halves are needed.** Pre-filtering alone misses loads by id, relations and objects rebuilt from events;
post-checking alone breaks pagination and totals. The mapping declares a column (predicate) and an accessor (check)
together, so they cannot drift.

**Three shapes of rule:**

| Rule | Binding | Example |
|---|---|---|
| One column holds one value | `.owner` / `.tenant` / `.partner` / `.bind` | orders this user created |
| The row relates to many values, any may match | `.bindCollection` | orders this user is mentioned on |
| Anything else | `.bindExpression` | mentioned or creator, and not revoked |

```java
DataScopeMapping.forResource("order", OrderEntity.class)
        .owner(order.createdBy, OrderEntity::getCreatedBy)
        .bindCollection(ScopeDimension.of("mentioned"),
                order.mentions.any().userId,                                   // query side: correlated EXISTS
                e -> e.getMentions().stream().map(Mention::getUserId).toList()) // load side
        .build();
```

`.bindExpression` is the escape hatch where nothing verifies that predicate and check agree; test them against each
other (`ExpressionScopeBindingTest` shows how).

**Per-subject grants** come from a `DataScopeProvider`, asked with the operation's resource and action (an operation
that touches no rows is asked with its id and `invoke`). It answers for a subject, never by comparing roles:

```java
@Bean
DataScopeProvider coverageGrants(CoverageRepository coverage) {
    return (principal, resourceType, action) -> "order".equals(resourceType) && DataAction.READ.equals(action)
            ? coverage.ordersCoveredBy(principal.subject())    // a time-boxed cover, say
            : DataScope.none();   // contributes nothing; the roles' scopes still apply
}
```

**Composition**: values within a dimension are OR-ed (`IN`); dimensions within a grant are AND-ed (`OWN+TENANT`);
grants are OR-ed; `ALL` beats everything. The roles' scopes and every provider are unioned, so a provider can only
**widen**; denial is nothing granting. A list endpoint
for a caller entitled to nothing returns an **empty page**, not 403. The full model and worked examples are in
[`docs/data-scope-configuration.md`](../../docs/data-scope-configuration.md).

### Background work

`SystemPrincipalTemplate` runs scheduler and listener code under an explicit system identity
(`ludwig.security.system-principal.*`).

web-core is optional. A worker without it - a Kafka listener on job-core, outbox or messaging, a batch job - gets
the whole starter except what renders problems: the problem bundle and `SecurityProblemMapper` exist only with
web-core. Operations are still enforced there, and their refusals are the starter's own `SecurityRefusalException`s
(`ReasonRequiredException`, `PatCredentialNotPermittedException`), which propagate as exceptions; with web-core they
render as `400 ludwig.security.reason-required` and `403 ludwig.pat.credential-not-permitted`, over HTTP and gRPC
alike.

### Personal access tokens

A PAT is an **attenuation** of its owner's live authority. This module reads the `ludwig_pat` claim from the
assertion the edge exchanged and narrows the authorities it resolved:

```
effective = authoritiesOf(owner, now)  ∩  token.scopes()
```

- A role revoked from the owner stops working for their tokens within the authority cache TTL, with no token
  revocation. A token can stop working without being revoked; the denial record names the token and the missing
  authority.
- A token scoped to authority its owner never held grants nothing.
- `PrincipalType` stays three values: a token caller is the same person with less authority. The presented
  credential is on `LudwigAuthentication`, not the principal: `authentication.credential().isLongLived()`,
  `.credentialId()`, `SecurityPrincipals.currentCredential()`.
- A `ludwig_pat` claim whose scope list cannot be read is **rejected** (falling through to "no attenuation" would grant
  full authority). A claim of the wrong *type* is ignored (it cannot come from the exchange).
- Audience scoping needs no new code: `AudienceValidator` enforces the `aud` the exchange mints
  (`PatAudienceScopingTest`).

**Direct PAT authentication** (for an edge that cannot exchange):

```yaml
ludwig:
  security:
    pat:
      filter:
        enabled: true                               # off by default
        issuer-base-url: https://idp.internal
        introspection-path: /introspect
        timeout: 2s
```

The filter recognises `lpat_`, introspects at the issuer (RFC 7662) and builds the authentication through the same
construction site, so the attenuation is identical. One token table and one revocation point remain. The resource
server's bearer-token resolver reports no token for `lpat_` values, so the JWT filter does not reject them first.
Trade-off: issuer availability is needed on each introspection cache miss (fails closed). Remove by setting
`enabled: false` when the edge gains exchange support (`PatDirectAuthenticationIT` covers both configurations).

**Revocation window** - computed, logged and validated at startup (fails above `max-revocation-window`):

```
request/response:   assertion lifetime + edge cache lifetime + authority cache TTL
long-lived:         revalidation interval + authority cache TTL
direct filter:      introspection cache TTL + authority cache TTL   (computed even when disabled)
```

### Administrative surface

Every administrative endpoint on the platform is served under `/admin/v1/` and authorized one way: by the operations
above, declared on the `ADMIN` surface (`Operation.admin(...)`), granted in `ludwig.security.roles` like any other.

| Piece | What it is |
|---|---|
| `@RequiresOperation(<Module>AdminOperations.<CONSTANT>)` | On the handler; the only gate. Never combined with `@PreAuthorize` or a role check |
| `@RequiresAuthentication` | A handler any admitted caller may use (the approvals listing) |
| `AdminAudit` | Records the change through the one `AuditSink`, category `admin`, action the operation id, from the module service inside its transaction |
| `AdminInvocation.require()` | The current operation, caller and reason; `tenantScope()` for tenant-carrying tables |
| `AdminPage` / `AdminCursor` | Keyset pages; at most 200 rows; an undecodable cursor is `400` |

```yaml
ludwig:
  security:
    roles:
      NOTIFICATION_SUPPORT:
        operations:
          outbox.message.read: ALL
      NOTIFICATION_ADMIN:
        includes: [NOTIFICATION_SUPPORT]
        operations:
          outbox.message.retry: ALL
          outbox.message.discard: ALL
```

In a `.properties` file a dotted key needs the bracket form:
`ludwig.security.roles.NOTIFICATION_ADMIN.operations[outbox.message.discard]=ALL`.

What the guard on `/admin/**` (a `HandlerInterceptor`, so it holds whatever filter chain the service runs)
refuses, in order:

| Caller | Answer |
|---|---|
| unauthenticated | `401` |
| a partner | `403` |
| a personal access token, unless `ludwig.security.operations.allow-personal-access-tokens=true` | `403`, `ludwig.pat.credential-not-permitted` - the PAT surface's own type |
| a person (`USER`) not acting through one of `ludwig.admin.human-callers.required-via`, when that list is set | `403`, published as an authorization denial and audited |
| an operation it does not hold | `403`, published as an authorization denial and audited |
| a `PRIVILEGED` operation without `Operation-Reason` (1-500 characters) | `400`, `ludwig.security.reason-required` - only after the grant check |
| an `@AdminObject` outside its scope, where the caller's scope confines objects | `403`, or for a `READ` operation the area's own not-found `404`; audited |
| a handler under `/admin/` with neither annotation, or with a `BUSINESS` operation | `403`, and the latter fails startup |

A `public-paths` entry matching `/admin/v1/` fails startup. The surface exists only with web-core's problem pipeline
present. `ludwig.admin.enabled=false` removes the guard, and an administrative handler still mapped is then refused
(`403`) by method security rather than served.

Acting party: a token's delegation claim (`ludwig.security.jwt.actor-claim`, default `act`, read as RFC 8693
`act.sub`, nested `act` kept as a chain of at most 5) names the party the request came through, for example
`admin-console`. It is identity only, never a source of roles, and is recorded as the audit actor's `via`. A present
but unreadable claim (not an object, no `sub`, nested too deep) refuses the token with `401`; a blank `actor-claim`
turns reading it off. With `ludwig.admin.human-callers.required-via: [admin-console]` a person reaches `/admin/**`
only through that party, while service principals are unaffected: keep a break-glass service principal for a UI
outage. Only a validated token's claim counts, never a header.

Per-object scope: a handler marks the path variable naming its object with `@AdminObject` (its `notFound` is the
area's own not-found code, so an out-of-scope read answers exactly as a missing object), and a listing filters
through `OperationAuthority.admitted`. A role confines an operation to objects by granting it `OBJECT`, and the
objects themselves are granted per subject on dimension `object`, keyed by the operation's id and action `invoke`
(through the identity projection's grants); a role granting `ALL` admits every object. See
[data-scope configuration](../../docs/data-scope-configuration.md#administrative-objects).

Four-eyes approval: `ludwig.admin.approval.required` lists `ADMIN` `PRIVILEGED` operations whose calls a second holder
must approve (empty by default; an uncatalogued id or any other operation, or a list with no `AdminApprovalStore`, fails
startup - job-core provides the JDBC store). Such a call, once authorized and with its reason, is not executed: the
request (method, path, query, body up to 64 KiB - `413` above - and `Operation-Reason`) is recorded and answered `202`
with an approval whose operation envelope is `PENDING`, `Location: /admin/v1/approvals/{id}`. Any caller the surface
admits may use `/admin/v1/approvals`; what they see and decide is limited to the operations they hold:

| Endpoint | Does |
|---|---|
| `GET /admin/v1/approvals?status=` | Approvals of the operations the caller holds, newest first; never request bodies |
| `GET /admin/v1/approvals/{id}` | One approval; its body only to a holder other than the requester, while pending |
| `POST .../{id}/approve` | A holder other than the requester approves: the requester's authority is re-checked (`409` if lost), then the stored request runs through the same handler as the approver, and its status and body (16 KiB) are recorded - envelope `SUCCEEDED` for a 2xx, `FAILED` otherwise |
| `POST .../{id}/reject` | A holder other than the requester rejects (`CANCELLED`) |
| `POST .../{id}/withdraw` | The requester withdraws (`CANCELLED`) |

Self-approval is `403`; a decided, withdrawn or expired approval is `409`. Undecided after `ludwig.admin.approval.ttl`
(`PT24H`) it expires (`EXPIRED`); one left executing by a stopped replica is marked interrupted after five minutes and
never re-run. Request, decision, withdrawal and expiry are audited, and the executed operation's own event carries
`requestedBy` and `approvedBy`. Tenant confinement and object scope apply to the approver at execution.

Tenant confinement: a `USER` reaches only resources of exactly their own tenant (a user without one, only untenanted
resources); a `SERVICE` is not confined. Modules apply `tenantScope()` to tables that carry a tenant.

An administrative UI knows which controls to show from `GET /me/operations`, filtered on `surface=ADMIN`.

## Configuration

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          # issuer-uri performs OIDC discovery at startup: the service will not start while the provider is
          # unreachable. Use jwk-set-uri (fetched lazily) if that matters.
          issuer-uri: https://login.example.internal/realms/corp

ludwig:
  security:
    enabled: true
    problem-type-prefix: "urn:ludwig:security:"
    public-paths: [/actuator/health, /actuator/health/**, /actuator/info]   # replaces the default list
    strip-identity-headers: true
    jwt:
      enabled: true
      subject-claim: sub
      name-claim: name
      tenant-claim: tenant_id
      service-client-claim: client_id   # presence marks the token as a peer service's
      actor-claim: act                  # RFC 8693 delegation, recorded as the audit actor's via; blank disables
      audiences: [my-service]
      require-audience: true
    mtls:
      enabled: false
      trusted-proxies: []               # required when enabled; 0.0.0.0/0 is refused
      trust-native-forward-headers: false
      service-identity-prefix: spiffe://mesh/ns/
      partners:                         # or identity-projection's security_partner table
        acme:
          display-name: ACME GmbH
          spiffe-id: spiffe://partners/acme
          certificate-hash: 468ed3...   # optional pin; update at every renewal
    authorities:
      require-resolver: false           # set true in every deployed environment
    data:
      enabled: true                     # row scoping; method security stays on either way
      unscoped-resources: []            # reference data, deliberately exempt
    roles: {}                           # <ROLE>: { includes: [...], operations: { <operation>: <scope> } }
    operations:
      allow-personal-access-tokens: false   # PRIVILEGED operations and /admin/** refuse PATs
    pat:
      assertion-lifetime: 5m            # match the issuer
      edge-cache-lifetime: 5m           # match the edge
      revalidation-interval: 1m
      max-revocation-window: 15m
      validate-revocation-window: true
      filter: { enabled: false, issuer-base-url: , introspection-path: /introspect, timeout: 2s }
    audit:
      enabled: true
      log-grants: false
    metrics:
      enabled: true
    system-principal:
      subject: system
      roles: []
```

The authority cache is configured under `ludwig.cache.caches.authorities.*` (TTL = how long a revoked role still
works; `purpose: security` enforces a ceiling). The introspection cache is `ludwig.cache.caches.pat-introspection.*`.

## Deployment

### Edge (Envoy) for mTLS partners

```yaml
forward_client_cert_details: SANITIZE_SET   # replace any inbound XFCC; never append to a client's
set_current_client_cert_details:
  uri: true        # SPIFFE id - survives renewal
  dns: true
  subject: true
  cert: false      # the PEM would put kilobytes into every log line
```

`APPEND_FORWARD` would let a client prepend its own element. Reference configurations:
[`docs/envoy-partner-gateway.yaml`](docs/envoy-partner-gateway.yaml), [`docs/nginx-edge.conf`](docs/nginx-edge.conf).

**`trusted-proxies` is load-bearing**: `x-forwarded-client-cert` is an ordinary header, trustworthy only because only
the proxy can reach the port. Set it to the sidecar/ingress subnet and nothing wider; `0.0.0.0/0` is refused. The
header from any other peer is **rejected** (not ignored) and counted.

**`server.forward-headers-strategy`**: `framework` is safe (the module unwraps to the container's peer address);
`native` (Tomcat `RemoteIpValve`) trusts `X-Forwarded-For` from any private-range peer and lets any workload
impersonate the sidecar - **startup fails** with mTLS enabled. If `native` is needed, narrow
`server.tomcat.remoteip.internal-proxies` and set `ludwig.security.mtls.trust-native-forward-headers=true`.

Partners are matched by SPIFFE URI, then DNS SAN, then exact subject DN, optionally pinned to a fingerprint. Map to
an identifier that survives certificate renewal.

### CSRF and statelessness

The filter chain is stateless and CSRF is off: the session cookie never reaches the service. **If a service ever
accepts the session cookie directly, re-enable CSRF in the same change.**

### Adoption procedure for an existing service

1. Declare each module's operations and move every authorization to `@RequiresOperation`; there is no "allow
   everything" mode to migrate under, by design.
2. Write `ludwig.security.roles` one role at a time, granting `ALL` first; the startup log lists operations no role
   holds, and `/admin/v1/security/operations/{id}/holders` shows who holds what.
3. Write `DataScopeMapping` beans and narrow the scopes (`OWN`, `TENANT`), starting with the most sensitive data.
4. Turn on `authorities.require-resolver` and `jwt.require-audience` in every deployed environment.
5. Enable mTLS on partner routes last, after `trusted-proxies` is correct in each environment.

## Observability

**Metrics:**

| Metric | Type | Tags | Meaning |
|---|---|---|---|
| `ludwig.security.authentication` | counter | `principal.type`, `outcome`, `reason` | Authentication results; `reason=untrusted-proxy` = something bypasses the mesh |
| `ludwig.security.access.denied` | counter | `resource`, `action` | Denials (role and row level) |
| `ludwig.security.authorities.cache` | counter | `principal.type`, `result` | Authority cache hits/misses |
| `ludwig.security.data.scope` | counter | `resource`, `access` | Effective scope width; `access=ALL` on a restricted resource = a role's scope is wider than intended |

Tags use a bounded vocabulary only; nothing caller-controlled.

**Logs** (`WARN` unless noted): XFCC from an untrusted peer (rejected), certificate matched by identifier but
fingerprint differs, no partner registered for a certificate, PAT introspection unreachable, unusable data-scope
value, `hasPermission` without a mapping (denied), `public-paths` entry that permits a broad pattern,
`validate-revocation-window=false`, failed denial audit. `INFO`: audience validation enabled, PAT revocation window
values. `WARN`: operations no role holds.

**Recommended alerts:**

| Condition | Why |
|---|---|
| `increase(ludwig_security_authentication_total{reason="untrusted-proxy"}[5m]) > 0` | Direct access bypassing the mesh, or spoofing |
| Spike in `ludwig_security_access_denied_total` for one resource | Probing, or a broken client/role mapping |
| `ludwig_security_data_scope_total{access="ALL"}` for a resource expected to be restricted | Policy not matching |
| Log `Could not introspect a personal access token` | Issuer down: PATs failing closed |

## Audit

Decisions go through the platform `AuditSink`:

| Field | Value |
|---|---|
| category | `access` |
| action | `access.denied` (role-level via `AuthorizationDeniedAuditListener`, row-level via `DataAccessGuard`); `access.granted` only with `audit.log-grants: true` |
| outcome | `DENIED` for refusals (not `FAILURE`) |
| attributes | resource type and id, action, reason, `scopeAccess`, PAT id and missing authority for token denials |

No payloads, no tokens. `PrincipalActorResolver` (registered regardless of `ludwig.security.enabled` and
`audit.enabled`) teaches the platform's actor resolution about `LudwigPrincipal`, so every `AuditEvent.actor` and
`created_by` carry the subject.

**Known gap:** authentication failures (missing, invalid or expired tokens, rejected certificates) are counted in
`ludwig.security.authentication` but not recorded in the audit trail.

## Runbook

| Symptom | Likely cause | What to do |
|---|---|---|
| Service does not start: OIDC discovery | `issuer-uri` provider unreachable | Restore connectivity, or use `jwk-set-uri` |
| Startup fails on `ludwig.security.roles` | Unknown operation, unmapped resource, a dimension the resource does not bind, `NONE`, a wildcard, an `includes` cycle, a removed key still set | Fix as the message names |
| Startup fails: `trusted-proxies` / `forward-headers-strategy=native` | Unsafe mTLS settings | Narrow proxies; see Deployment |
| Startup fails: no audience / no resolver | `require-audience` / `require-resolver` with nothing configured | Configure `jwt.audiences`; add identity-projection |
| All users get 403 | No `AuthorityResolver` data (projection empty), or no role holds the operation | Check identity-projection consumption; check the operation's holders |
| A user still has a revoked role | Authority cache TTL | Wait ≤ TTL (`ludwig.cache.caches.authorities.ttl`) |
| Partner gets 401 after certificate renewal | Partner mapped by a non-stable identifier or pinned fingerprint | Map by SPIFFE URI/SAN; update `certificate-hash` |
| `reason=untrusted-proxy` | Request reached the pod without the mesh proxy | Find the path (port-forward, extra ingress); fix network policy |
| 500 instead of 403 inside controllers | Neither web-core nor own advice handles `AccessDeniedException` | Add web-core (recommended) |
| "Why can't user X do Y?" | - | `SELECT occurred_at, action, resource_type, resource_id, reason, attributes FROM audit_event WHERE category = 'access' AND actor_subject = '<x>' ORDER BY occurred_at DESC LIMIT 50;` |
| PATs fail in a service using the direct filter | Issuer unreachable, `issuer-base-url` wrong | Check the `Could not introspect` warning and connectivity |

## Testing

Unit tests for XFCC parsing, trusted proxies, the mTLS filter, audience validation, authority caching, scope
composition and predicate building (including collection and expression bindings), the data guard and permission
evaluator, method-security auditing, problem mapping, PAT attenuation, the PAT-aware bearer resolver, introspection
client and cache, revocation window validation, auto-configuration. In a service, use
[`test-support-security`](../test-support-security) to build principals for MockMvc.

## Design decisions

| Situation | Behaviour | Why not the other way |
|---|---|---|
| Operation no role holds | Denied, and logged at startup | A new endpoint would ship unprotected |
| Policy names a resource with no mapping | Startup failure | It would be treated as unrestricted |
| Policy names an unbound dimension | Startup failure | An unenforceable clause must not be ignored |
| Misspelled grant (`OWNN`) | Startup failure | Otherwise a 500 on the first request using that role |
| Resource both unscoped and policed | Startup failure | One silently wins |
| Principal lacks a required dimension value | Denied | Dropping the clause turns "my tenant" into "all tenants" |
| Grant value unparseable for the column type | Denied | An empty set, not an absent filter |
| Role store unreachable | Request fails (500) | "No roles" on a blip is an outage and, on denial-phrased rules, a silent grant |
| XFCC from an untrusted peer | Rejected and counted | Ignoring hides spoofing |

- **No session handling, login pages or token issuance** - the edge's job.
- **No Hibernate `@Filter` or PostgreSQL RLS**: QueryDSL predicates over generated Q-types break the build on a column
  rename. Add RLS *underneath* for containment against a compromised service.
- **No distributed authority cache by default**: authorities are cheap to recompute and a shared cache is a
  poisoning target; eviction rides the Kafka topic every instance consumes.
- **Uninformative 401/403 bodies**; specifics are in the audit trail. Messages are overridable by defining the same
  key in the service's bundle.
- **No streaming-transport enforcement yet**: `revalidation-interval` exists; the first long-lived transport must add
  the enforcing test.
