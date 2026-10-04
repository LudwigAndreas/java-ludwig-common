# `pat-spring-boot-starter`

The issuing half of the platform's personal access tokens, as a drop-in. Add the dependency, include the
changelog, supply a signing key, and the identity provider has a complete PAT subsystem: the table, the
management API, the RFC 8693 exchange, audit, metrics, i18n and problem details.

The *verifying* half is not here. Every service already has it, in `security-spring-boot-starter`.

## The topology in one diagram

```
  CI / curl / notebook
        │  Authorization: Bearer lpat_<keyId>_<secret>_<crc>     ← ONE static header, forever
        ▼
  ┌──────────────────────────────────────────────────────────┐
  │ EDGE (Envoy / gateway)   ← NOT in this repository         │
  │   prefix "lpat_" ?  ──▶ cache[(digest, audience)] hit ?    │
  │                            hit  ──▶ reuse assertion        │
  │                            miss ──▶ exchange, then cache   │
  │                                     for expires_in - skew  │
  └───────────────────────────┬──────────────────────────────┘
                              │ POST /oauth2/token
                              ▼
  ┌──────────────────────────────────────────────────────────┐
  │ identity-provider-service   (this starter)                │
  │   parse → checksum → rate limit → keyId point read        │
  │   → constant-time digest compare → not revoked/expired    │
  │   → source allowed → audience permitted                   │
  │   → mint JWT: sub=<owner>, ludwig_pat={id, scopes}, 5m    │
  └───────────────────────────┬──────────────────────────────┘
                              │ short-lived, audience-scoped assertion
                              ▼
  ┌──────────────────────────────────────────────────────────┐
  │ ANY service - unchanged filter chain                      │
  │   owner authorities = AuthorityLookup.lookup(sub) ← LIVE  │
  │   effective         = owner ∩ ludwig_pat.scopes           │
  └──────────────────────────────────────────────────────────┘
```

The property worth noticing: **the client sends one static header and never performs an exchange**. No
token endpoint call, no `jq` to pull a field out of a JSON body, no expiry tracking, no refresh. That is the
main reason this topology was chosen over a client-side exchange, which would push OAuth logic into every
`curl` in every runbook.

## Quick start

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
      issuer: https://idp.internal            # REQUIRED - startup fails without it
      audiences: [deploy-service, billing-service]
  security:
    public-paths:
      - /actuator/health
      - /oauth2/token                          # REQUIRED - see "The exchange must be public"
```

Plus an `RSAKey` bean for the default minter, or your own `PatAssertionMinter` if the provider already owns
a signing key — which is the better arrangement and is why the SPI exists.

## What the edge owes, and why no build here can check it

Three obligations live outside this repository. They are written out verbatim because nothing in the build
can observe them, and that gap is recorded in the change's enforcement table rather than left to be found.

**1. Exchange per destination audience, and cache per `(secret digest, audience)`.**

```
POST /oauth2/token
Content-Type: application/x-www-form-urlencoded

grant_type=urn:ietf:params:oauth:grant-type:token-exchange
&subject_token=<the lpat_ value from the Authorization header>
&subject_token_type=urn:ludwig:params:oauth:token-type:pat
&resource=<the service this request is going to>
```

Cache the response for `expires_in` seconds minus a clock-skew margin. **A digest-only cache key is wrong**:
it would serve one service's assertion to another, and every such request would then fail audience
validation at the destination.

**Without the caching the whole design collapses.** Every service request becomes an issuer request by
proxy, which is the remote-check-per-request topology that was rejected for availability. The observable
proof that caching works: `ludwig.pat.exchange` stays proportional to *distinct tokens per assertion
lifetime* rather than rising with request volume. Alert on that.

**2. Render a failed exchange as a client-facing `401`.** The client called a *service*; it never called the
exchange and does not know it exists. The uniform refusal the issuer returns is for the edge.

```
HTTP/1.1 401 Unauthorized
WWW-Authenticate: Bearer error="invalid_token"
Content-Type: application/problem+json

{ "type": "https://problems.ludwigandreas.ru/credential/rejected",
  "status": 401,
  "detail": "This credential was not accepted. ...",
  "manageCredentialsAt": "/api/v1/personal-access-tokens" }
```

The constants are published as `PatProblemTypes` in `pat-core` so the edge configures against a definition
rather than inventing one. **Disclose no cause.** Not which of eleven, not "revoked", not "expired".

**3. Strip the `lpat_` credential before forwarding.** The service receives the assertion, never the token.

## The exchange must be public

`/oauth2/token` has to be reachable unauthenticated. It *consumes* a token as its subject; it does not
accept one as the caller's credential — the same rule that keeps a token off the management surface.

`PatExchangeConfigurationValidator` warns when the path is missing from `ludwig.security.public-paths`. A
warning rather than a startup failure, because the failure mode is loud and safe: the edge receives a 401,
no assertion is minted, PATs visibly do not work, and nothing is exposed. Refusing to start would also be
wrong for an application that published the path through its own `SecurityFilterChain`, which this module
cannot see.

## The revocation window, computed for you

Three TTLs compose into the one number that matters, and nobody multiplies them out in production. Startup
computes **both** paths, logs them, and refuses to start when either exceeds the ceiling:

```
request/response callers:  assertion lifetime + edge cache lifetime + authority cache TTL
long-lived connections:    revalidation interval + authority cache TTL
```

With the defaults: `5m + 5m + 60s = 11m` and `1m + 60s = 2m`, against a `15m` ceiling.

To shorten it, shorten `ludwig.pat.exchange.assertion-lifetime` **and** the edge's cache together — they are
one number split across two systems, and lowering only the first makes the computed figure a fiction.

Both paths are computed on purpose. Computing only the first would log a correct-looking number while being
false for exactly the callers whose window is largest.

## What is enforced for you

| Rule | How |
|---|---|
| Effective authority is owner ∩ scopes, never a union | one construction site, `credentials.one-attenuation-path` |
| No second credential store, SPI or token format | `credentials.no-second-credential-store`, `...-spi` |
| The raw secret never leaves the credential modules | `credentials.secret-carrier-stays-inside` + a fenced accessor + a masked `toString()` |
| A token may not mint, rotate or revoke a token | `PatCredentialGuard`, asserted on **every** endpoint |
| Mandatory expiry, maximum lifetime, non-empty scopes, non-empty audiences | refused at issuance, each with its own localized message |
| The verification TTL is a bounded revocation window | `CachePurpose.SECURITY` — startup ceiling, no stale reads |
| No SQL or JDBC anywhere | `SqlConfinementTest`, the inverse of the two carve-outs |
| No weak digest algorithm | Checkstyle `WeakDigestAlgorithm` |

## Deployment checklist

1. **Register the `lpat_` format with your secret scanners *before* the first token is issued.** A scanner
   added afterwards does not scan history by default, so tokens committed in the gap stay invisible for
   exactly as long as they stay valid. The pattern and a ready `gitleaks` rule are in `pat-core`'s
   `pat-secret-detection.properties`.
2. Include `db/changelog/pat/pat-changelog.xml`, or let `PatLiquibaseAutoConfiguration` run it.
3. Supply the signing key, or a `PatAssertionMinter` that delegates to your provider.
4. Add `/oauth2/token` to `ludwig.security.public-paths`.
5. Grant `pat:manage` to anyone who may hold a token, and `pat:manage:any` only to whoever may mint one for
   somebody else — that second one is how a credential outlives the person who created it.
6. Alert on `ludwig.pat.exchange.failure{reason="revoked"}`. Presenting a revoked credential means something
   still holds it.
7. Alert on `ludwig.pat.exchange` rising with request volume. That is a non-caching edge.

## What this module deliberately leaves alone

- **Verification.** `security-spring-boot-starter`, in every service. This module never sees a request that
  a token authenticated.
- **The token format.** `pat-core`, which has zero in-repo dependencies so both sides of the seam can share
  one definition of the `ludwig_pat` claim.
- **Signing, in production.** The `PatAssertionMinter` SPI. A deployment with an OIDC provider should point
  it there: one key to rotate, one JWKS to publish.
- **Streaming transports.** `ludwig.pat.revalidation-interval` and the long-lived composition above ship
  now so the knob exists before the first transport does. There is deliberately **no ArchUnit rule** —
  nothing in this repository holds a connection open, so a rule would pass vacuously and read as coverage.
  Whoever adds the first streaming transport owes this module the enforcing test.
- **A shared credential table.** A deploy key or a signed webhook secret has a different lifecycle,
  issuance authority and revocation story. A table built for all three would hold none of them well.
