# outbound-token-core

***English** · [Русский](README.ru.md)*

The one implementation of the credentials an outbound client sends, shared by
[`rest-client-spring-boot-starter`](../rest-client-spring-boot-starter) and
[`grpc-client-spring-boot-starter`](../grpc-client-spring-boot-starter): a cached machine token minted once
however many callers need it, the OAuth2 client-credentials mint, and the caller's bearer token for relay.

| | |
|---|---|
| Role | library |
| Depends on (in-repo) | nothing |
| Used by | `grpc-client`, `rest-client` starters |
| Surfaces | outbound OAuth2 token endpoint (through the minter) |

## Usage

The client starters use this module; a service normally does not depend on it directly.

```xml
<dependency>
    <groupId>ru.ludwigandreas</groupId>
    <artifactId>outbound-token-core</artifactId>
</dependency>
```

| Type | What it is |
|---|---|
| `CachingTokenSource` | A machine token cached until `skew` before expiry, minted **once** however many callers need a new one at the same moment (single-flight), and re-minted on a forced refresh after a peer rejects it - without a second mint for callers rejected at the same time. Blocking `get(force)` and non-blocking `getAsync(force)` |
| `CachedToken` | A token and its expiry. A token with no stated expiry is treated as already expired |
| `ClientCredentialsTokenMinter` | The OAuth2 client-credentials grant of a `spring.security.oauth2.client.registration`, optionally with narrowed scopes. A refusal is a `TokenMintingException` naming the registration and the OAuth2 error code, never a credential |
| `InboundBearerToken` | The bearer token of the request being served, for relay: `current()` from the security context, or `of(authentication)`. `null` when there is none |

Each client starter keeps what is transport-specific: how a token is attached (HTTP header, gRPC
`CallCredentials`), counters, configuration keys and exceptions.

## Configuration

None of its own. Token sources are configured through each client starter's per-client `auth` block, and the
minter uses standard `spring.security.oauth2.client.registration.<id>.*` / `provider.<id>.*` properties.

## Deployment

The OAuth2 token endpoint of each registration must be reachable; client secrets come from the secret store
into `spring.security.oauth2.client.registration.<id>.client-secret`.

Dependencies: only `slf4j-api` is required. `spring-security-oauth2-client` (minter) and
`spring-security-oauth2-resource-server` (relay reader) are optional.

## Observability

None here: token refresh counters are published by the client starters
(`ludwig.restclient.*`, `ludwig.grpcclient.auth.token.refreshes`).

## Audit

Not applicable. Outbound calls are audited by the client starters (`outbound-call` category).

## Runbook

| Symptom | Likely cause | What to do |
|---|---|---|
| `TokenMintingException` naming a registration and `invalid_client` | Wrong client id/secret | Fix the registration's credentials in the secret store |
| `TokenMintingException` with `invalid_scope` | Narrowed scopes not granted to the client | Grant the scopes at the authorization server or narrow less |
| Calls fail with 401 right after a token rotation | Peer rejects a cached token | Handled: a 401 forces one re-mint. If it persists, the minted token itself is rejected - check the audience and scopes |
| Relay sends no token | No inbound bearer token (scheduled job, Kafka listener) | Use a machine token for that client instead of relay |

## Testing

`CachingTokenSourceTest` (single-flight, skew, forced refresh, missing expiry), `ClientCredentialsTokenMinterTest`,
`InboundBearerTokenTest`, and `ModuleIndependenceTest` (no platform type referenced). Each client starter has a
`TokenOwnershipTest` that fails if it grows a second cache, minter or reader.

## Design decisions

- **A library of its own**: in either starter it would drag the other's transport in (three HTTP engines into the
  gRPC client, or gRPC into the REST client).
- **No expiry means expired**: "never expires" is how a revoked token gets used for weeks.
- **No in-repo dependencies**, enforced by `ModuleIndependenceTest`.
