## Context

This crosses three modules: `audit-core` (the envelope), `security-spring-boot-starter` (claim, actor resolver and
admin guard) and `audit-spring-boot-starter` (store and search), and it changes three shared contracts.

- `JwtPrincipalConverter` already treats `ludwig_pat` as present-or-absent, never ignored when malformed.
- `LudwigAuthentication` carries `Credential` for exactly this kind of per-request fact.
- `AdminSurfaceGuard` is the `HandlerInterceptor` every `/admin/**` request passes.
- `AuthorizationDeniedEvent` publication already produces the audited `403`.

## Goals / Non-Goals

**Goals:** see the proposal.

**Non-Goals:** see the proposal. Additionally:
- no new principal type;
- no change to `AuthorityResolver`.

## Decisions

1. **`Delegation` on `LudwigAuthentication`, not on `LudwigPrincipal`.** This is the same argument as
   `Credential`'s javadoc: who you are does not change with the channel you came through. The record is
   `Delegation(List<String> chain)`, where `actingParty()` returns the head. It is `Optional` from an accessor, never
   a field (the Optional rule).
2. **Claim parsing.** The parsing lives in a `DelegationClaims` helper beside `PatClaims`.
   - A missing claim yields no delegation.
   - An object with a string `sub` yields a delegation, following nested `act` up to depth 5. Deeper nesting is
     refused, because an unbounded chain is a parsing denial-of-service.
   - Anything else is an `InvalidBearerTokenException`, which becomes a `401` through the existing entry point.
   - `ludwig.security.jwt.actor-claim` sets the claim name. A blank value disables the feature entirely.
3. **`Actor.via` as a record component.** `Actor` is the envelope, and a value derived from attributes could not
   be searched. `PrincipalActorResolver` fills it from the current `LudwigAuthentication`.

   The factories keep their signatures and pass `null`. This breaks only callers of the canonical constructor;
   `AuditSinkConformanceTest` and `AuditEventEntity` are the in-repo ones, and they are updated.
4. **The policy lives in `AdminSurfaceGuard`, after authentication and before `@AdminAccess`.** This makes it
   surface-wide, which covers `/admin/v1/me/operations` too: a UI that cannot discover its controls through itself
   is misconfigured. It applies only when `principal.type() == USER`. A denial goes through the same
   `AuthorizationDeniedEvent` path, so it is audited and metered with no new code.
5. **Store and search.** Migration `0006-actor-via.sql` adds `actor_via VARCHAR(255)` with a partial index
   `WHERE actor_via IS NOT NULL`. `AuditEventQueryRepositoryImpl` gains one predicate. Tenant confinement is
   unchanged, because it is applied before filters.

**Dependency-direction check.**
- Does `audit-core` gain an in-repo dependency? No.
- Does `security-spring-boot-starter` gain one? No; it already depends on `audit-core`.
- Does `audit-spring-boot-starter` gain one? No; it already depends on `audit-core` and
  `security-spring-boot-starter`.

No edge is added, so no cycle is possible.

**POMs.** No POM changes: not the root `pom.xml`, `ludwig-bom` or `ludwig-service-parent`.

**Mechanical checks.** There is no new structural convention. "Roles never from claims" remains enforced as it is
today: `JwtPrincipalConverter` never maps claims to authorities, and the delegation claim is read only into
`Delegation`. A unit test asserts that a token carrying `act.roles` gains no authority. revapi records the `Actor`
constructor change as an accepted difference in the root `pom.xml` analysis configuration. That is a
`<revapi.differences>` entry, not a POM-structure change.

## Risks / Trade-offs

- [An identity provider emits `act` for an unrelated purpose and now fails requests with `401` because it is
  malformed for RFC 8693.] → `actor-claim` can be set to blank or renamed. The admin guide states that this is the
  first thing to check.
- [`required-via` locks out an administrator during a UI outage.] → It is configuration, and a deployment can
  clear it. The guide recommends keeping a break-glass service principal, which the policy never affects.
