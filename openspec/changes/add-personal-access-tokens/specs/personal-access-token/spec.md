## ADDED Requirements

### Requirement: A PAT attenuates its owner's live authority and never grants authority

The effective authority of a PAT-backed request SHALL be the intersection of (a) the owner's
authorities as resolved at request time by the service's own `AuthorityLookup` and (b) the scope set
carried in the PAT's `ludwig_pat` claim. It SHALL NOT be a union, SHALL NOT be a snapshot taken at
issuance, and SHALL NOT be read from the token.

This is the requirement the whole capability exists to hold. `LudwigPrincipal` already states that
the provider "issues identity, not entitlement", so that a revoked role takes effect within a cache
TTL rather than a token lifetime. A PAT carrying frozen roles would be a token-carried role with a
90-day lifetime - the exact construct that reasoning refuses.

#### Scenario: The owner is demoted while a PAT is live
- **WHEN** a PAT scoped `ROLE_ADMIN` is used after its owner has had `ROLE_ADMIN` removed from the
  identity projection, and no PAT revocation has occurred
- **THEN** the resolved principal holds neither `ROLE_ADMIN` nor any other authority the owner no
  longer has, within the authority cache's TTL and with no call to the issuer

#### Scenario: A PAT is scoped to an authority its owner never held
- **WHEN** a PAT is issued with a scope naming an authority absent from the owner's authorities, and
  is then used
- **THEN** the request resolves to a principal without that authority and is refused by the
  endpoint's own policy - the PAT is inert rather than privileged, because a filter applied to an
  absent element yields the empty set

#### Scenario: The owner gains an authority after the PAT was issued
- **WHEN** the owner is granted an authority that is also named in a live PAT's scope set
- **THEN** that authority becomes effective for the PAT without the PAT being reissued, because the
  owner side of the intersection is read live

#### Scenario: A PAT's scope set is empty
- **WHEN** issuance is requested with no scopes
- **THEN** issuance is refused with a `400` and a localized problem document, because a PAT that
  attenuates to nothing is indistinguishable from a leaked credential that happens to be harmless
  today and is a configuration mistake rather than a valid state

### Requirement: There is exactly one code path that builds a PAT-credentialed authentication

The intersection SHALL be performed in exactly one place, and no other code SHALL be able to
construct a `LudwigAuthentication` carrying a personal-access-token credential.

A documented invariant that any filter, test fixture or future converter can bypass is not an
invariant. Making the construction path singular is what turns the previous requirement from a
promise into a property.

#### Scenario: A second converter tries to build a PAT-credentialed authentication
- **WHEN** a class outside the designated converter constructs a `LudwigAuthentication` whose
  credential kind is the personal-access-token kind
- **THEN** the `architecture-rules` build fails, naming the class and the rule

### Requirement: A PAT is a credential dimension on the authentication, not on the identity

`PrincipalType` SHALL keep exactly its three existing values. A PAT-backed caller SHALL resolve to
the same `PrincipalType` as the identity it attenuates.

The fact that a PAT was presented SHALL be carried on the **per-request authentication** rather than
on the principal, and SHALL be added without changing the shape of any existing type.

Two separate claims, and the second is the one this change nearly got wrong. `LudwigPrincipal` is the
identity model - who the caller is. A credential is a property of *how this request authenticated*,
which is not part of who they are: the same person is the same principal whether they presented a
session-derived assertion or a token-derived one. Putting a per-request transport fact on the identity
model is the same category error as a fourth `PrincipalType`, one level further in.

#### Scenario: Existing type-based policy is evaluated for a PAT-backed caller
- **WHEN** a user authenticates with a PAT and an existing check tests `isType(USER)` or a
  `DataScopeProvider` keyed on `USER` is consulted
- **THEN** the check matches exactly as it does for the same user's session-derived token, because
  the door the caller came through has not changed

#### Scenario: An authentication is built with no PAT involved
- **WHEN** any authentication is built from a session-derived JWT, a client certificate or a system
  template, through the constructor that existed before this change
- **THEN** it reports no credential, and no existing behaviour changes

#### Scenario: An existing consumer compiles and links unchanged
- **WHEN** code written against the previous release constructs an authentication or a principal
- **THEN** it compiles and links without modification, because the credential arrives as an
  overloaded constructor rather than as a change to an existing signature. A new component on the
  principal's canonical constructor would have broken every consumer outside this repository to carry
  a fact that does not belong on an identity

### Requirement: A PAT has exactly one owner and a mandatory bounded expiry

Every PAT SHALL reference exactly one subject as its owner, SHALL carry an expiry, and that expiry
SHALL NOT exceed `ludwig.pat.max-lifetime`. A request to issue a PAT with no expiry SHALL be refused
unless the deployment has explicitly configured non-expiring tokens to be permitted.

#### Scenario: Issuance requests a lifetime beyond the ceiling
- **WHEN** issuance requests an expiry further out than `ludwig.pat.max-lifetime`
- **THEN** the request is refused with a `400` whose problem document names the configured ceiling,
  rather than being silently clamped - a caller who believes they hold a one-year token and holds a
  90-day one discovers it when the pipeline breaks

#### Scenario: Issuance requests no expiry on a default deployment
- **WHEN** issuance omits an expiry and `ludwig.pat.allow-non-expiring` is at its default
- **THEN** the request is refused, because a credential with no expiry is a credential that outlives
  every process that knows it exists

#### Scenario: An expired PAT is presented
- **WHEN** a PAT whose expiry has passed is presented for exchange
- **THEN** the exchange fails with the uniform failure response defined by the `token-exchange`
  capability, and the row is not deleted - the record is retained for audit with its secret hash
  cleared

### Requirement: A PAT declares which services it may be presented to

Every PAT SHALL carry a non-empty set of permitted audiences, chosen at issuance. An exchange SHALL
refuse an audience the PAT does not permit, and the minted assertion's `aud` SHALL name exactly the
single requested audience.

Scope answers *what* may be done; audience answers *where*. Neither substitutes for the other, and
collapsing them is how a CI token issued for deployments becomes presentable at the billing service.
The attenuation invariant means that is not privilege escalation - the PAT still cannot exceed its
owner - but for an owner holding broad roles, "bounded by the owner's authority" is not a meaningful
bound, and the automation case is the one where it matters most.

The service-side enforcement already exists and requires no new code: `AudienceValidator` under the
existing `require-audience` configuration refuses an assertion that does not name the service, and
its own javadoc states why that check is not optional.

#### Scenario: A PAT is presented at a service outside its audience set
- **WHEN** an assertion is requested for an audience the PAT does not permit
- **THEN** the exchange fails with the uniform failure response, and the response does not name the
  audience mismatch - which services a harvested token is good for is exactly what an attacker wants
  to learn

#### Scenario: An assertion reaches the wrong service
- **WHEN** an assertion minted for one audience is replayed against a different service
- **THEN** that service rejects it through the existing `AudienceValidator`, by the same mechanism
  that already protects session-derived tokens

#### Scenario: Issuance requests no audience
- **WHEN** issuance omits the audience set
- **THEN** it is refused with a `400`, because a PAT valid everywhere is the condition audience
  binding exists to prevent and defaulting to it would make the requirement decorative

#### Scenario: A legitimate holder needs to know where their PAT works
- **WHEN** the issuance response or the management API is read
- **THEN** the PAT's permitted audiences are listed, so the information withheld from an attacker at
  the point of failure is freely available to the owner

### Requirement: A long-lived connection re-derives authority or is closed

A service holding a connection open longer than `ludwig.pat.revalidation-interval` SHALL re-derive
the caller's effective authority at that interval, and SHALL close the connection when the assertion
has expired, the PAT has been revoked, or the intersection has narrowed.

Every revocation guarantee in this capability assumes authority is re-derived per request. A
connection authenticated once at open - a server-sent-event stream, a streaming RPC, a websocket -
pins its attenuation for its entire lifetime, so a PAT revoked an hour into an eight-hour stream
keeps working. That is worse than a large revocation window, because the computed window is still
being logged as correct at startup while being false for those callers.

Re-derivation is cheap: it is the same `AuthorityLookup` call every ordinary request already makes,
against the same cache.

This change ships no streaming transport, so nothing here exercises the rule. The property and its
ceiling check ship now so the knob exists before the first transport does, and the enforcing test is
an obligation on the change that introduces one - recorded in the `enforcement-triad` delta as a
deliberately deferred check rather than an absent one.

#### Scenario: A PAT is revoked while a stream is open
- **WHEN** a PAT backing an open long-lived connection is revoked
- **THEN** the connection is closed no later than `ludwig.pat.revalidation-interval` after the
  revocation, rather than persisting until the client disconnects

#### Scenario: The owner's authority narrows mid-stream
- **WHEN** the owner loses an authority the stream's operation depends on
- **THEN** the next re-derivation observes the narrowed intersection and the connection is closed,
  rather than the stream continuing under authority its caller no longer has

#### Scenario: The assertion expires but the PAT is still valid
- **WHEN** the assertion backing an open connection passes its expiry while the PAT remains live
- **THEN** the connection is closed. The client reconnects and the edge exchanges again - lengthening
  the assertion instead would widen the revocation window for every caller to solve a problem only
  streaming callers have

#### Scenario: A revalidation interval is configured above the ceiling
- **WHEN** `ludwig.pat.revalidation-interval` would make the composed revocation window exceed
  `ludwig.pat.max-revocation-window`
- **THEN** startup fails, by the same computation and the same check as the other three contributing
  values

### Requirement: The raw secret exists once, is never stored, and never reaches a log

The secret SHALL be generated from a cryptographically secure source, returned to the caller exactly
once in the issuance response, and persisted only as a digest. The in-memory carrier for the raw
secret SHALL mask itself in `toString()`, and the raw value SHALL be reachable only from the token
package and the issuance service.

#### Scenario: A PAT is issued
- **WHEN** issuance succeeds
- **THEN** the response carries the full secret once; and no subsequent read of that PAT through any
  endpoint can return it

#### Scenario: A secret carrier is interpolated into a log statement
- **WHEN** code logs the secret carrier, directly or inside a formatted message
- **THEN** the emitted text contains the mask and not the secret

#### Scenario: Code outside the permitted packages reads the raw secret
- **WHEN** a class outside the token package and the issuance service calls the carrier's reveal
  accessor
- **THEN** the `architecture-rules` build fails, naming the class

### Requirement: The secret format is prefixed, key-identified and checksummed

A PAT SHALL be rendered as `lpat_<keyId>_<secret>_<checksum>`. The prefix SHALL be a fixed literal
so the credential is discoverable by secret scanners; the key id SHALL be unique and indexed so that
verification is a point read; the checksum SHALL allow a malformed token to be rejected before any
database access. The published detection pattern for the format SHALL ship as a resource of
`pat-core`.

#### Scenario: A truncated or mistyped token is presented
- **WHEN** the presented value's checksum does not match its payload
- **THEN** it is rejected without any database access, with the same uniform failure response as a
  wrong secret, because a distinguishable "malformed" response tells an attacker which half of their
  guess was wrong

#### Scenario: A token is committed to a repository
- **WHEN** the shipped detection pattern is applied to a file containing a PAT
- **THEN** the token is matched, so that CI and SCM scanning can find a leaked credential before an
  incident does

### Requirement: Verifying a PAT is an indexed read and a constant-time comparison

Verification SHALL locate the record by key id through a unique index and SHALL compare digests in
constant time. It SHALL NOT scan candidate rows, and SHALL NOT apply a password-hashing key
derivation function to the secret.

The secret is a full-entropy machine-generated value, so there is no guessable distribution for a
work factor to protect; what a KDF would reliably add is per-request CPU on the system's one
brute-force target. The reasoning depends on the generator, so the code states that dependency at
the point of the hashing - this is recorded as a rule that cannot be mechanically checked.

#### Scenario: Two digests differing only in their last byte are compared
- **WHEN** a wrong secret is presented whose digest shares a long prefix with the stored digest
- **THEN** the comparison takes the same time as for a digest differing in its first byte

#### Scenario: A weak digest algorithm is named at a hashing call site
- **WHEN** source in the PAT hashing path names a weak digest algorithm
- **THEN** the Checkstyle build fails on the id'd rule, because the algorithm is a string literal
  argument and therefore a source-text fact rather than something bytecode analysis can see

### Requirement: A PAT may not mint, rotate or revoke a PAT

The PAT management surface SHALL refuse any request whose principal carries a
personal-access-token credential, before the endpoint's own authorization is evaluated.

Without this, a leaked narrow PAT is a persistence mechanism: exchange it, mint a wider one, and
revoking the original accomplishes nothing.

#### Scenario: A PAT-backed caller calls the issuance endpoint
- **WHEN** a request authenticated by a PAT-derived assertion reaches any PAT management endpoint,
  even one its scopes would otherwise permit
- **THEN** it is refused with `403` and a distinct problem type - not `401`, because the caller is
  authenticated and it is the credential that is not permitted here

#### Scenario: The same caller's session-derived token is used instead
- **WHEN** the same user calls the same endpoint with a session-derived assertion
- **THEN** the request proceeds to ordinary authorization

### Requirement: Only an authorized caller may issue a PAT, and only for a permitted subject

Issuance SHALL be authorized through `security-spring-boot-starter`. A caller SHALL be able to issue
a PAT for themselves; issuing on behalf of another subject SHALL require a distinct administrative
authority.

#### Scenario: An unauthenticated request reaches the management API
- **WHEN** no credential is presented
- **THEN** the response is the existing localized `401` problem document from the security starter's
  entry point, not a bespoke one

#### Scenario: A caller issues a PAT for themselves
- **WHEN** an authenticated caller requests a PAT whose owner is their own subject
- **THEN** it is issued, subject to the scope, expiry and lifetime requirements above

#### Scenario: A caller issues a PAT for someone else without the administrative authority
- **WHEN** an authenticated caller requests a PAT whose owner is a different subject and they lack
  the administrative authority
- **THEN** the request is refused with `403`, and the attempt is recorded as an audit event, because
  a request to mint a credential for another identity is a security event whether it succeeds or not

### Requirement: Rotation keeps the previous secret valid for a bounded overlap

Rotation SHALL mint a new secret on the same PAT - preserving its identity, scopes, owner and audit
history - and SHALL keep the previous secret accepted for at most
`ludwig.pat.rotation-overlap`, itself bounded by the maximum-lifetime ceiling.

A rotation that invalidates the old secret at once requires every consumer to be updated atomically.
Nothing real can do that, so the observed result is that nobody rotates.

#### Scenario: A PAT is rotated and the old secret is used during the overlap
- **WHEN** the previous secret is presented before the overlap elapses
- **THEN** it is accepted, and resolves to the same PAT id and scopes as the new secret

#### Scenario: The overlap elapses
- **WHEN** the previous secret is presented after the overlap has elapsed
- **THEN** it is refused with the uniform failure response, and its digest is no longer stored

#### Scenario: An operator wants to know whether rotation is complete
- **WHEN** both secrets exist during an overlap
- **THEN** last-use tracking distinguishes them, so an operator can see the old secret go quiet and
  end the overlap early rather than waiting it out blind

### Requirement: Revocation is immediate at the issuer and bounded everywhere else

Revocation SHALL take effect at the issuer on the next exchange. The total time until a revoked PAT
can no longer be used anywhere SHALL be computed and logged at startup, and startup SHALL fail when
it exceeds `ludwig.pat.max-revocation-window`.

Two paths compose differently and **both** SHALL be computed, with the larger one being the window
that is checked against the ceiling:

- *Request/response callers* - assertion lifetime + edge exchange cache lifetime + service authority
  cache TTL.
- *Long-lived connections* - revalidation interval + service authority cache TTL.

Independently configured TTLs compose into the one number that matters, and nobody multiplies them
out in production. A revocation window that is unbounded and unnoticed cannot be found by testing,
because every test passes. Computing only the first path would be worse than computing neither: it
would log a correct-looking number while being false for exactly the callers whose window is
largest.

#### Scenario: A revoked PAT is exchanged
- **WHEN** a revoked PAT is presented to the exchange
- **THEN** no assertion is issued, the failure is the uniform response, and a counter tagged as a
  revoked-token attempt is incremented - a signal that should alert, since presenting a revoked
  credential means something still holds it

#### Scenario: The composed window exceeds the ceiling
- **WHEN** either composition sums to more than `ludwig.pat.max-revocation-window`
- **THEN** the application fails to start, naming which path exceeded it, every contributing value
  and the total, in the same manner as the existing audience-validation startup check

#### Scenario: A deployment starts within the ceiling
- **WHEN** both compositions are within the ceiling
- **THEN** both computed totals are logged at startup, so the numbers exist in the record of every
  deployment rather than only in whoever last reasoned about them

### Requirement: An owner who is disabled takes their PATs with them

When the identity projection reports an owner as disabled or removed, that owner's PATs SHALL stop
producing usable authority, and SHALL be marked revoked rather than left live.

The intersection already makes them inert - a disabled owner resolves to no authorities. The
explicit revocation is so that the PAT list an auditor reads matches reality, rather than showing
live tokens for a departed employee.

#### Scenario: A user is disabled in the identity projection
- **WHEN** the owner of live PATs is disabled
- **THEN** those PATs resolve to no authority at once by intersection, and are marked revoked with a
  reason distinguishing owner-disabled from an operator's revocation

### Requirement: PAT lifecycle is audited through the one audit sink, and use is not audited per request

Issuance, rotation, revocation, expiry and refused issuance attempts SHALL be recorded as typed event
records exposing `toAuditEvent()`, delivered to `audit-core`'s single `AuditSink`. Use SHALL NOT
produce a per-request audit event. First use, use after dormancy and use from an unseen source SHALL
produce one.

The module SHALL NOT declare an audit SPI, a logger named `*.audit`, or a redaction mask, and SHALL
NOT wrap the sink call in a `try`/`catch` - whether a sink failure fails the caller is
`AuditFailurePolicy`, resolved from configuration, and a catch in a library overrides it.

#### Scenario: A PAT is used normally, many times
- **WHEN** a PAT is exchanged repeatedly within its normal pattern
- **THEN** no audit event is produced per exchange, and the row's last-use fields are updated at most
  once per the configured debounce interval

#### Scenario: A PAT is used from a source never seen for it before
- **WHEN** an exchange succeeds from a source address outside the pattern recorded for that PAT
- **THEN** an audit event is produced naming the PAT, its owner and the new source

#### Scenario: Last-use tracking fails
- **WHEN** the asynchronous last-use update cannot be written
- **THEN** the exchange still succeeds, and the failure is logged and metered - a telemetry write
  must not be able to break a production pipeline

#### Scenario: An authorization denial involves a PAT
- **WHEN** a PAT-backed request is denied because the owner lacks an authority the PAT scope named
- **THEN** the denial record names both the PAT and the absent authority, so that the first
  investigation ends at the owner's demotion rather than at the token

### Requirement: Per-PAT source restriction

A PAT MAY carry a list of CIDR ranges. When present, an exchange SHALL succeed only when the
presenting source falls within one of them.

#### Scenario: A PAT with an allowlist is presented from outside it
- **WHEN** the source address is outside every configured range
- **THEN** the exchange fails with the uniform failure response and the attempt is audited - the
  refusal is a security signal, and the response must not reveal that an allowlist is the reason

### Requirement: Expired and revoked records are retained for audit with their secrets destroyed

A PAT record SHALL be retained after expiry or revocation for `ludwig.pat.retention`, with its
secret digests cleared. Purging SHALL remove only records past retention.

#### Scenario: A PAT expires
- **WHEN** a PAT passes its expiry
- **THEN** its digests are cleared so the record can no longer authenticate anything, while its id,
  owner, scopes and timestamps remain readable for an audit of what that credential could do

#### Scenario: Retention elapses
- **WHEN** a record is older than the retention period
- **THEN** it is purged, and the purge is itself recorded

### Requirement: The verification cache declares the security purpose

The PAT verification cache SHALL be declared as a `CacheDefinition` with `CachePurpose.SECURITY` and
resolved from `LudwigCacheRegistry`. The module SHALL NOT construct a cache of its own.

The purpose is the one thing the module must get right and the one thing no check can infer. Declared
as `SECURITY`, the TTL is understood as the revocation window, the startup ceiling applies, and the
primitive refuses stale reads and the cluster-wide load lease - all of which are the required
behaviour here and all of which `PERFORMANCE` would quietly disable.

#### Scenario: The cache TTL is configured above the security ceiling
- **WHEN** `ludwig.cache.caches.<pat cache>.ttl` exceeds `ludwig.cache.security-ttl-ceiling`
- **THEN** startup fails naming the cache, by the existing behaviour of the cache primitive

#### Scenario: A cached verification entry expires
- **WHEN** an entry passes its TTL
- **THEN** it is not served stale, and the next verification reaches the database
