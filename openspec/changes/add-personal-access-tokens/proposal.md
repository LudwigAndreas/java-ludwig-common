## Why

Every non-browser caller into the platform today has exactly two options, and neither fits a
long-lived automated client acting *as a person*. A browser user arrives as a short-lived JWT the
edge minted from a session cookie; a partner or peer service arrives as a client certificate. A CI
pipeline, a `curl` in a runbook, a Terraform provider or an analyst's notebook that must act with
Alice's entitlements has nothing to present. In practice that gap gets filled by the two worst
available answers: a human's own session token pasted into a CI secret, or a shared service account
whose roles are the union of everyone who ever needed it and whose owner left the company in 2023.

A personal access token closes the gap, but only if it is built so that it cannot become a third
source of entitlement. `LudwigPrincipal` already states the platform's position - *"the OIDC
provider issues identity, not entitlement"*, roles are resolved locally so that a revoked role takes
effect within a cache TTL rather than a token lifetime, and *"a stolen token cannot carry elevated
roles that were never granted."* A PAT that freezes its owner's roles at creation time is precisely
the token-carried role that paragraph refuses, with a 90-day lifetime attached. This change
therefore defines a PAT as an **attenuation** - a filter over its owner's live authority, never a
source of authority - and makes that the enforced invariant rather than the documented intent.

## What Changes

**The topology: the PAT is exchanged for a JWT at the edge, and services are not taught a new
credential format.** The edge already exchanges an opaque long-lived credential (the session
cookie) for a short-lived JWT. A PAT is an opaque long-lived credential. It goes through the same
exchange, so the 29 modules that already verify a JWT accept PAT-backed callers with no filter, no
new `PrincipalType` and no PAT table of their own. The only service-side change is in the one class
that converts a JWT into a principal.

- **New module `pat-core`** (library, `sources/pat-core`, package `ru.ludwigandreas.pat`,
  parented by the reactor root `common`, imports `ludwig-bom`): the token format and nothing
  stateful. `PatSecret` (a masked-`toString` carrier for the raw secret), the
  `lpat_<keyId>_<secret>_<crc>` mint/parse/checksum, SHA-256 fingerprinting with constant-time
  comparison, the `PatAttenuation` scope set, and `PatClaims` - the single definition of the
  `ludwig_pat` JWT claim, shared by the issuer and the verifier so the two sides cannot drift.
  **Zero in-repo dependencies**, deliberately, for the same reason `cache-spring-boot-starter` has
  none: it is depended on from both sides of the security seam.

- **New module `pat-spring-boot-starter`** (starter, `sources/pat-spring-boot-starter`, package
  `ru.ludwigandreas.pat`, parented by `common`, imports `ludwig-bom`): the issuer side as a
  drop-in. The `ludwig_pat` table and its Liquibase changelog, the QueryDSL repository, the
  lifecycle service (issue / list / revoke / rotate-with-overlap / expire / purge), the management
  REST API secured through `security-spring-boot-starter`, the RFC 8693 token-exchange endpoint the
  edge calls, the typed audit events, the `CachePurpose.SECURITY` verification cache, the
  `ProblemDetail` mappers and the i18n bundle in both locales. `identity-provider-service` adds one
  dependency and has a complete PAT subsystem; nothing in this repository is required to run it.

- **Modified `security-spring-boot-starter`** (starter): `JwtPrincipalConverter` learns to read the
  `ludwig_pat` claim, resolve the owner's authorities through the existing `AuthorityLookup` exactly
  as it does today, and then **intersect** them with the PAT's attenuation. `LudwigAuthentication`
  gains a credential dimension - via an overloaded constructor, so nothing existing changes shape -
  which is what lets audit and policy tell *what was presented* from *who is accountable*. A
  fail-fast startup validation computes and logs both revocation-window compositions and refuses to
  start when either exceeds the configured ceiling, in the same style as the existing
  `require-audience` check.

  **No breaking change.** An earlier draft put the credential on `LudwigPrincipal` as a new record
  component and accepted a broken canonical constructor. That was corrected during implementation:
  `LudwigPrincipal` is the *identity* model, and a credential is a property of how one request
  authenticated rather than of who the caller is - the same argument this change uses to refuse a
  fourth `PrincipalType`, applied one level further in. An overloaded constructor on the
  per-request authentication object is binary- and source-compatible, so there is nothing for
  revapi to flag and no collision with the in-flight
  `add-api-compatibility-and-release-governance` change.

- **Modified `architecture-rules`** (rules): a new `RuleGroup.CREDENTIALS` carrying the rules that
  make the attenuation invariant mechanical - no second PAT store, SPI or token format; nothing
  outside `pat-core`'s token package and the issuance service may call `PatSecret.reveal()`;
  nothing but the intersecting converter may construct a PAT-credentialed principal.

- **Modified `checkstyle-rules`** (rules): one id'd `RegexpSinglelineJava` rule refusing a weak
  digest algorithm name in a PAT hashing call site. This is a source-text fact (the algorithm is a
  string literal argument) and therefore Checkstyle's, not ArchUnit's - the same split that already
  puts `SecondRedactionMask` in Checkstyle because ArchUnit cannot see a string constant's value.

- **Modified root `pom.xml`**: two `<module>` entries. No other build configuration changes.

- **Not modified: `ludwig-bom`.** Every dependency the new modules need - `spring-boot-starter-data-jpa`,
  `querydsl-jpa`, `liquibase-core`, `postgresql`, `spring-security-oauth2-jose`, `nimbus-jose-jwt` -
  is already managed, either by `spring-boot-dependencies` or by an existing `ludwig-bom` entry that
  another module already consumes. The checksum uses `java.util.zip.CRC32C` from the JDK and the
  digest uses `java.security.MessageDigest`, so neither adds a dependency. Stated explicitly because
  a `ludwig-bom` change would widen the gate to `mvn clean install` with no narrower option.

## Capabilities

### New Capabilities

- `personal-access-token`: what a PAT is and is not. The attenuation invariant (effective authority
  is the owner's live authority intersected with the PAT's scopes, never a union and never a
  snapshot), the audience set that decides *where* a PAT may be presented as distinct from the scopes
  that decide *what* it may do, the secret format and why its hash is a plain digest rather than a
  password KDF, the mandatory-expiry and maximum-lifetime policy, rotation with an overlap window,
  the bounded and startup-validated revocation window on both its request/response and its
  long-lived-connection path, the no-privilege-escalation rule (a PAT may not mint, rotate or revoke
  a PAT), and the lifecycle audit events.
- `token-exchange`: the RFC 8693 exchange that turns a PAT into a short-lived, audience-scoped JWT -
  the request and response shape, the guarantee that a client presents one static header and never
  performs an exchange itself (the property that makes this usable from `curl` and from any
  automation tool), the requirement that the response declare its own cacheable lifetime (without
  which the issuer becomes a per-request availability dependency and the whole topology collapses
  into a remote check on every call), the uniform failure response that gives no oracle
  distinguishing an unknown key id from a bad secret, the separate client-facing `401` the edge
  returns so an automation user can act on a dead token without learning why it died, and the rate
  limiting that makes the endpoint survive being the one brute-force target in the system.

### Modified Capabilities

- `problem-detail-pipeline`: the new PAT failure modes enter the existing single RFC 9457 pipeline
  as mappers contributed from the starter. No `@RestControllerAdvice` of its own. Adds the
  credential-rejected problem type, distinct from an authorization denial because the two demand
  opposite actions from an automation client - reissue a token, versus request a role - and a client
  that cannot tell them apart stalls unattended.
- `enforcement-triad`: adds `RuleGroup.CREDENTIALS` to the ArchUnit side and one id'd rule to the
  Checkstyle side, records which PAT conventions could **not** be mechanised and why, and adds the
  rule that a check which cannot be written yet is deferred to a named future change rather than
  written as a rule whose matching set is empty - a vacuous rule is the most expensive kind of absent
  one, because it stays green forever and gives the next reader a reason not to look.
- `data-access`: asserted unchanged, as a scenario rather than a silence. The PAT claim is an
  indexed point read on `key_id` followed by an application-side constant-time comparison, so it
  needs no `ON CONFLICT ... RETURNING` and therefore **no third SQL carve-out**. The two carve-outs
  stay at two, and a test in the new starter fails the build if SQL or JDBC appears in it at all.

### Unchanged contracts, stated rather than passed over

`audit-envelope`, `cache-purpose`, `i18n-bundles`, `module-dependency-direction`, `pom-topology`,
`repository-layout` and `test-layout` are all **conformed to, not changed**. The new modules declare
typed audit events with a `toAuditEvent()` against the one `AuditSink`, declare a `CacheDefinition`
with `CachePurpose.SECURITY` and resolve it from `LudwigCacheRegistry`, ship both locale bundles,
sit in `sources/` as their role dictates, and use the `{unit,integration,architecture}` test layout
with integration tests named `...IT`. None of those capabilities' requirements change.

`long-running-operations` is untouched: every PAT operation is synchronous. Issuing a token is a
single insert and the secret must be returned in that response or never, so there is no 202 to
build and no run table to keep.

## Impact

### Verification gate

The gate is wide because the root POM is the parent of every library module and gains two
`<module>` entries, and because `security-spring-boot-starter` has seven in-repo dependents. Per
`scripts/manifest.sh module`:

| Touched module | Role | In-repo dependents the gate must run |
|---|---|---|
| `pat-core` | library (new) | `pat-spring-boot-starter` |
| `pat-spring-boot-starter` | starter (new) | none |
| `security-spring-boot-starter` | starter | `crud-service-example`, `export-spring-boot-starter`, `file-action-spring-boot-starter`, `identity-projection-spring-boot-starter`, `notification-service`, `test-support-security`, `user-settings-spring-boot-starter` |
| `architecture-rules` | rules | `crud-service-example`, `file-action-spring-boot-starter`, `file-ingest-spring-boot-starter`, `messaging-spring-boot-starter`, `notification-service`, `object-storage-spring-boot-starter`, `user-settings-spring-boot-starter` |
| `checkstyle-rules` | rules | none |
| `pom.xml` (root) | parent of every library | all of them |

Because the root POM changes, the honest gate is `scripts/manifest.sh build` followed by
`mvn clean install`, not a `-pl` subset. `scripts/gate.sh --change add-personal-access-tokens`
derives this; the narrower per-module commands are in `tasks.md` for the loop during development.

### Outside this repository

- **`identity-provider-service`** gains the `pat-spring-boot-starter` dependency, a Liquibase
  changelog inclusion, and a signing key for the exchanged assertion. It is the only service that
  stores a PAT.
- **The edge (Envoy / gateway)** gains one rule: a bearer credential matching the `lpat_` prefix is
  exchanged at the issuer for the destination service's audience and replaced with the returned JWT,
  the result is cached per `(secret digest, audience)` for the lifetime the response declares, and a
  failed exchange is rendered as the client-facing `401` whose problem type and management location
  `pat-core` publishes as constants. This is the one piece of the design that lives outside any
  repository and therefore outside any build check - called out here, and again in the design's
  enforcement table as two explicitly non-mechanisable rules rather than omitted.
- **Automation clients change nothing.** A `curl`, a CI job or any other tool presents
  `Authorization: Bearer lpat_...` and never calls the exchange, never parses a token response and
  never tracks an expiry. That is the deliverable, not a side effect, and the client-side exchange
  variant is rejected in the design rather than merely unbuilt.
- **Secret scanning.** `pat-core` ships the detection regex for the `lpat_` format as a resource so
  that CI, `gitleaks` and the SCM's own scanner can find a leaked token. A credential format that
  cannot be grepped for is one whose leaks are discovered during the incident.

## Non-goals

- **No PAT storage, issuance or management API in `security-spring-boot-starter`.** It stays free of
  any persistence dependency, as `web-core-spring-boot-starter` does for the operation and
  preference contracts. The verification side is a claim reader; the storage side is a different
  module that depends on it.
- **No new `PrincipalType`.** A PAT is not a fourth door into a service - it is Alice, with less. The
  door is still the JWT the edge forwarded. The new dimension is *credential*, which is orthogonal:
  a `USER` may present a session-derived JWT or a PAT-derived one, and both remain `USER`.
- **No frozen scopes and no scope that grants.** A PAT whose owner lost a role loses it too, in the
  same cache TTL as any other authority change, with no revocation step. The corollary is that a
  PAT scope naming an authority the owner does not hold resolves to nothing rather than to an
  error - it is a filter, and filtering for something absent yields the empty set.
- **No PAT verification in every service.** No per-service PAT table, no per-service filter, and
  therefore no N revocation surfaces. Revocation that only works in one of N places is the failure
  mode a PAT exists to prevent.
- **No non-expiring tokens by default.** The maximum lifetime is a configured ceiling enforced at
  creation. A deployment may raise it; it may not escape it without changing configuration that an
  auditor can read.
- **No per-request `pat.used` audit event.** The volume is per-request and the signal is not. The
  row carries a debounced `last_used_at`/`last_used_ip` written off the critical path, and the audit
  sink receives only first use, use after dormancy, and use from an unseen source - the three that
  actually indicate compromise.
- **No shared service-account PAT.** A PAT has exactly one owner and derives from exactly one
  identity. A credential for a workload with no human owner is a `SERVICE` principal with a
  workload certificate, which the platform already has.
- **Not an OAuth authorization server.** The starter verifies PATs and asks a pluggable minter for
  an assertion. It does not implement discovery, JWKS rotation, consent or any other grant type;
  where the deployment already runs an OIDC provider, the minter delegates to it.
- **No client-side exchange.** Rejected, not merely unbuilt: it would push token-endpoint calls and
  expiry tracking into every automation tool, and avoiding that is most of why this topology was
  chosen. A single static `Authorization` header is the requirement.
- **No streaming transport and no MCP server.** The long-lived-connection revalidation rule is
  specified and its configuration property and ceiling check ship here, so the knob exists before the
  first transport does - but no transport is added, and the test that enforces the rule is a recorded
  obligation on the change that introduces one. An MCP starter is a separate change that consumes
  this capability; in particular MCP over stdio has no HTTP, no edge and no exchange, and should not
  be made to fit.
- **No audience default.** A PAT with no audience set is refused at issuance. Every available default
  is either "every service this PAT permits" or "everything", and both are the condition audience
  binding exists to prevent.
