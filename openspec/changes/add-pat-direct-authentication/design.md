## Context

See `proposal.md` - Why. This section records only the facts that constrain the approach.

**The edge cannot help, and that is not negotiable.** The company-provided proxy gateway does session
→ JWT and nothing else. It cannot be extended by us. A request with no session is redirected to OIDC.

**Everything else from `add-personal-access-tokens` is built, tested and reusable.** The token table,
the format, the digest, the management API, rotation with overlap, revocation, retention, the audit
events, the `CachePurpose.SECURITY` verification cache, the `PatVerifier` with its ordered cheap-first
checks, the `ludwig_pat` claim reader and the attenuation intersection all exist and pass. This change
adds one authentication path and touches nothing else.

**Two constraints from the shipped work carry forward unchanged:**

1. `security-spring-boot-starter` has **no persistence dependency** and sits near the bottom of the
   reactor. Whatever it does here must not change that.
2. There is **exactly one construction site** of a credential-backed authentication, enforced by
   `credentials.one-attenuation-path`. A second authentication path must not become a second place
   where authority is computed.

## Goals / Non-Goals

Proposal-level scope is in `proposal.md` - Non-goals. Design-level boundaries only:

**Goals:**

- A PAT works **today**, with no change to the company gateway, no new proxy, no second issuer and no
  client-side exchange.
- The removal is **one property**, and what remains afterwards is exactly what the edge-exchange world
  needs - so this is scaffolding rather than a fork.
- The enforcement around the attenuation invariant gets **stronger**, not weaker, despite there now
  being two ways in.

**Non-Goals:**

- Matching the exchange route's availability profile. It will not: see D5.
- Solving the routing precondition. That is infrastructure and is named as a risk, not designed here.

## Decisions

### D1. Verify the PAT in the service; do not mint a JWT of our own

The tempting shape is "write our own identity provider for PAT → JWT". Rejected for three reasons, and
the third is decisive:

1. **A second issuer.** Services trust the company's `issuer-uri`. A JWT we sign comes from a
   different issuer, so every service must trust two - which Boot's single `issuer-uri` property
   cannot express, requiring a `JwtIssuerAuthenticationManagerResolver` and a change to
   `ResourceServerAutoConfiguration`'s decoder wiring.
2. **A signing key of our own** to generate, store, rotate and publish a JWKS for. A key regenerated
   on restart invalidates every assertion in flight, which presents as intermittent authentication
   failures rather than as a configuration error.
3. **There is still nothing to perform the exchange.** This is the one that settles it. The whole
   purpose of exchanging at the edge is that the client does not have to. Our edge will not. So either
   every `curl` runs the exchange itself - precisely what the shipped design exists to prevent - or we
   deploy our own proxy in front of everything. A second issuer, a key to rotate, and a new proxy, in
   order to avoid one in-cluster hop.

Verifying directly costs that one hop and buys all three back.

```
    CI / curl / future MCP client
       │  Authorization: Bearer lpat_...
       │  routed AROUND the company gateway  (precondition - see Risks)
       ▼
  ┌───────────────────────────────────────────────────────────┐
  │ any service running security-spring-boot-starter          │
  │                                                           │
  │  strip-headers → mTLS → ★PatAuthenticationFilter★ → bearer │
  │                            │                              │
  │                            │ cache miss only              │
  │                            ▼                              │
  └────────────────────────────┼──────────────────────────────┘
                               │ POST /introspect
                               ▼
                    ┌──────────────────────────────┐
                    │ the issuing service          │
                    │  ONE token table             │
                    │  PatVerifier (unchanged)     │
                    │  no signing key, no JWKS     │
                    └──────────────────────────────┘
```

### D2. Introspection, RFC 7662-shaped, over one central table

The filter asks the issuing service a question and gets facts back:

```
POST /introspect              (form-encoded, as RFC 7662 specifies)
  token=lpat_...
→ 200 { "active": true, "sub": "alice",
        "scope": "orders:read deploy:write",
        "aud": ["deploy-service"], "pat_id": "pat-42", "exp": 1780000000 }
→ 200 { "active": false }     for every failure
```

**One table, one revocation point.** The shipped design rejected per-service verification because N
tables means N revocation surfaces, and a leaked token revoked in one of N places is the failure a PAT
exists to prevent. That objection is fully preserved here: there is still exactly one table, in the
issuing service, and a revocation takes effect there.

**`{"active": false}` for every failure**, exactly as the exchange returns one indistinguishable body
for all eleven causes. The reason is tagged in the issuer's metrics and audit and disclosed nowhere -
the same no-oracle property, reused rather than restated, because `PatVerifier` already throws
`ExchangeRefusedException` with the reason and the endpoint already has somewhere to put it.

**Never a secret, never a digest, never a key id.** The response carries what a principal needs and
nothing a credential could be reconstructed from. The key id is excluded for the reason it is excluded
everywhere else: it is secret-adjacent lookup material that changes on rotation, and `pat_id` is the
identifier that survives one.

Rejected alternative: have the filter call `/oauth2/token` and verify the returned JWT. It would work
and it reintroduces the second issuer from D1 to avoid writing one endpoint.

### D3. The single attenuation path is preserved by **narrowing** the rule, not widening it

This is the decision that keeps the change from weakening what the last one built.

`credentials.one-attenuation-path` currently fences construction of a credential-backed
`LudwigAuthentication` to `ru.ludwigandreas.security.authn.jwt..`. A filter in a new package would be a
second construction site, and the obvious response - add the filter's package to the rule - **widens**
it. Two packages today, three next year, and the invariant is gone by increments.

Instead: extract the attenuation and the principal construction into **one dedicated type**, and
re-point the rule at that type alone.

```
            ┌──────────────────────────────────────────┐
            │ authn.attenuation.AttenuatedAuthentications│  ← the ONLY construction site
            │   owner authorities ∩ token scopes        │
            └───────────▲────────────────▲─────────────┘
                        │                │
         JwtPrincipalConverter      PatAuthenticationFilter
         (reads ludwig_pat claim)   (reads introspection)
```

The rule becomes **stricter** than it was: one class rather than a package. Both callers supply a
subject and a scope set and receive an authentication; neither computes authority. So "the
intersection cannot be bypassed" remains a structural property with two entry points rather than a
promise repeated in two places.

Worth stating plainly because the rule's own javadoc makes the argument this relies on: *"the
intersection is one line, the union is also one line, and the difference between them is invisible in
review"*. That is as true of a filter as it was of a converter.

### D4. The filter sits between mTLS and the bearer-token filter

```
strip-identity-headers → mTLS → PAT → bearer-JWT → MDC
```

The chain's javadoc explains four filters and says a fifth must justify its position. The
justification is the one the mTLS filter already makes, almost verbatim: **a PAT request carries no
bearer JWT, so the resource server downstream would reject it before the token was ever looked at.**
That is exactly why mTLS runs before the bearer filter, and it is the same reason here.

After header stripping, so nothing the filter reads can be a client-supplied identity header. Before
the bearer filter, which is left to handle the ordinary case and only when nothing has authenticated
yet - so a request carrying a real company JWT is untouched by this filter, which simply sees no
`lpat_` prefix and passes through.

**Off by default** (`ludwig.security.pat.filter.enabled=false`). Adding the starter must not silently
open a second authentication path; and a deployment whose edge *can* do the exchange should use the
exchange.

### D5. A third revocation-window composition, and it is the worst of the three

`RevocationWindowValidator` computes two sums today. This adds a third:

```
request/response via the edge:  assertion lifetime + edge cache + authority cache
long-lived connections:         revalidation interval + authority cache
PAT filter (this change):       introspection cache TTL + authority cache
```

With a 30s introspection cache and a 60s authority cache that is **90 seconds** - shorter than the
edge route's eleven minutes, which looks like an improvement and is not the interesting comparison.
The interesting one is availability:

| | issuing service unreachable |
|---|---|
| edge exchange | PATs keep working up to the assertion lifetime (~5 min) |
| **PAT filter** | **PATs stop working within the introspection cache TTL (~30 s)** |

That is the real cost of this route and it belongs in the open. The issuing service becomes a
**per-request dependency** of every service that enables the filter, bounded by a cache rather than
eliminated. Accepted because the alternative is that PATs do not work at all, and because the bound is
short, explicit and computed at startup like the other two.

All three are computed and logged; the largest is checked against the ceiling. Computing only the
first two would log a correct-looking number while being false for exactly the callers this change
creates.

### D6. The removal path, and what survives it

When the company gateway gains PAT support:

```
ludwig.security.pat.filter.enabled = false
```

That is the whole removal. What then happens is the shipped design: the gateway calls
`/oauth2/token`, the assertion arrives carrying `ludwig_pat`, and `JwtPrincipalConverter` reads it -
code that is **already written and tested** and that this change does not touch.

What survives in both worlds: the token table, the management API and its credential guard, rotation,
revocation, retention, every audit event, the attenuation intersection, `PatAuthorities`, the
`CachePurpose.SECURITY` caches, and the whole of `pat-core`. What is deleted: one filter, one client,
one endpoint, one property. That ratio is what makes this scaffolding rather than a fork, and it is
the reason to prefer it over a second issuer - which would leave a signing key, a JWKS and a
multi-issuer decoder behind for somebody to clean up.

### D7. The introspection client authenticates as the service, never as the token

The call is service-to-service and carries no end user. It uses the calling service's own identity -
the workload certificate the mesh already provides - and never relays the PAT as its own credential.

That is the same rule that keeps a PAT off the management surface: a credential that can operate on
credentials makes revoking the original pointless. It is also why the introspection endpoint must
**not** accept a PAT-derived authentication, which the existing credential guard already handles for
the management API and which this endpoint inherits by the same mechanism.

### Dependency-direction check

Written out as the question and the answer, per the design rules.

**Does `security-spring-boot-starter` acquire a new in-repo dependency?** No. It already depends on
`pat-core` (added by `add-personal-access-tokens`), which is where the introspection request and
response shapes go. `pat-core` still has **zero** in-repo dependencies, which is the property that
allows this edge at all.

**Does it acquire a new third-party dependency?** No. The client uses Spring Framework's
`RestClient`, which arrives through `spring-boot-starter-web`, already a non-optional dependency of
the module. Deliberately **not** `rest-client-spring-boot-starter`: `project-index.json` says its
`inRepoDependencies` are `audit-core` and `web-core-spring-boot-starter`, so the edge would not close
a cycle - but it would put a sizeable module onto the classpath of the one that sits nearest the
bottom of the reactor, for one POST. The cost of that is paid by every consumer of the platform.

**Does `pat-spring-boot-starter` acquire anything?** No. The introspection endpoint reuses
`PatVerifier`, `PatMetrics` and the problem pipeline it already has.

```
            pat-core  (0 in-repo deps)
             ▲      ▲
             │      │
  security-sb-starter   │        ← filter + introspection client (RestClient)
             ▲          │
             │          │
      pat-sb-starter ───┘        ← /introspect, reusing PatVerifier
```

### POM changes

**None.** Not the root `pom.xml`, not `ludwig-bom`, not `ludwig-service-parent`, not any module's.
Stated explicitly because it is what keeps the gate at the module-plus-dependents set rather than at
`mvn clean install`, and because `add-personal-access-tokens` learned the hard way that a BOM edit is
easy to need and easy to overlook - there, a new published module had to be added to it, which the
proposal had asserted would not be necessary.

### Enforcement - every rule twice

| Rule this change introduces | Mechanical check | Owner |
|---|---|---|
| One construction site of a credential-backed authentication, with two callers | `credentials.one-attenuation-path`, **narrowed to one type** | `architecture-rules` |
| The introspection response carries no secret, digest or key id | a test over the response record's components, as `CachedVerification` already has | the module |
| Every introspection failure answers `{"active": false}` identically | integration test comparing bodies byte for byte, as `UniformExchangeFailureIT` does | the module |
| The filter is off unless deliberately enabled | autoconfiguration condition + a context test asserting no filter is registered by default | the module |
| The third revocation window is computed and ceiling-checked | the existing fail-fast startup validation, extended | the module |
| A PAT may not authenticate the introspection call itself | the existing credential guard, applied to the endpoint | the module |

Rules that **cannot** be mechanised, recorded rather than dropped:

1. **Machine traffic must bypass the company gateway.** Not in this repository and not expressible in
   it. A startup warning is not even available, because a service cannot tell how it was reached. The
   only mitigation is the README and the fact that the failure is loud - an OIDC redirect in response
   to a `curl` is unmistakable.
2. **This change is meant to be deleted.** No check can assert that a deployment removed the filter
   once its edge gained PAT support. A deprecation note on the property is the whole mitigation, and
   it will be read by whoever is already looking at the property rather than by whoever should be.

## Risks / Trade-offs

- **The issuing service becomes a per-request dependency**, bounded by the introspection cache (~30s)
  rather than by an assertion lifetime (~5 min). → Accepted and quantified in D5; the bound is
  computed and ceiling-checked at startup like the other two. No fallback that keeps a revoked token
  working is offered, because that is worse than the outage.
- **The routing precondition is unresolved.** → Named in the proposal's Impact and first in the task
  list. Nothing in this change functions without it, and it is infrastructure rather than code. The
  honest position is that this change is inert until it is confirmed.
- **A second authentication path is a second thing to reason about** when diagnosing a 401. → The
  filter records its decisions through the same `SecurityMetrics` and audit sink as every other path,
  and the principal carries the same credential dimension - so a denial names the token either way.
- **Scaffolding tends to stay.** → The removal is one property and the design states what survives,
  which is most of it. That is the best available mitigation and it is not a guarantee.
- **`{"active": false}` for everything makes the introspection endpoint hard to debug.** → Same
  trade-off the exchange already makes, and the same answer: the reason is in the issuer's metrics and
  audit, where the defender can see it and a caller cannot.

## Migration Plan

1. **Confirm the routing.** A hostname, path prefix or ingress that reaches services without the
   session gateway. Until this exists, nothing below is testable end to end.
2. Ship `pat-core`'s introspection shapes and `pat-spring-boot-starter`'s endpoint. Inert: nothing
   calls it.
3. Ship the extracted `AttenuatedAuthentications` and the narrowed ArchUnit rule. No behaviour change
   - the JWT converter calls the new type and produces what it produced before, which is what its
   existing tests assert.
4. Ship the filter, off. Every service can take the upgrade with no effect.
5. Enable it in one service, with one token scoped to one read endpoint. Verify: the request
   authenticates, the authority is the intersection, a revoked token stops working within the computed
   window, and the audit trail names the token.
6. Widen.

Rollback: set the property to `false`. No redeployment of the issuing service, no schema change,
nothing to undo.

## Open Questions

1. **Is there already a route for machine traffic that bypasses the session gateway?** Asked and not
   yet answered. It does not change any requirement or any line of code here - it decides whether this
   change is immediately usable or waits on infrastructure.
2. **Should the introspection cache be shared (Redis) rather than per-replica?** Per-replica is
   assumed, because `CachePurpose.SECURITY` refuses the cluster-wide load lease and stale reads
   anyway, so the shared tier buys only a better hit ratio. A deployment with many small replicas and
   a hot token might prefer otherwise; it is a configuration change under
   `ludwig.cache.caches.<name>.tiers`, not a design change.
3. **Does the future MCP starter want this path or the exchange?** It wants whichever exists when it is
   written. Worth revisiting then rather than guessing now - and the answer may well be "both", since
   an MCP server reachable from outside the mesh and one reachable only from inside it are different
   deployments.
