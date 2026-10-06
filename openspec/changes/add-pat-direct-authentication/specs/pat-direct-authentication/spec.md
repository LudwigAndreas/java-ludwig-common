## ADDED Requirements

### Requirement: A service may authenticate an `lpat_` credential itself, and does not by default

A service running `security-spring-boot-starter` SHALL be able to authenticate a bearer credential in
the `lpat_` format by introspecting it against the issuing service. This SHALL be off unless
`ludwig.security.pat.filter.enabled` is set, and SHALL remain off by default.

Off by default for two reasons. Adding a starter must never silently open a second authentication path;
and a deployment whose edge can perform the token exchange should use the exchange, which has a longer
revocation window and does not make the issuing service a per-request dependency.

#### Scenario: A PAT is presented and the filter is enabled
- **WHEN** a request arrives with `Authorization: Bearer lpat_...` and the filter is enabled
- **THEN** the token is introspected, and on success the request is authenticated as the token's owner
  with the owner's live authorities intersected with the token's scopes

#### Scenario: A PAT is presented and the filter is not enabled
- **THEN** nothing in the service recognises the format, the credential is rejected as an unparseable
  bearer token, and the request is unauthenticated - which is the behaviour the `token-exchange`
  capability describes for a deployment relying on the edge

#### Scenario: An ordinary company-issued JWT arrives while the filter is enabled
- **WHEN** a request carries a session-derived JWT rather than an `lpat_` credential
- **THEN** the filter does not act on it and the bearer-token filter authenticates it exactly as it
  does today. Enabling this path must not change the ordinary one

#### Scenario: Both a PAT and a JWT are somehow presented
- **WHEN** a request carries an `lpat_` credential and something has already authenticated it
- **THEN** the filter leaves the existing authentication alone, for the same reason the mTLS filter
  does: a filter that overwrote an established identity would make the order of the chain a security
  decision rather than a plumbing one

### Requirement: The filter runs after identity-header stripping, and the resource server declines the credential

The filter SHALL be placed immediately after the identity-header-stripping filter, so nothing it reads
can be a client-supplied identity header. The chain's ordering javadoc requires a fifth filter to
justify its place, and that is the justification.

The resource server SHALL be configured, **only when this filter is enabled**, with a bearer-token
resolver that reports no token for an `lpat_`-prefixed credential.

That resolver, not the filter's position, is what makes this path reachable. This requirement was
written as "before the bearer-token filter" by analogy with the mTLS filter and that was wrong; the
test asserting against the registered chain is what found it. Two facts:

1. Spring Security orders `BearerTokenAuthenticationFilter` **ahead of** `BasicAuthenticationFilter`,
   so `addFilterBefore(..., BasicAuthenticationFilter)` - the position every filter this module adds
   uses, including the stripping filter - places a filter *after* the bearer filter.
2. `BearerTokenAuthenticationFilter` does not check for an existing authentication. It resolves a
   token, authenticates unconditionally, and on failure commences the entry point and returns without
   continuing the chain. So moving the PAT filter earlier would not have worked either: the bearer
   filter would still have handed the `lpat_` credential to the JWT decoder and answered `401`.

The analogy failed because an mTLS request carries no `Authorization` header at all, so the resolver
finds nothing and the bearer filter passes it through. A PAT request carries one.

Declining to offer the credential to the JWT decoder is also correct independently of ordering: a
decode failure is logged, metered and sometimes stack-traced, and the token is its input.

#### Scenario: A forged identity header accompanies a PAT
- **WHEN** a request carries both an `lpat_` credential and a client-supplied identity header
- **THEN** the header has already been stripped before the filter runs, so the authenticated identity
  comes from the token alone

#### Scenario: The chain order is inspected
- **THEN** the filter is registered after the stripping filter and before the authorization filter,
  asserted against `SecurityFilterChain.getFilters()` rather than by reading the configuration - the
  ordering is a fact about Spring's filter registration and not about this module's intent

#### Scenario: An `lpat_` credential reaches the resource server's token resolver
- **WHEN** the filter is enabled and a request carries `Authorization: Bearer lpat_...`
- **THEN** the resolver reports no bearer token, the bearer filter passes the request through, and the
  PAT filter authenticates it

#### Scenario: The filter is not enabled
- **THEN** bearer tokens resolve exactly as Spring Security resolves them by default

### Requirement: The introspection endpoint is reachable by the calling service

The introspection path SHALL be reachable without the caller presenting a credential of its own, and the
issuing service SHALL warn at startup when the endpoint is mounted and the path is not in
`ludwig.security.public-paths`.

A service authenticating a token itself calls introspection with no credential of its own: the only
credential involved is the token being asked about, which is the subject of the question rather than the
caller's identity. Behind `anyRequest().authenticated()` the call receives a `401`, introspection fails
closed, and every personal access token is refused.

This is not a weakening. The exchange endpoint is already public on exactly this reasoning and the two must
not disagree: a caller who could learn something from introspection can learn strictly more from the
exchange, which takes the same credential, performs the same verification and returns a usable assertion.
Both require the full secret, so neither is an enumeration oracle, and the rate limiter applies to both.
"Public" means the application does not authenticate the caller; the endpoint is expected to be reachable
only inside the mesh, which is a deployment control no build can assert. `PatCredentialGuard` still refuses
a caller that *is* authenticated by a personal access token.

A warning rather than a startup failure, for the reason the exchange's own check gives: this module cannot
tell whether an application has published the path through its own `SecurityFilterChain`.

#### Scenario: The endpoint is mounted and the path is not public
- **THEN** startup logs a warning naming the path and the configured public paths, and explaining that the
  caller carries no credential of its own

#### Scenario: The endpoint is disabled
- **THEN** nothing is warned about, because no caller needs to reach it

### Requirement: Introspection answers with facts and never with credential material

The issuing service SHALL expose an RFC 7662-shaped introspection endpoint. A successful response
SHALL carry the token's id, its owner's subject, its scopes, its audiences and its expiry, under the
RFC's own field names where it defines one. It SHALL NOT carry a secret, a digest, or a key id.

The token id has no RFC field name and is carried as `patId`. `pat-core` has no Jackson - that is what
lets the security starter depend on it - so a record component name *is* the wire name, and spelling
this one `pat_id` would have meant a component named `pat_id` and a Checkstyle naming suppression spent
on the appearance of a field no standard defines.

The key id is excluded for the reason it is excluded from the claim and from every read endpoint: it is
secret-adjacent lookup material that changes on rotation, so it is useless to a caller naming a token
and would quietly become the identifier somebody built an integration on. The token id survives a
rotation.

#### Scenario: A live token is introspected
- **WHEN** a live, unexpired, unrevoked token is introspected
- **THEN** the response reports it active and names its id, owner, scopes, audiences and expiry

#### Scenario: The response shape is inspected for credential material
- **WHEN** the response type's components are enumerated
- **THEN** none of them is a secret, a digest or a key id, checked by a test over the type rather than
  by reading it - a field added later is then covered on the day it is written

### Requirement: Every introspection failure answers identically

An unknown key id, a bad secret, a malformed token, a revoked token, an expired token and a refused
source SHALL all produce the same response: `active` false, with nothing distinguishing the cause. The
cause SHALL be available in the issuer's metrics and audit, tagged.

The same no-oracle property the token exchange already has, and for the same reason: anything that
distinguishes one cause from another tells an attacker testing a credential guess which half of it to
keep working on, or confirms that a harvested token was once real.

#### Scenario: Six different causes are compared
- **WHEN** each of the six failures above is introspected
- **THEN** the six responses are byte-for-byte identical

#### Scenario: An operator investigates introspection failures
- **THEN** each cause is distinguishable in the issuer's metrics by tag, so the uniform response costs
  the defender nothing

#### Scenario: A revoked token is introspected
- **THEN** the response is the uniform inactive one, and the issuer records the revoked-token-presented
  audit event and increments the counter that should alert - presenting a revoked credential means
  something still holds it

### Requirement: There remains exactly one table and one revocation point

Verification SHALL read the one token table in the issuing service. No service enabling this filter
SHALL hold a copy of the table or any secret material.

This preserves the objection that made the shipped design reject per-service verification: N tables
means N revocation surfaces, and a leaked token revoked in one of N places is the failure a personal
access token exists to prevent. Introspection against one table keeps one revocation point while still
allowing a service to authenticate the credential itself.

#### Scenario: A token is revoked
- **WHEN** an operator revokes a token at the issuing service
- **THEN** it stops authenticating at every service within the computed revocation window, with no
  action at any of them

#### Scenario: A service's own storage is examined
- **THEN** it holds no token row, no digest and no key id

### Requirement: The introspection call authenticates as the calling service, never as the token

The client SHALL authenticate to the introspection endpoint with the calling service's own identity.
It SHALL NOT present the personal access token as its own credential, and the endpoint SHALL refuse a
request authenticated by one.

The same rule that keeps a token off the token management surface: a credential that can operate on
credentials makes revoking the original pointless.

#### Scenario: The introspection call is inspected
- **THEN** it carries the service's own identity and does not relay the token as a caller credential

#### Scenario: A token-derived authentication reaches the introspection endpoint
- **THEN** it is refused, by the same credential guard that protects the management surface

### Requirement: A third revocation-window composition is computed and bounded

Startup SHALL compute, log and ceiling-check a third composition for callers authenticated by this
filter: the introspection cache TTL plus the service authority cache TTL. The largest of the three
compositions SHALL be the one checked against `ludwig.security.pat.max-revocation-window`.

Computing only the two existing paths would log a correct-looking number while being false for exactly
the callers this capability creates.

#### Scenario: A deployment enables the filter
- **THEN** three compositions are logged at startup, so the number an operator needs during an
  incident exists in the record of every deployment

#### Scenario: The introspection cache TTL would breach the ceiling
- **THEN** startup fails, naming which path exceeded it and every contributing value

### Requirement: The introspection cache declares the security purpose

The introspection result cache SHALL be declared as a `CacheDefinition` with
`CachePurpose.SECURITY` and resolved from `LudwigCacheRegistry`.

The TTL *is* the revocation window for this path, which is precisely what that purpose means. It also
buys the startup ceiling and the refusal to serve stale - and `PERFORMANCE` would quietly remove both
while reading as a throughput improvement.

#### Scenario: The cache TTL is configured above the security ceiling
- **THEN** startup fails naming the cache, by the existing behaviour of the cache primitive

#### Scenario: An entry expires
- **THEN** it is not served stale, and the next request introspects again

### Requirement: The issuing service becomes a per-request dependency, bounded by the cache

When the issuing service is unreachable, tokens SHALL stop authenticating within the introspection
cache TTL. No fallback that admits an unverified token SHALL exist.

Stated as a requirement rather than left in a design note because it is the cost of this route and an
operator needs it written down: the edge-exchange path keeps working for an assertion lifetime when the
issuer is down, and this one does not.

#### Scenario: The issuing service is unreachable and an entry is cached
- **THEN** the request authenticates from the cache until the entry expires

#### Scenario: The issuing service is unreachable and nothing is cached
- **THEN** the request is not authenticated. There is deliberately no degraded mode: a fallback that
  accepted an unverified credential would keep a revoked token working, which is worse than the outage

### Requirement: Enabling this path is reversible by one property

Disabling `ludwig.security.pat.filter.enabled` SHALL restore the behaviour of a deployment that relies
on the edge exchange, with no other change, no schema change and no redeployment of the issuing
service.

This capability is scaffolding for a platform whose edge cannot yet exchange a token. When the edge
gains that ability, the exchange endpoint and the `ludwig_pat` claim reader - both already shipped and
tested - are what carry the traffic.

#### Scenario: The property is turned off
- **THEN** the filter is not registered, an `lpat_` credential is no longer recognised by the service,
  and a token-backed caller arriving as an exchanged assertion is authenticated exactly as the
  `personal-access-token` capability already specifies

#### Scenario: What survives the removal is enumerated
- **THEN** the token table, the management API, rotation, revocation, retention, the audit events, the
  attenuation and the whole of `pat-core` are unaffected; what is deleted is one filter, one client,
  one endpoint and one property
