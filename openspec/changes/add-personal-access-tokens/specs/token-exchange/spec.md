## ADDED Requirements

### Requirement: A PAT is exchanged for a short-lived assertion, and services never see the PAT

The issuer SHALL expose an RFC 8693 token-exchange endpoint that accepts a PAT as the subject token
and returns a short-lived JWT. No module in this repository other than `pat-spring-boot-starter`
SHALL accept a PAT as a request credential, and no service SHALL hold PAT secret material.

The platform's edge already exchanges an opaque long-lived credential - the browser session cookie -
for a short-lived JWT, which `PrincipalType.USER` documents. A PAT is an opaque long-lived
credential and goes through the same exchange. The consequence is that every service that already
verifies a JWT accepts PAT-backed callers with no filter, no table and no new principal type.

#### Scenario: A client presents a PAT to a service directly
- **WHEN** a request reaches any service with a `lpat_`-prefixed bearer credential, having bypassed
  the edge
- **THEN** it is not authenticated, because no service has a verifier for that format - the credential
  is rejected as an unparseable bearer token rather than partially handled

#### Scenario: A valid PAT is exchanged
- **WHEN** a live, unexpired, unrevoked PAT is presented to the exchange from a permitted source
- **THEN** the response carries a signed JWT whose `sub` is the PAT's owner and which carries the
  `ludwig_pat` claim holding the PAT id and its scope set

#### Scenario: The exchanged assertion is verified by a service
- **WHEN** the returned assertion reaches any service running `security-spring-boot-starter`
- **THEN** it is verified by the existing resource-server chain with no additional filter, and the
  principal's authorities are the owner's live authorities intersected with the claim's scopes

### Requirement: The assertion carries the attenuation, never the effective authority

The `ludwig_pat` claim SHALL carry the PAT identifier and its scope set. The assertion SHALL NOT
carry roles or permissions, and the issuer SHALL NOT resolve the owner's authorities in order to
mint it.

Two sources of truth for entitlement is the defect the platform's local role resolution exists to
prevent. If the issuer baked effective authority into a five-minute assertion, that assertion would
be five minutes of frozen privilege resolved from the issuer's view of the owner rather than each
service's.

#### Scenario: The assertion is inspected
- **WHEN** a returned assertion is decoded
- **THEN** it contains identity and the attenuation, and no role or permission claim

#### Scenario: The owner's roles change between exchange and use
- **WHEN** the owner's authorities change after an assertion was minted but while it is still valid
- **THEN** the next request using that assertion resolves the new authorities, because the service
  resolves them locally rather than reading them from the assertion

### Requirement: The claim's shape is defined once and shared by both sides

The `ludwig_pat` claim name and structure SHALL be defined in exactly one place, in `pat-core`, and
both the minting side and the verifying side SHALL read that definition rather than restating it.

The issuer and the verifier are in different modules and will be released independently. A claim
name written as a literal on both sides is a claim name that will diverge, and the failure mode is a
PAT that authenticates as its owner with no attenuation applied at all - a silent privilege
escalation rather than an error.

#### Scenario: The claim definition changes
- **WHEN** the claim name or structure is altered in `pat-core`
- **THEN** both the issuer and the verifier change with it, because neither holds its own copy

### Requirement: The exchange is audience-scoped, and the assertion names one service

The exchange SHALL accept the RFC 8693 `resource` or `audience` parameter, SHALL refuse a value the
presented PAT does not permit, and SHALL mint an assertion whose `aud` names exactly that one
audience. It SHALL NOT mint an assertion with no audience, and SHALL NOT mint one naming every
service the PAT permits.

`AudienceValidator` in `security-spring-boot-starter` states the reason in its own javadoc: in a mesh
where every service trusts the same provider, a token minted for one service is valid at every other,
so anything that can obtain a token for the least sensitive service can replay it against the most
sensitive. An assertion naming several audiences at once reintroduces that property in miniature.

#### Scenario: An exchange names a permitted audience
- **WHEN** the requested audience is one the PAT permits
- **THEN** the assertion's `aud` is exactly that audience, and no other

#### Scenario: An exchange names an audience the PAT does not permit
- **WHEN** the requested audience is outside the PAT's set
- **THEN** the exchange fails with the uniform failure response, indistinguishable from a wrong
  secret

#### Scenario: An exchange names no audience
- **WHEN** the request omits the parameter
- **THEN** the exchange fails. There is no default, because every available default is either "the
  PAT's whole set" or "everything", and both are the condition audience binding exists to prevent

#### Scenario: The edge caches assertions for a PAT used against two services
- **WHEN** one PAT is used against two services in its audience set
- **THEN** the edge holds two cached assertions, because the cache is keyed on the pair of secret
  digest and audience - a digest-only key would serve one service's assertion to another and every
  such request would then fail audience validation at the destination

### Requirement: The response declares its own cacheable lifetime

The exchange response SHALL state the lifetime for which it may be reused. The edge SHALL reuse a
cached assertion for a given PAT and audience for that lifetime, less a clock-skew margin, rather
than exchanging per request.

This is the requirement that makes the topology highly available. Without edge caching, every
service request becomes an issuer request by proxy, and the design degenerates into the
remote-check-per-request alternative that was rejected for exactly that reason. The response declares
the lifetime so the edge has no reason to invent one.

#### Scenario: Many requests are made with one PAT
- **WHEN** a client makes a sustained stream of requests with a single PAT, through a correctly
  configured edge
- **THEN** the number of exchanges is proportional to the elapsed time divided by the assertion
  lifetime, not to the number of requests

#### Scenario: The edge is not caching
- **WHEN** exchanges per distinct PAT rise in proportion to request volume
- **THEN** the condition is visible on the issuer's exchange-rate metric. This is explicitly **not**
  mechanically enforced - the edge configuration is not in this repository and no build here can see
  it - so the metric and an alert on it in the deployment are the substitute, and the gap is recorded
  rather than omitted

### Requirement: Every exchange failure returns one indistinguishable response

An unknown key id, a bad secret, a failed checksum, a revoked PAT, an expired PAT and a refused
source address SHALL all produce the same status, the same problem type and the same body. The reason
SHALL be available in metrics and audit, tagged, and SHALL NOT appear in the response.

Any distinguishable failure is an oracle. A response that says "unknown key id" tells an attacker
which half of the credential to keep guessing; a response that says "revoked" confirms that a
harvested token was once valid and identifies a target.

#### Scenario: A well-formed token with an unknown key id is presented
- **WHEN** the key id matches no record
- **THEN** the response is byte-for-byte the same as for a known key id with a wrong secret

#### Scenario: A revoked token is presented
- **WHEN** a revoked PAT is presented
- **THEN** the response is the same uniform failure, while a counter tagged as a revoked-token attempt
  is incremented and an audit event is recorded

#### Scenario: An operator investigates failures
- **WHEN** exchange failures are examined in metrics
- **THEN** the reasons are distinguishable there by tag, so the uniform response costs the defender
  nothing

### Requirement: The client sees one static header and never performs an exchange

A client holding a PAT SHALL authenticate by presenting it as an ordinary bearer credential on its
request to the service. It SHALL NOT be required to call the exchange, to track an assertion's
expiry, or to refresh anything.

This is the property that makes the design usable by automation, and it is the main reason this
topology was chosen over a client-side exchange. The cost of the alternative falls on every tool: a
`curl` in a runbook would need two requests and a JSON parse, and would then hold a credential valid
for minutes; every such tool reimplements expiry tracking and some get it wrong. A single static
`Authorization` header in a CI secret has none of that surface.

#### Scenario: An automation tool authenticates
- **WHEN** a client sends `Authorization: Bearer lpat_...` to a service through the edge
- **THEN** the request succeeds with the owner's live authority intersected with the PAT's scopes,
  and the client has made exactly one request and holds no short-lived credential

#### Scenario: The same PAT is used for months
- **WHEN** a PAT is stored once in a CI secret and used across many pipeline runs
- **THEN** no client-side change is ever required for assertion rotation, because the client never
  held an assertion

### Requirement: An exchange failure reaches the client as an actionable `401`

When the edge cannot obtain an assertion for a presented PAT, the client SHALL receive `401` with
`WWW-Authenticate: Bearer error="invalid_token"` and a problem document whose `type` identifies that
the credential was not accepted and which points at the PAT management location. The response SHALL
NOT disclose which cause applied.

The uniform exchange failure defined above is seen by the **edge**, not by the client - the client
called a service, not the exchange. Without this requirement the automation user gets a bare `401`
and cannot tell a revoked token from a typo, which is the difference between a one-minute fix and an
afternoon.

The two properties are compatible because of what each audience already knows: *that* the credential
failed is information the legitimate holder needs and an attacker already has, while *why* it failed
is information only the attacker gains from.

#### Scenario: An automation tool presents a revoked PAT
- **WHEN** the PAT has been revoked
- **THEN** the client receives the `401` with the management location, and nothing indicating
  revocation specifically rather than expiry, a bad secret or an audience refusal

#### Scenario: An automation tool presents a mistyped PAT
- **WHEN** the presented value fails its checksum
- **THEN** the client receives the same `401` with the same body as every other cause

#### Scenario: A client library needs to distinguish this from an authorization failure
- **WHEN** the response is inspected programmatically
- **THEN** the problem `type` distinguishes a credential that was not accepted from an authenticated
  caller who lacks an authority, so a tool can tell its user to reissue a token rather than to
  request a role

#### Scenario: The edge is configured
- **WHEN** an operator configures the edge's failure response
- **THEN** the required problem `type` and the management location are available as constants in
  `pat-core` and are documented verbatim in the module README, so the edge has a definition to
  configure against rather than one to invent. This is explicitly **not** mechanically checked - no
  build in this repository can observe what the edge returns

### Requirement: The exchange endpoint is rate limited per key and per source

The endpoint SHALL apply rate limits keyed on both the presented key id and the source address, and
SHALL reject the checksum before performing any database access.

This endpoint is the one place in the platform where an attacker can test a credential guess. It is
also on the hot path, which is why the verification is a digest and not a key-derivation function -
so the limit, not the CPU cost, is what bounds the attempt rate.

#### Scenario: Repeated failures for one key id
- **WHEN** failed exchanges for a single key id exceed the configured rate
- **THEN** further attempts for that key id are rejected without a database access, and the condition
  is audited

#### Scenario: Repeated failures from one source across many key ids
- **WHEN** a single source attempts many distinct key ids
- **THEN** the source limit applies, because a per-key limit alone does not bound an attacker
  enumerating key ids

#### Scenario: A malformed token arrives under load
- **WHEN** the checksum does not validate
- **THEN** the request is rejected with no database access at all, so a flood of garbage cannot
  convert into database load

### Requirement: The assertion is signed by a pluggable minter

The starter SHALL obtain the signed assertion from a replaceable minter interface and SHALL ship a
default implementation. It SHALL NOT implement discovery, key rotation endpoints, consent or any
other grant type.

Where the deployment already runs an OIDC provider, the provider owns signing keys and the services
already trust its issuer; a minter that delegates to it is strictly better than a second signing
identity. Where it does not, the default implementation is enough to run.

#### Scenario: A deployment supplies its own minter
- **WHEN** a minter bean is declared by the application
- **THEN** it is used instead of the default, and the exchange endpoint is otherwise unchanged

#### Scenario: The issuer or audience of the assertion is not configured
- **WHEN** the exchange is enabled without a configured issuer and audience
- **THEN** startup fails naming what is missing, because an assertion minted with no audience is one
  every service trusting the issuer will accept, which is the condition the existing
  audience-validation check already refuses to start over

### Requirement: The exchange does not accept a PAT as its caller identity

The exchange endpoint consumes a PAT as the subject token being exchanged. It SHALL NOT treat a PAT
as the authentication of the caller making the exchange request, and a PAT-derived assertion SHALL
NOT be accepted on it.

#### Scenario: A PAT-derived assertion is presented as the caller credential on the exchange
- **WHEN** the exchange request itself is authenticated with a PAT-derived assertion
- **THEN** it is refused, consistent with the rule that a PAT may not operate on PATs
