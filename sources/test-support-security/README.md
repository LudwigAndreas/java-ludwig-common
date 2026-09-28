# test-support-security

***English** · [Русский](README.ru.md)*

The domain-free half of this platform's security test fixtures: a builder that mints a `LudwigPrincipal`
and wraps it in an authentication a `MockMvc` request can carry, and the `JwtDecoder` that lets a context
start without OIDC discovery. Each service keeps its own constants naming its own roles and subjects.

## Why this is a second module

It looks like over-engineering until you try the alternative.

Maven builds its reactor DAG from **all** declared dependencies, whatever their scope - `test`,
`provided` and `optional` included. These fixtures need `LudwigPrincipal`, so they must depend on
`security-spring-boot-starter`. But [`test-support`](../../sources/test-support/README.md) has to be consumable by
*every* module, including the most upstream ones: `db-core` and `audit-core` both have container tests. If
the two halves were one artifact, that artifact would depend on `security-spring-boot-starter` while being
depended on by `db-core` - and any test-scoped container dependency added to the security starter would
close a cycle that fails the entire reactor, not just the module that added it.

A single merged module would in fact build today, because `security-spring-boot-starter` currently has
only unit tests and so declares no dependency back. That is a coincidence, not a design: the cycle appears
the first time somebody gives the security starter a container test, and the fix at that point is this
same split, done under time pressure with a red reactor. So it is done now, while it is free.

The split is also the honest description of the two halves. The container half is framework infrastructure
that knows nothing about this platform's domain; this half knows what a principal is.

## What you get

| Type | What it is |
|---|---|
| `TestPrincipalBuilder` | `user(...)` / `partner(...)` / `service(...)`, then roles, tenant, permissions, attributes → a `LudwigPrincipal`, a `LudwigAuthentication`, or a `RequestPostProcessor` |
| `RejectingJwtDecoderConfiguration` | A `@TestConfiguration` whose `JwtDecoder` refuses every token |
| `DefaultCallerConfiguration` | Optional: a `MockMvcBuilderCustomizer` defaulting every request to one caller |

## Switching it on

```xml
<dependency>
    <groupId>ru.ludwigandreas</groupId>
    <artifactId>test-support-security</artifactId>
    <scope>test</scope>
</dependency>
```

This brings `test-support` with it, so a service declares one dependency rather than two.

```java
@TestConfiguration(proxyBeanMethods = false)
@Import(RejectingJwtDecoderConfiguration.class)
public class TestSecurityConfiguration { }
```

and a small constants class of the service's own:

```java
final class TestPrincipals {

    static final String ADMIN_SUBJECT = "admin-subject";

    static RequestPostProcessor admin() {
        return TestPrincipalBuilder.user(ADMIN_SUBJECT).roles("ROLE_CATALOG_ADMIN").postProcessor();
    }
}
```

## Why only the builder is shared

The two services' `TestPrincipals` classes looked near-identical and were not. They share a *shape* but
diverge entirely in vocabulary: `crud-service-example` speaks of editors, watchers, a partner code and
`ROLE_CATALOG_ADMIN`; `notification-service` speaks of support agents, tenants and a peer service's SPIFFE
id. Extracting either one wholesale would have given the other service a fixture full of names from
somebody else's domain - readable to nobody, and wrong in assertions.

So what moved here is the mechanism, and each service keeps the names. `TestPrincipalBuilder` holds no
domain vocabulary and must not acquire any.

## Why the decoder rejects every token

This is the part that is easy to get backwards. The decoder Spring Boot builds from `issuer-uri` performs
OIDC discovery when the bean is created, so without a replacement a test would have to reach the identity
provider just to start the context. The replacement **rejects** rather than accepts: the tests
authenticate by injecting a principal, so no test needs a token accepted, and a decoder that silently
accepted anything would hide a genuine misconfiguration - letting a test pass against a security chain
that in production would admit unsigned tokens.

Worth knowing beyond tests: in production, prefer `jwk-set-uri`, which is fetched lazily, if a service
must be able to boot while the provider is unreachable.

## Why the default caller is opt-in

Both services need the rejecting decoder. Only `crud-service-example` defaults its requests to an admin;
`notification-service` names a caller on every request on purpose, because several of its tests are
precisely about what a caller *without* a role is refused. Bundling the default into the decoder
configuration would have quietly changed those from "anonymous is refused" to "an admin is allowed" -
a test still passing while no longer asserting anything.

So `DefaultCallerConfiguration` is separate, and the service that opts in states which caller it defaults
to. A default request is applied first and a request's own `.with(...)` after it, so a test that names a
narrower caller wins; that ordering is what makes the default safe to use at all.
