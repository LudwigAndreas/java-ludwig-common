# pat-core

***English** · [Русский](README.ru.md)*

The personal-access-token (PAT) format and nothing stateful: the wire format, a masked secret carrier, the
digest, the attenuation scope set, the single definition of the `ludwig_pat` JWT claim, the introspection
response, and the problem-type constants the edge renders a rejected credential with. No storage, no Spring, no
HTTP, **no in-repo dependencies**.

| | |
|---|---|
| Role | library |
| Depends on (in-repo) | nothing |
| Used by | `pat-spring-boot-starter` (issues), `security-spring-boot-starter` (verifies) |
| Surfaces | none; ships `pat-secret-detection.properties` for secret scanners |

```
                        pat-core  (0 in-repo deps)
                         ^      ^
                         |      |
  security-spring-boot-starter  |         <- reads the claim, applies the intersection
          ^         ^           |
          |         |           |
     pat-spring-boot-starter ---+         <- stores, issues, exchanges
```

## Usage

The starters use it; a service rarely depends on it directly.

| Type | Purpose |
|---|---|
| `token.PatSecret` | The raw secret, in a type that will not print itself |
| `token.PatTokens` | Mint and parse `lpat_<keyId>_<secret>_<crc32c>` |
| `token.PatDigest` | SHA-256 and constant-time comparison |
| `token.PatDetection` | The published scanner pattern, read from the same file scanners read |
| `scope.PatAttenuation` | The scope set and its one operation: intersection |
| `claim.PatClaims` | The single definition of the `ludwig_pat` claim |
| `introspection.PatIntrospectionResponse` | RFC 7662 answer for a service asking about a token |
| `problem.PatProblemTypes` | Problem types and management location the **edge** renders |

### The invariant

A PAT is an **attenuation** of its owner's live authority:

```
effective = authoritiesOf(owner, now)  ∩  token.scopes()
```

Never a union, never a snapshot taken at issuance. The owner's authorities are resolved live, per request,
through `AuthorityLookup`, so a revoked role stops working within the authority cache TTL. `PatAttenuation`
offers no `union`, `plus`, `grant` or `withAdditional`; a test asserts reflectively that no widening method exists.

### Introspection field names

`PatIntrospectionResponse` components are named `sub`, `aud`, `exp`: with no Jackson on the classpath (zero
dependencies), the component names are the RFC 7662 wire names. It carries no secret, no digest and no key id (a
key id changes on rotation; `patId` does not).

### Scanner pattern

`src/main/resources/ru/ludwigandreas/pat/pat-secret-detection.properties` holds the detection regex, a prefix for a
cheap pre-filter, and a ready `gitleaks` rule body.

## Configuration

None.

## Deployment

**Register the scanner pattern with every secret scanner (repositories, CI logs, chat) before the first token is
issued.** A scanner added later does not scan history by default.

## Observability

Not applicable (pure library). Exchange and verification metrics are in the starters.

## Audit

Not applicable. Token lifecycle events are recorded by `pat-spring-boot-starter` (`credential` category).

## Runbook

| Symptom | Likely cause | What to do |
|---|---|---|
| A `lpat_` token appears in a repository or log | Leak | Revoke it through the management API immediately (`DELETE /api/v1/personal-access-tokens/{id}`), then rotate whatever it reached |
| Tokens with the wrong prefix or shape are rejected as malformed | Typo or truncation (CRC32C check) | Expected; the client must use the token exactly as issued |

## Testing

`PatTokenFormatTest` draws hundreds of samples (format round-trip must hold for every random draw); digest
comparison is constant-time; attenuation has a reflective "no widening method" test.

## Design decisions

- **Zero dependencies**, because both sides of the security seam need the same definition of `ludwig_pat`. A literal
  written twice fails open: a misspelled claim reads as "no PAT", and the token gets its owner's full authority.
- **SHA-256, not bcrypt/Argon2.** A KDF protects low-entropy human secrets; this one is 43 base62 characters from
  `SecureRandom`. A KDF would add tens of milliseconds to every exchange. **This reasoning depends on the generator**:
  if the secret ever becomes shorter or human-chosen, it no longer holds (stated at `PatDigest`).
- **The CRC32C checksum is not a security control.** It rejects typos and truncations without a database query;
  forgery is stopped by the digest comparison.
- **Random parts are base62**, not base64url: base64url contains the `_` separator. The alphabet is narrowed rather
  than characters substituted, so the distribution stays uniform.
- **Left to others**: storage, issuance, rotation, revocation and exchange (pat starter); intersection against typed
  authorities (security starter); validating scopes at issuance (issuer); rendering the rejection (the edge).
