## Context

See `proposal.md` - Why. This section records only the existing facts that constrain the approach.

Four properties of `security-spring-boot-starter` decide most of this design, and none of them is
negotiable:

1. **One identity model.** `LudwigPrincipal` is what every authorization decision is made against,
   and `PrincipalType` has exactly three values because there are exactly three doors into a
   service. Its javadoc states that normalising at the edge "means nothing downstream needs to know
   which door the caller came through".
2. **Roles are never taken from the token.** The JWT carries `sub`, name and email;
   `AuthorityResolver` fills in roles from the service's own projection. The stated reasons are
   token size, revocation latency bounded by a cache TTL rather than a token lifetime, and that a
   stolen token cannot carry roles that were never granted.
3. **The module has no persistence dependency** - `querydsl-core` for predicate construction only.
   `identity-projection-spring-boot-starter` is the established way to add "a security concern that
   needs a table": it depends on `security-spring-boot-starter` and adds JPA, rather than the
   dependency going the other way.
4. **The filter chain order is deliberate and documented**, and the chain backs off entirely if the
   service declares its own. Adding a fifth filter to it would have to justify its position against
   a javadoc that explains all four current ones.

Two further platform facts matter:

- `CachePurpose.SECURITY` already encodes exactly the semantics a PAT cache needs: the TTL *is* the
  revocation window, a TTL above the configured ceiling **fails startup naming the cache**, and
  serving a stale value past expiry is **refused** - as is the cluster-wide load lease, because its
  losing replica serves stale. A PAT verification cache needs no new primitive and no new argument.
- The edge already performs an opaque-credential-to-JWT exchange for browser sessions. That is the
  mechanism this design reuses, not one it invents.

## Goals / Non-Goals

Proposal-level scope is in `proposal.md` - Non-goals. Design-level boundaries only:

**Goals:**

- The attenuation invariant is **structurally impossible to violate**, not merely documented: there
  is one code path that produces a PAT-credentialed principal and it cannot produce one whose
  authorities exceed the owner's live authorities, because the intersection is the only way it
  builds the set.
- The revocation window is a **single computed number**, logged at startup and refused when it
  exceeds a ceiling - not an emergent property of three independently configured TTLs that nobody
  has ever multiplied out.
- The issuer is **not on the per-request path** of any service, under any load, which is what makes
  the design highly available rather than merely distributed.
- The starter is **complete out of the box**: a deployment adds a dependency, a changelog include
  and a signing key, and has a production PAT subsystem with audit, metrics, i18n, problem details
  and a management API.

**Non-Goals:**

- Minting the assertion. The starter asks a `PatAssertionMinter` for a signed JWT and ships a
  default JOSE implementation; a deployment running Keycloak points it at the provider instead. The
  starter is not becoming an authorization server.
- Supporting a deployment with no edge. If a service is reachable directly by a client holding a
  PAT, the PAT never gets exchanged and the request is unauthenticated. That is a deliberate
  restriction of topology D and is recorded under Risks.
- Shipping a streaming transport, or an MCP server. D12 specifies what a long-lived connection owes
  the revocation guarantee, and this change ships the configuration property and its ceiling check so
  the knob exists before the first transport does. It does not ship a transport, and the enforcing
  test belongs to the change that does.
- Making a PAT usable without an exchange. The client-side exchange - where every automation tool
  calls the token endpoint itself and tracks expiry - is explicitly rejected, not merely unbuilt. It
  is the variant that would force OAuth logic into every `curl`, and avoiding it is most of the
  reason topology D was chosen. A single static `Authorization` header is the deliverable.

## Decisions

### D1. The PAT is exchanged for a JWT at the edge, not verified in each service

```
  CI / curl / notebook
        │  Authorization: Bearer lpat_<keyId>_<secret>_<crc>
        ▼
  ┌───────────────────────────────────────────────────────────────┐
  │ EDGE (Envoy)                                                  │
  │   prefix "lpat_" ?  ──yes──▶ cache[ sha256(pat) ] hit ?       │
  │                                 │ hit  ──▶ reuse JWT          │
  │                                 │ miss ──▶ exchange, then     │
  │                                 │          cache for          │
  │                                 │          expires_in - skew  │
  └───────────────────────────────────┬───────────────────────────┘
                                      │ POST /oauth2/token
                                      │ grant_type=...token-exchange
                                      ▼
  ┌───────────────────────────────────────────────────────────────┐
  │ identity-provider-service  (pat-spring-boot-starter)          │
  │   parse → checksum → keyId point read → constant-time compare │
  │   → not revoked, not expired, IP allowed                      │
  │   → mint JWT: sub=<owner>, ludwig_pat={id, scopes[]}, exp=5m  │
  └───────────────────────────────────┬───────────────────────────┘
                                      │ short-lived JWT
                                      ▼
  ┌───────────────────────────────────────────────────────────────┐
  │ ANY service - unchanged filter chain                          │
  │   ResourceServerAutoConfiguration → JwtPrincipalConverter     │
  │     owner authorities = AuthorityLookup.lookup(sub)   ← LIVE  │
  │     effective        = owner ∩ ludwig_pat.scopes       ← NEW  │
  │     LudwigPrincipal{ type=USER, credential=PAT(id), ... }     │
  └───────────────────────────────────────────────────────────────┘
```

Rejected alternatives:

- **Per-service PAT table and filter.** A user needs N tokens for N services, and a leaked token has
  N revocation surfaces. Revocation is the entire reason a PAT is preferable to a shared password;
  an architecture that makes it N-way partial defeats the purpose. Also adds a fifth filter to a
  chain whose ordering javadoc currently explains four.
- **Central store, remote check per request.** Correct revocation, unacceptable availability: every
  service acquires a hard synchronous dependency on the issuer for every authenticated request. The
  issuer's p99 becomes every service's p99 and its outage is a platform outage.
- **Central issuance, Kafka projection, local verification.** This one is genuinely viable and would
  reuse `identity-projection`'s machinery almost verbatim. Rejected because it replicates secret
  material - even as a hash - into every service's database, multiplying the blast radius of a
  database compromise by the number of services, and because it reintroduces the per-service table
  that topology D exists to avoid. Kept as the documented fallback if the edge cannot be changed.

### D2. The JWT carries the attenuation, never the effective authority

The exchanged assertion carries `sub` (the owner) and a `ludwig_pat` claim holding the PAT id and
its scope set. It does **not** carry roles or permissions.

This is the decision that keeps the change consistent with constraint 2 above. Each service
resolves the owner's authorities through the `AuthorityLookup` it already uses - with the caching,
metrics and null-handling that wrapper already applies - and then intersects. The consequences are
exactly the ones `LudwigPrincipal`'s javadoc claims for ordinary tokens, now extended to PATs:

- A role revoked from Alice stops working for her PATs within the authority cache TTL, with no PAT
  revocation and no issuer involvement.
- A PAT scope naming an authority Alice never held grants nothing, so a mis-scoped PAT is inert
  rather than dangerous.
- The assertion stays small and carries no entitlement worth stealing beyond Alice's identity -
  which a session-derived JWT already carries.

Alternative rejected: minting the effective authority into the assertion at exchange time. It would
save the per-service lookup, and it would make a 5-minute JWT a 5-minute window of frozen
privilege, resolved from the issuer's view of Alice's roles rather than each service's. Two sources
of truth for entitlement is the defect the platform's local-resolution design exists to prevent.

### D3. `LudwigAuthentication` gains a credential dimension - not `LudwigPrincipal`, and not a fourth `PrincipalType`

```java
// shape, not final signature
record Credential(CredentialKind kind, String id) { }
enum CredentialKind { DIRECT, PERSONAL_ACCESS_TOKEN }

// an OVERLOADED constructor - the existing one-arg form is untouched
new LudwigAuthentication(principal);
new LudwigAuthentication(principal, credential);
```

**Not a fourth `PrincipalType`.** A token-backed caller *is* Alice - same subject, same tenant, same door,
authority bounded below hers. Making it `PrincipalType.PAT` would be wrong in a way that quietly breaks
existing policy: every `isType(USER)` check and every `DataScopeProvider` keyed on `USER` would stop matching
Alice when she used a token, which is both a surprise and a fail-open/fail-closed coin toss depending on how
each check happens to be written.

**Not on `LudwigPrincipal` either, and this corrects an earlier version of this design.** The first draft put
a new record component on the principal and accepted that it was a breaking change. Implementation found that
the argument above applies one level further in than the draft followed it.

`LudwigPrincipal`'s own javadoc calls it *"the single identity model every authorization decision is made
against, whoever the caller is and however they authenticated"*. A credential is a property of **how this
particular request authenticated** - it is not part of who the caller is. Alice is the same principal whether
she presented a session-derived assertion or a token-derived one; what differs is the authentication event.
Putting a per-request transport fact on the identity model is the same category error as the fourth
`PrincipalType`, one level down.

`LudwigAuthentication` is already the right home. It is constructed per request, it already exists for all
three principal types alike, and `getCredentials()` already returns `null` deliberately so that no raw
credential material is held where it might reach a log - a `credential()` that describes the *kind* and the
*id* and carries no secret fits that reasoning rather than fighting it.

Three consequences, and the first is why this is worth correcting rather than noting:

- **The change has no breaking item at all.** An overloaded constructor is binary- and source-compatible.
  There is nothing for revapi to flag, nothing to accept with a justification, and no collision with the
  in-flight `add-api-compatibility-and-release-governance` change.
- **No call-site sweep.** A survey found **zero** canonical-constructor call sites for `LudwigPrincipal`
  (20 builder call sites, 9 `new LudwigAuthentication(...)` call sites), so the principal route's sweep was
  unnecessary anyway - but the principal route would still have changed a published signature for every
  consumer outside this repository.
- **Credential stays orthogonal to type**, which was the point of the original decision and is preserved: a
  `USER` may present a session-derived assertion or a token-derived one, and remains `USER` either way.

The accessor is `Optional`-shaped, so every existing authentication reports no credential and no existing
policy changes meaning.

### D4. The secret is `lpat_<keyId>_<secret>_<crc32c>`, and its hash is SHA-256, not bcrypt

Each element earns its place:

| Element | Why it is there |
|---|---|
| `lpat_` prefix | makes the credential **findable**. Secret scanners, `gitleaks` and a pre-receive hook can only match a known shape. A format nobody can grep for is one whose leaks surface during the incident, not before it. |
| `keyId` | makes verification an **indexed point read**. Without it, verification is "scan the table and hash-compare each row", which is how systems end up accidentally O(n) in a KDF. |
| `secret` | 256 bits from `SecureRandom`, base64url, never stored. |
| `crc32c` | rejects a mistyped or truncated token with **no database hit**, and lets a scanner avoid false positives. `java.util.zip.CRC32C`, no dependency. |

**The hash is a plain SHA-256 with a constant-time comparison, and this is deliberate.** Reaching
for bcrypt or Argon2 here is the intuitive move and it is wrong. Password KDFs exist to make
brute-forcing *low-entropy human-chosen* secrets expensive. A 256-bit value from `SecureRandom` has
no guessable distribution to attack - there is nothing for the work factor to protect. What a KDF
would reliably add is ~100ms of CPU to every exchange, on the one endpoint in the system that is
both a brute-force target and on the hot path. The threat the KDF addresses does not exist here;
the latency and the resulting denial-of-service surface do.

The checksum is **not** a security control and the design says so where the code says so, because
"it has a checksum" is a sentence that gets read as "it is tamper-proof" by the next reader.

### D5. The revocation window is computed, logged and ceiling-checked at startup

Three TTLs compose:

```
revocation window  =  assertion lifetime        (issuer: minutes)
                   +  edge exchange cache TTL   (edge: ≤ assertion lifetime)
                   +  service authority cache   (CachePurpose.SECURITY: seconds)
```

Nobody multiplies this out in production, so the starter does it at startup, logs the total, and
**refuses to start** when it exceeds `ludwig.pat.max-revocation-window`. This is the same mechanism
and the same reasoning as the existing audience check, whose javadoc says: *"A resource server that
skips the audience check accepts any token the issuer minted for any service, so this is worth
refusing to start over rather than warning about."* A PAT whose revocation takes an unbounded and
un-noticed amount of time is the same category of defect: it cannot be discovered by testing,
because everything works.

The edge's contribution is the term this repository cannot verify. The exchange response therefore
**declares** its own cacheable lifetime, so the edge has no reason to invent one, and the declared
value is what the startup computation assumes. See Risks.

### D6. A PAT may not mint, rotate or revoke a PAT

Without this, a leaked read-only PAT is a persistence mechanism: the attacker exchanges it, calls
the management API, mints a fresh token with wider scope, and revoking the original achieves
nothing. The management surface rejects any request whose principal carries a
`PERSONAL_ACCESS_TOKEN` credential, before authorization - a `403` with a distinct problem type, not
a `401`, because the caller is authenticated and this credential is simply not permitted here.

The same rule is why the exchange endpoint itself is unauthenticated-by-PAT: it consumes a PAT, it
does not accept one as its caller identity.

### D7. Rotation keeps the old secret valid for an overlap window

A rotation that invalidates the old secret immediately requires every consumer of the token to be
updated atomically, which no real deployment can do, so in practice people never rotate. `rotate`
mints a new secret and keeps the previous one accepted until `ludwig.pat.rotation-overlap`
elapses - a second `key_id`/hash pair on the same PAT row, not a second PAT, so the identity,
scopes and audit trail are continuous. The overlap is bounded by the same maximum-lifetime ceiling
and both secrets appear in `last_used_at` tracking, so an operator can see when the old one went
quiet and cut the overlap short.

### D8. `last_used_at` is written off the critical path and may be lost

Per-request writes to a single hot row are write amplification on the exchange path and a
contention point under load. The update is debounced (at most once per
`ludwig.pat.last-used-debounce`), applied asynchronously, and **best-effort**: a failure is logged
and metered and never fails the exchange. Losing a `last_used_at` update costs an operator some
precision in a dormancy report. Failing an exchange because a telemetry write failed costs a
production pipeline.

The genuine security signals - first use, use after dormancy, use from an unseen source - go to the
one `AuditSink` as typed events with `toAuditEvent()`, and are not debounced. Per-request `pat.used`
events are refused: the volume is per-request and the signal is not.

### D9. No third SQL carve-out

The claim path is `SELECT ... WHERE key_id = ?` on a unique index, followed by an application-side
constant-time comparison. It needs no `ON CONFLICT`, no `RETURNING`, and no set-based merge, so it
needs none of what justified the carve-outs in `ru.ludwigandreas.ingest.bulk` and
`ru.ludwigandreas.idempotency.sql`. The new starter therefore gets a `SqlConfinementTest` of the
inverse shape: it fails the build if SQL or JDBC appears **anywhere** in the module. The carve-outs
stay at two, and `specs/data-access/spec.md` asserts that as a scenario rather than relying on
nobody adding a third.

This is worth stating because the instinct for "claim a token atomically" is the idempotency
module's upsert, and the two problems look alike. They are not: idempotency *writes* to claim, PAT
verification only *reads*.

### D10. A PAT carries an audience set, and the exchange mints audience-scoped assertions

A PAT declares at issuance which services it may be presented to. The exchange accepts the RFC 8693
`resource`/`audience` parameter, refuses an audience the PAT does not permit, and mints an assertion
whose `aud` is that single audience. The edge's exchange cache is therefore keyed on
`(secret digest, audience)`, not on the digest alone.

`AudienceValidator` already in `security-spring-boot-starter` makes the argument this decision
exists to answer: *"In a mesh where every service trusts the same OIDC provider, a token minted for
service A is perfectly valid at service B - so anything that can obtain a token for the least
sensitive service can replay it against the most sensitive one. The audience claim is what makes a
token service-specific, and checking it is not optional."*

An earlier draft of this design left the exchanged assertion's `aud` unspecified, which would have
handed that validator exactly the token it exists to refuse - or, worse, a token with an audience
wide enough to pass everywhere.

The attenuation invariant means an unscoped audience is not privilege *escalation*: the PAT still
cannot exceed its owner. But for an owner holding broad roles, "bounded by Alice's authority" is not
a meaningful bound, and the common automation case is the dangerous one - a CI token for deployments
has no business being presentable at the billing service, whoever owns it. Scope answers *what may
be done*; audience answers *where*, and neither substitutes for the other.

The enforcement is free: the audience ends up in a standard `aud` claim, and every service already
validates it through `AudienceValidator` under the existing `require-audience` configuration. No new
verification code exists on the service side for this at all.

Alternative rejected: a single platform-wide audience, with per-service restriction left to scopes.
It collapses two independent dimensions into one, and it requires every service to agree on a scope
vocabulary before any service can be protected - which, per Open Question 2, does not exist.

### D11. The edge translates an exchange failure into a client-facing `401` that is actionable but not an oracle

The client calls a service, not the exchange. So the uniform exchange failure response defined for
the issuer is seen by the **edge**, never by `curl`. What the client sees is a separate contract, and
leaving it unstated is how an automation user ends up with a bare `401` and no idea whether their
token is revoked, expired, mis-scoped or simply mistyped.

The edge returns `401` with `WWW-Authenticate: Bearer error="invalid_token"` and a problem document
whose `type` identifies "this credential was not accepted" and points at the PAT management URL.

That is actionable - it tells a pipeline operator to go look at their tokens rather than at their
code - while disclosing nothing about which of the six causes applied, so the oracle property the
uniform response was built for survives. The distinction that makes both possible: *that* the
credential failed is information the legitimate holder needs and an attacker already has; *why* it
failed is information only the attacker gains from.

### D12. A long-lived connection re-derives authority or is closed

Every revocation guarantee in this design assumes authority is re-derived per request. A connection
authenticated once at open - SSE, a streaming RPC, a websocket, the transport a future MCP starter
would use - pins its attenuation for its lifetime, and a PAT revoked an hour in keeps working until
the client disconnects. The computed revocation window of D5 becomes false for exactly those
callers, which is worse than it being large, because it is still being logged as correct at startup.

A service holding a connection open past `ludwig.pat.revalidation-interval` SHALL re-derive the
caller's effective authority and SHALL close the connection when the assertion has expired, the PAT
has been revoked, or the intersection has narrowed. Re-deriving is cheap - it is the same
`AuthorityLookup` call every request already makes, against the same cache.

Deliberately not solved by lengthening the assertion: a longer assertion lifetime widens the window
for *every* caller to fix a problem that affects only streaming ones.

This design does not add a streaming transport, so nothing in this repository exercises the rule
today. It is specified now because the MCP starter is the named consumer, and a revocation
requirement retrofitted after a transport ships is a breaking change to that transport.

### Dependency-direction check

Written out as the question and the answer, per the design rules.

**Does `security-spring-boot-starter` already depend on `pat-core`, directly or transitively?**
`project-index.json` `inRepoDependents` for `pat-core` is `[pat-spring-boot-starter]` (the module is
new; this is the set after the change), which does not contain `security-spring-boot-starter`. And
`pat-core`'s `inRepoDependencies` is `[]` by design. So `security-spring-boot-starter → pat-core`
introduces no cycle.

**Does `pat-core` need anything from `security-spring-boot-starter`?** It would, if the intersection
lived in `pat-core` - `Authorities` is declared in the security starter. It does not: `pat-core`
holds the scope set as opaque strings and the intersection lives in the security starter, which is
the module that owns `Authorities` and `AuthorityLookup`. That is what keeps `pat-core` at zero
in-repo dependencies, for the same reason `cache-spring-boot-starter` has zero: it is depended on
from both sides of the seam, and `ModuleIndependenceTest` is the precedent for guarding that.

**Does `security-spring-boot-starter` already depend on `pat-spring-boot-starter`?** No, and it must
not - that is the edge that would create the cycle. The direction is
`pat-spring-boot-starter → security-spring-boot-starter`, matching
`identity-projection-spring-boot-starter → security-spring-boot-starter` exactly.

```
                        pat-core  (0 in-repo deps)
                         ▲      ▲
                         │      │
  security-spring-boot-starter  │        audit-core
          ▲         ▲           │          ▲
          │         │           │          │
          │    cache-sb-starter │          │
          │                     │          │
     pat-spring-boot-starter ───┴──────────┘
          │
          └──▶ db-core, web-core-spring-boot-starter
```

### POM changes

- **Root `pom.xml`**: two `<module>` entries. Both new modules are libraries, so both are parented
  by the reactor root `common` with `<relativePath>../../pom.xml</relativePath>` - without which
  Maven resolves a stale `common` from `~/.m2` and does not fail.
- **`ludwig-bom`**: **no change.** Enumerated in `proposal.md` - Impact. This is load-bearing for the
  gate: a `ludwig-bom` change has no narrower gate than `mvn clean install`.
- **`ludwig-service-parent`**: no change. Neither new module is a service.

### Enforcement - every rule twice

| Rule the change introduces | Mechanical check | Owner |
|---|---|---|
| No second PAT store, SPI or token format | `RuleGroup.CREDENTIALS` | `architecture-rules` |
| Only `pat-core`'s token package and the issuance service may call `PatSecret.reveal()` | `RuleGroup.CREDENTIALS` | `architecture-rules` |
| Only the intersecting converter may construct a PAT-credentialed principal | `RuleGroup.CREDENTIALS` | `architecture-rules` |
| `PatSecret` never reaches an SLF4J logger or an audit attribute | `RuleGroup.CREDENTIALS`, on the typed parameter | `architecture-rules` |
| No SQL or JDBC anywhere in `pat-spring-boot-starter` | `SqlConfinementTest` (inverse form) | the module |
| No weak digest algorithm named at a PAT hashing call site | one id'd `RegexpSinglelineJava` | `checkstyle-rules` |
| Revocation window within the configured ceiling | fail-fast startup validation | the module |
| Mandatory expiry and maximum lifetime | validation at issuance + rejected-at-startup config | the module |
| A PAT may not reach the PAT management surface | request rejection + integration test | the module |
| A PAT declares a non-empty audience set; the exchange refuses an audience it does not permit | validation at issuance and at exchange + integration test | the module |
| An assertion is only accepted by the service it names | existing `AudienceValidator` under `require-audience` — **no new code** | existing |
| Both locale bundles carry the same key set | existing i18n parity check | existing |

Rules that **cannot** be mechanised, with the reason, recorded here rather than dropped so the list
is available as the backlog it is:

1. **The edge must cache the exchange response for the lifetime it declares.** The edge
   configuration is not in this repository and no build here can see it. Partially mitigated: the
   response declares its lifetime so the edge need not choose one, and a metric on exchange rate per
   distinct PAT makes a non-caching edge visible as a step change in traffic rather than as nothing.
   An alert on that metric is the closest available substitute for a check, and it belongs to the
   deployment, not the build.
2. **SHA-256 rather than a password KDF is correct only because the secret is machine-generated with
   full entropy.** No check can assert that the reasoning still holds if the generator is ever
   changed. A comment at the point of the hashing states the dependency explicitly, which is this
   repository's convention for a limit analysis cannot express.
3. **The secret is displayed exactly once.** The starter returns it in the issuance response and
   never again, and there is no code path that can re-read it - but whether the *consuming UI* shows
   it once or logs it to a browser console is outside any build here.
4. **The checksum is not a security control.** A comment, not a check: there is nothing to detect,
   only a misreading to pre-empt.
5. **The edge's client-facing `401` contract** (D11). Same reason as 1 - the edge is not in this
   repository. The problem `type` and the management URL it points at are defined as constants in
   `pat-core` so the edge has a definition to configure against rather than one to invent, and the
   module README carries the required edge response verbatim. That is the whole mitigation; there is
   no build here that can observe what the edge actually returns.
6. **A long-lived connection re-derives authority on an interval** (D12). Nothing in this repository
   holds a connection open, so there is no code for a rule to fire on - an ArchUnit rule written now
   would pass vacuously and keep passing after the rule had been broken elsewhere, which is worse
   than no rule because it reads as coverage. The requirement is specified, the interval property and
   its ceiling check ship with this change so the knob exists before the transport does, and the
   obligation to add the enforcing test is recorded as a task **on the MCP starter change**, which is
   the first change that will have a transport to enforce it against. This is a deliberately deferred
   check, not an absent one, and it is the row most likely to be forgotten.

## Risks / Trade-offs

- **The issuer becomes a single point of failure for first use of any PAT.** → The exchange is
  stateless and horizontally scalable; its only shared state is one indexed read and a
  `CachePurpose.SECURITY` cache. Edge caching keeps steady-state traffic proportional to *distinct
  PATs per assertion lifetime*, not to requests. Blast radius is stated plainly rather than papered
  over: if the issuer and every edge cache are simultaneously cold, PATs do not work. No fallback
  that weakens revocation is offered, because a fallback that keeps a revoked token working is worse
  than the outage.
- **A deployment with no edge gets no PAT support.** → Documented as a precondition in the module
  README, surfaced as a startup warning when the issuer is configured but no exchange audience is,
  and the Kafka-projection fallback (D1, third alternative) is written down so it is a design
  decision available later rather than a rewrite.
- **The exchange endpoint is the one brute-force target in the system.** → Checksum rejection before
  any database access; per-`keyId` and per-source rate limiting; a single uniform failure response
  for unknown key id, bad secret, revoked, expired and IP-refused, so none of them is an oracle; a
  counter on failed exchanges that is a security signal and should alert, distinguished in metrics
  by a reason tag that is *not* in the HTTP response.
- **The credential dimension could have been a breaking change and is not.** → Recorded here rather
  than dropped, because the first draft of D3 chose the breaking option and it took writing the code
  to notice that the design's own argument ruled it out. The lesson generalises: a per-request fact
  does not belong on an identity model, and "add a component to the record" is the path of least
  resistance to exactly that mistake.
- **Live intersection means a PAT can stop working without being revoked.** → This is the intended
  behaviour and is the whole security argument, but it will read as a bug to whoever is paged. The
  denial audit event names *both* the PAT and the authority that was absent, so the first
  investigation ends at the demotion rather than at the token.
- **An automation client that cannot traverse the edge gets no PAT support.** → The common case is a
  cron job or sidecar inside the mesh curling a service directly. This is a routing decision rather
  than a code one, and the answer is to route internal automation through the edge like anything
  else - but it is stated as a precondition in the README rather than left to be discovered, and the
  Kafka-projection fallback of D1 remains the escape hatch if some caller genuinely cannot.
- **Audience binding adds a dimension a client can get wrong.** → A PAT presented at a service
  outside its audience set fails as an ordinary `401` rather than as something that names the
  audience mismatch, because naming it would tell an attacker which services a harvested token is
  good for. The issuance response and the management API both list the PAT's audiences, so the
  information is available to the legitimate holder where it costs nothing.
- **MCP over stdio is out of reach and should not be made to fit.** → A stdio transport has no HTTP,
  no edge and no exchange; trust there is process-local. A future MCP starter should use PATs for its
  HTTP transports and not pretend the stdio case is the same problem. Named here so that the MCP
  change does not inherit an assumption this one never made.
- **Two modules where one would do.** → `pat-core` exists so that `security-spring-boot-starter` can
  read the `ludwig_pat` claim without acquiring a JPA dependency. Merging them would put a table in
  the module that deliberately has no persistence, which is the constraint that shaped the whole
  design.

## Migration Plan

Nothing exists to migrate; the risk is ordering, not data.

1. Ship `pat-core` and the `security-spring-boot-starter` change first. Services that upgrade
   acquire the ability to *understand* a `ludwig_pat` claim while nothing can yet mint one. Safe in
   isolation and independently releasable.
2. Ship `pat-spring-boot-starter`; `identity-provider-service` adopts it, runs the changelog, and
   configures a signing key. The management API and the exchange endpoint are live; no edge sends
   anything to them yet.
3. Enable the edge rule for one route, with the exchange cache keyed on `(secret digest, audience)`
   and the credential-rejected `401` configured. Verify with a PAT scoped to a read endpoint and
   audienced to that one service:
   - the metric for distinct-PAT exchanges stays flat while request volume rises - the observable
     proof that the edge is actually caching, which is the one thing no build here can check;
   - the same PAT presented at a second service fails, which is the observable proof that audience
     binding is in effect rather than merely configured;
   - a revoked PAT produces the `401` with the management location and no cause.
4. Widen to all routes. Announce the `lpat_` format to the secret scanners before the first token is
   issued, not after.

Rollback: remove the edge rule. PATs stop being accepted platform-wide within one assertion
lifetime, and no service needs redeploying. The management API can stay up, or be closed by
property, without affecting any service. This is a property of D1 worth naming - in a per-service
topology, rollback is 29 redeployments.

## Open Questions

Deferrable without changing the specs, the approach, or the task breakdown:

1. **Does `identity-provider-service` already have a JOSE signing key and a JWKS endpoint this can
   reuse, or does the default minter need to own a key?** Either way the `PatAssertionMinter` SPI
   and its default implementation are the same shape; this decides only which one the deployment
   wires. If the provider is Keycloak, a delegating minter is a later, additive implementation of
   the same interface.
2. **Is there an existing registry of permission strings** (`order:read`) that PAT scopes should be
   validated against at issuance? `LudwigPrincipal.permissions` is a `Set<String>` with no
   vocabulary attached anywhere in this repository. Without a registry, issuance validates a scope
   only against the owner's *current* authorities, which is a good fail-fast and not a closed
   vocabulary. A registry would let the management UI offer a picker; it changes no requirement
   here, because the enforcement is the use-time intersection either way.
3. **Per-PAT IP allowlisting granularity** - CIDR list per PAT is specified; whether the deployment
   also wants an org-wide default allowlist is a configuration addition that no requirement depends
   on.
