# outbound-token-core

The one implementation of the credentials an outbound client sends, shared by
`rest-client-spring-boot-starter` and `grpc-client-spring-boot-starter`:

| Type | What it is |
|---|---|
| `CachingTokenSource` | A machine token cached until `skew` before it expires, minted **once** however many callers need a new one at the same moment (single-flight), and minted again on a forced refresh after a peer rejects it - without a second mint for the callers rejected at the same time. Blocking `get(force)` and non-blocking `getAsync(force)`. |
| `CachedToken` | A token and its expiry. A token with no stated expiry is treated as already expired: "never expires" is how a revoked token gets used for weeks. |
| `ClientCredentialsTokenMinter` | The OAuth2 client-credentials grant of a `spring.security.oauth2.client.registration`, optionally with narrowed scopes. A refusal is a `TokenMintingException` naming the registration and the OAuth2 error code, never a credential. |
| `InboundBearerToken` | The bearer token of the request being served, for relay - from the security context (`current()`) or from an `Authentication` found elsewhere (`of(...)`). `null` when there is none. |

Each client starter keeps what is specific to its transport: how a token is attached (an HTTP header,
gRPC `CallCredentials`), its counters, its configuration keys and its exceptions. A
`TokenOwnershipTest` in each starter fails the build if it grows a second cache, minter or reader.

## Why a library of its own

Until 3.0.0 the cache existed twice - in `rest-client` and in `grpc-client` - and a fix to one was a
fix to one. It cannot live in either starter: the gRPC client would then carry three HTTP engines, and
the REST client a gRPC transport.

## Dependencies

None in this repository, on purpose: both client starters depend on it, and `ModuleIndependenceTest`
fails the build if a platform type is ever referenced from here. `slf4j-api` is the only required
dependency; `spring-security-oauth2-client` (for the minter) and
`spring-security-oauth2-resource-server` (for the relay reader) are optional, so a service whose
clients use a static token or none loads neither.

## Migrating from 2.x

`ru.ludwigandreas.restclient.auth.CachedToken` and `CachingTokenSource` are removed in 3.0.0 without a
deprecation release, by decision; import `ru.ludwigandreas.outboundtoken.CachedToken` and
`ru.ludwigandreas.outboundtoken.CachingTokenSource` instead. Behaviour and constructor are unchanged.
