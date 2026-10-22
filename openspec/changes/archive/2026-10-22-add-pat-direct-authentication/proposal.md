## Why

`add-personal-access-tokens` shipped a complete PAT subsystem built on one assumption: that the edge
exchanges an `lpat_` credential for a short-lived JWT before it reaches a service. That assumption is
false for this platform, and the capability is therefore **unusable today**.

The edge here is a **company-provided proxy gateway**. It checks for a session, redirects to the
corporate OIDC provider when there is none, and exchanges the resulting session cookie for a
short-lived JWT. That is the whole of what it does. It has no personal-access-token support, it does
not recognise the `lpat_` format, and it cannot be extended - no filter, no `ext_authz` hook, no Lua
or Wasm module is available to us. The company intends to add PAT support in a future release; there
is no date.

So a request carrying `Authorization: Bearer lpat_...` reaches that gateway, is found to have no
session, and is **redirected to OIDC** - which is fatal for a `curl`, a CI job or an MCP client.

The obvious workaround is to mint our own JWT from a PAT, and it is worse than it looks. It would make
every service trust a **second issuer**, which Spring Boot's single `issuer-uri` property cannot
express; and it would still leave nothing to *perform* the exchange, since the gateway will not - so
either every client does it (the thing the original design exists to prevent) or we deploy our own
proxy as well. A second issuer, a signing key to own and rotate, and a new proxy, to avoid one
in-cluster hop.

This change takes the other route: **authenticate the PAT directly in the service**, against the one
central token table, and skip the JWT entirely. No second issuer, no signing key, no new proxy, no
client change. It is explicitly **temporary scaffolding** - when the company gateway gains PAT support
it is removed by setting one property to `false`, and the parts that matter (the table, the management
API, the attenuation, the audit trail, and the `ludwig_pat` claim reader that will then start being
used) are unchanged in both worlds.

## What Changes

- **New capability `pat-direct-authentication`.** A service may authenticate an `lpat_` bearer
  credential itself, by introspecting it against the issuing service, instead of receiving an
  already-exchanged assertion.

- **Modified `pat-core`** (library): the introspection request and response shape, defined once and
  read by both sides, for the same reason `PatClaims` is - the issuer and every verifier are released
  independently and a field name written twice is a field name that diverges.

- **Modified `pat-spring-boot-starter`** (starter): an RFC 7662-shaped `/introspect` endpoint. It
  reuses `PatVerifier` unchanged, so the parse, the checksum, the indexed point read, the
  constant-time comparison, the revocation and expiry checks, the CIDR allowlist and the
  `CachePurpose.SECURITY` cache all apply exactly as they do to the exchange. It answers with the
  token's owner, scopes and audiences - **never a secret, never a digest, never a key id**.

- **Modified `security-spring-boot-starter`** (starter): a `PatAuthenticationFilter`, **off by
  default**, placed between the mTLS filter and the bearer-token filter. Plus a cached introspection
  client, and a third revocation-window composition for callers authenticated this way.

- **Modified `architecture-rules`** (rules): the `credentials.one-attenuation-path` rule is
  **narrowed**, not widened. The attenuation and principal construction move into one dedicated type
  that both the JWT converter and the new filter call, and the rule is re-pointed at that single type
  instead of at the converter's whole package. A second authentication path must not mean a second
  place where authority is computed.

- **No new module. No POM change anywhere.** The introspection client uses Spring Framework's
  `RestClient`, which `security-spring-boot-starter` already has through
  `spring-boot-starter-web` - so no in-repo dependency is added to the module that sits nearest the
  bottom of the reactor, and `ludwig-bom` and the root POM are untouched. Stated explicitly because it
  is what keeps the gate narrower than a full install.

## Capabilities

### New Capabilities

- `pat-direct-authentication`: a service authenticating an `lpat_` credential itself. The
  introspection contract and what it may and may not disclose; the filter's position in the chain and
  why; the single central table that keeps one revocation point; the third revocation-window
  composition; the preserved single attenuation path; and the removal path when the edge gains PAT
  support.

### Modified Capabilities

- `token-exchange`: one requirement is **reversed**, and reversing it loudly is the point of listing
  it here. The shipped spec says *"No module in this repository other than `pat-spring-boot-starter`
  SHALL accept a PAT as a request credential"*, with a scenario asserting that a PAT presented to a
  service directly is **not** authenticated. That is now conditionally false: it holds when the filter
  is off, which stays the default, and does not when a deployment has deliberately enabled it. The
  requirement is rewritten to say so rather than left to be contradicted by code.
- `personal-access-token`: the attenuation invariant and the single-construction-site requirement both
  stand, and both need restating against two authentication paths rather than one. The revocation
  window requirement gains its third composition.
- `enforcement-triad`: records that `credentials.one-attenuation-path` was narrowed rather than
  widened to accommodate the second path, and why that direction was available at all.

### Unchanged contracts, stated rather than passed over

`audit-envelope`, `cache-purpose`, `i18n-bundles`, `problem-detail-pipeline`, `data-access`,
`module-dependency-direction`, `pom-topology`, `repository-layout` and `test-layout` are **conformed
to, not changed**. In particular there is still no SQL in `pat-spring-boot-starter`, the introspection
cache is still a `CachePurpose.SECURITY` `CacheDefinition` resolved from `LudwigCacheRegistry`, and the
new endpoint's failures still enter `web-core`'s single pipeline through `LocalizedException`.

## Impact

### Verification gate

No POM changes, so this is **not** a full-install gate - which is the one practical benefit of adding
no module and no dependency. Per `scripts/manifest.sh module`:

| Touched module | Role | In-repo dependents the gate must run |
|---|---|---|
| `pat-core` | library | `pat-spring-boot-starter`, `security-spring-boot-starter` |
| `pat-spring-boot-starter` | starter | none |
| `security-spring-boot-starter` | starter | `crud-service-example`, `export-spring-boot-starter`, `file-action-spring-boot-starter`, `identity-projection-spring-boot-starter`, `notification-service`, `pat-spring-boot-starter`, `test-support-security`, `user-settings-spring-boot-starter` |
| `architecture-rules` | rules | `crud-service-example`, `file-action-spring-boot-starter`, `file-ingest-spring-boot-starter`, `messaging-spring-boot-starter`, `notification-service`, `object-storage-spring-boot-starter`, `pat-spring-boot-starter`, `user-settings-spring-boot-starter` |

### Outside this repository

- **A route that bypasses the session gateway is a precondition, and it is not code.** Machine traffic
  must reach services without traversing the company gateway, or it is redirected to OIDC and no
  amount of filter is relevant. A separate hostname, a path prefix or a dedicated ingress all work.
  **This is unresolved at the time of writing** and is the first thing to confirm; everything in this
  change is inert without it.
- The issuing service (wherever `pat-spring-boot-starter` runs) becomes reachable from every service
  that enables the filter. It does not need a signing key, a JWKS endpoint or an OIDC identity - which
  is the single largest simplification this route buys, and it closes the shipped design's open
  question about whose signing key to use.

## Non-goals

- **Not a replacement for the exchange.** `/oauth2/token` stays, tested and dormant. When the company
  gateway learns PAT, the exchange is what it will call, and the `ludwig_pat` claim reader already in
  `JwtPrincipalConverter` is what will consume the result. This change adds a path; it removes none.
- **No second issuer, no self-minted JWT, no signing key.** Explicitly rejected rather than unbuilt,
  for the three reasons in `design.md`.
- **No own proxy or gateway.** The point of this route is that it needs none.
- **No per-service token table.** One table, in the issuing service, reached by introspection. The
  original design rejected per-service verification because N tables means N revocation surfaces, and
  that objection is fully preserved: there is still exactly one table and one place a revocation takes
  effect.
- **Not on by default.** A deployment that has an edge capable of the exchange should use it; this is
  for a deployment that does not. The property defaults to `false` so that adding the starter cannot
  silently open a second authentication path.
- **No change to how authority is computed.** The filter computes nothing. It calls the same single
  construction site the JWT converter calls, which is what keeps the attenuation invariant a property
  rather than a promise repeated twice.
- **Not a long-term shape.** This is scaffolding with a removal path stated in the design and a single
  property controlling it. It is proposed on the understanding that it will be deleted.
