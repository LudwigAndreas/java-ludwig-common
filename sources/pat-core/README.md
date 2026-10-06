# `pat-core`

The personal-access-token format, and nothing stateful.

This module holds the wire format, the masked secret carrier, the digest, the attenuation scope set, the
single definition of the `ludwig_pat` JWT claim and the problem-type constants the edge renders a rejected
credential with. It has no storage, no Spring, no HTTP and **no in-repo dependencies**.

## Why this module exists separately

Because it is depended on from **both sides of the security seam**.

```
                        pat-core  (0 in-repo deps)
                         ^      ^
                         |      |
  security-spring-boot-starter  |         <- reads the claim, applies the intersection
          ^         ^           |
          |         |           |
     pat-spring-boot-starter ---+         <- stores, issues, exchanges
```

`security-spring-boot-starter` sits near the bottom of the reactor: `identity-projection`,
`user-settings`, `web-core` and every service depend on it. The issuing starter depends on `security` in
turn. If this module depended on `audit-core`, on `web-core` or on `security` itself, the security starter
could not depend on it - and then the claim name would have to be written as a literal on both sides of the
seam.

**That literal is the failure this module exists to prevent**, and it does not fail loudly. A verifier
looking for `ludwig_pat` in an assertion that carries `ludwigPat` finds no claim, concludes the caller is an
ordinary session-authenticated user, and resolves their authorities **with no attenuation applied at all**. A
token scoped to one read endpoint then carries its owner's entire authority. A typo, failing open, invisible
to any test that uses a matching pair of modules.

The same reasoning as `cache-spring-boot-starter`'s zero-dependency property, for the same structural reason.

## What is here

| Type | What it is for |
|---|---|
| `token.PatSecret` | The raw secret, in a type that will not print itself |
| `token.PatTokens` | Mint and parse `lpat_<keyId>_<secret>_<crc32c>` |
| `token.PatDigest` | SHA-256, and constant-time comparison |
| `token.PatDetection` | The published scanner pattern, read from the same file scanners read |
| `scope.PatAttenuation` | The scope set, and the one operation: intersection |
| `claim.PatClaims` | The single definition of the `ludwig_pat` claim |
| `problem.PatProblemTypes` | The problem types and management location the **edge** renders |

## Introspection, and why its field names look odd

`introspection.PatIntrospectionResponse` is what the issuing service answers when a *service* asks about
a token, rather than when the edge exchanges one. It exists because this platform's edge - a
company-provided session gateway - cannot perform the exchange, so a service has to be able to
authenticate an `lpat_` credential itself.

Its components are named `sub`, `aud`, `exp` rather than `subject`, `audiences`, `expiresAt`. That is not
a style choice. **This module has zero dependencies, which includes no Jackson**, so there is no
`@JsonProperty` available to bridge a readable Java name to an RFC 7662 wire name — the component names
*are* the wire names.

An attempt to add Jackson here failed the build, and that is the zero-dependency property doing its job.
It is what lets `security-spring-boot-starter` — which sits near the bottom of the reactor — depend on
this module at all. The alternative, mapping readable names to wire names up in the starter, would have
put the field names in two places, which is the one thing this type exists to prevent.

It carries no secret, no digest and **no key id**. The last is the least obvious and is the same decision
every other response here makes: a key id changes on rotation, so it is useless to a caller naming a
token and would quietly become the identifier somebody built an integration on. `patId` survives a
rotation.

## The invariant

A personal access token is an **attenuation** of its owner's live authority:

```
effective = authoritiesOf(owner, now)  ∩  token.scopes()
```

Never a union. Never a snapshot taken at issuance. The owner side is resolved live, per request, through the
same `AuthorityLookup` every other caller goes through.

`LudwigPrincipal` already states why: the identity provider issues identity, not entitlement, so a revoked
role stops working within a cache TTL rather than a token lifetime, and a stolen token cannot carry roles
that were never granted. A token freezing its owner's roles at issuance would be precisely the
token-carried role that reasoning refuses, with a ninety-day lifetime attached.

`PatAttenuation` therefore offers **no** `union`, `plus`, `grant` or `withAdditional`. Not because nobody
needed one - because the difference between `retainAll` and `addAll` is one method name, invisible in review,
and catastrophic. A unit test asserts reflectively that no widening method exists, because no behavioural
test would fail if somebody added a reasonable-looking one.

## Three things worth knowing before changing this module

**1. The digest is a plain SHA-256, not bcrypt or Argon2, and that is deliberate.** A password KDF exists to
make brute-forcing a *low-entropy, human-chosen* secret expensive. This secret is 43 base62 characters from
`SecureRandom` - there is no distribution to exploit, so the work factor would protect against an attack that
cannot be mounted, while reliably adding tens of milliseconds to **every token exchange**, which is the one
endpoint that is both on the hot path and the system's only brute-force target.

**This reasoning depends entirely on the generator and no check can assert that.** If the secret ever becomes
shorter, human-chosen, or structured for readability, the premise is gone and this becomes an unsalted hash of
a guessable value. The comment at `PatDigest` says so at the point of the rule, which is this repository's
convention for a limit analysis cannot express.

**2. The checksum is not a security control.** CRC32C is not a MAC - anyone can compute it, so anyone can
produce a well-formed token for any key id. It rejects a typo and a truncation without a database query, and
that is all it does. What stops a forgery is the digest comparison, which reaches the database. This sentence
exists because "it has a checksum" is read as "it is tamper-proof" by the next person.

**3. The random parts are base62, not base64url, and the reason is a bug this format briefly had.** The
base64url alphabet is `A-Za-z0-9-_`, which **contains the separator**. About one token in twenty had an
underscore inside its key id or secret, `split("_")` returned five parts instead of four, and that token was
permanently unusable while nineteen in twenty worked perfectly. A single round-trip test passes about 95% of
the time, so this would have shipped as "tokens sometimes don't work" with no reproducible case. That is why
`PatTokenFormatTest` draws hundreds of samples rather than one, and why the fix narrowed the alphabet rather
than substituting the offending characters - substitution would have made the distribution non-uniform,
quietly reducing entropy.

## What this module deliberately leaves to others

- **Storage, issuance, rotation, revocation and the exchange** - `pat-spring-boot-starter`. Putting them here
  would mean a persistence dependency in the module the security starter depends on, which is the constraint
  that shaped this whole design.
- **The intersection against typed authorities** - `security-spring-boot-starter`, which owns `Authorities`
  and `AuthorityLookup`. This module holds scopes as opaque strings, which is exactly what keeps its
  dependency count at zero.
- **Validating that a scope names a real authority** - the issuer, at issuance, where the owner's current
  authorities are in hand. That check is a fail-fast nicety; the enforcement is the use-time intersection,
  every time.
- **Rendering the rejected-credential response** - the **edge**, which is not in this repository. This module
  publishes the constants so the edge configures against a definition rather than inventing one. No build here
  can check what the edge actually returns, and that gap is recorded in the change's enforcement table rather
  than left to be discovered.

## Registering the scanner pattern

`src/main/resources/ru/ludwigandreas/pat/pat-secret-detection.properties` carries the detection regex, the
prefix for a cheap pre-filter, and a ready-to-paste `gitleaks` rule body. It is data rather than a Java
constant because most of its consumers are not Java.

**Register it before the first token is issued.** A scanner added afterwards does not scan history by
default, so tokens committed in the gap stay invisible for exactly as long as they stay valid.
