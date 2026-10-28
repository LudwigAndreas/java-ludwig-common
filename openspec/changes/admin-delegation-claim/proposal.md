## Why

When a person administers a service through an administrative UI, the audit trail records only the person, for
example `alice`. It cannot tell "alice, through admin-console" from "alice, with a token she pasted into curl".
Regulated deployments want both of these:

- the channel recorded on every administrative event;
- the option to require that people reach `/admin/**` only through the sanctioned UI, while service principals
  (automation) keep calling directly.

The identity provider's token-exchange (RFC 8693) expresses the channel as the `act` claim (`act.sub` =
`admin-console`). The audit envelope already distinguishes actor from `on_behalf_of`, but `on_behalf_of` means
"the subject the action was for" (an administrator editing Bob's settings), not the channel, so reusing it would
corrupt existing queries. This was out of scope in `admin-authorization`
(`openspec/intents/admin-surface-follow-ups.md` §2).

## What Changes

- **Reading the claim.** `security-spring-boot-starter` reads a delegation claim from a JWT and records the acting
  party on the per-request `LudwigAuthentication`, not on `LudwigPrincipal` (the same reasoning as `Credential`).
  It is configured through `ludwig.security.jwt.actor-claim`:
  - the default is `act`, read as RFC 8693 (`act.sub`, with nested `act` kept as a chain);
  - an empty value turns the feature off.

  The claim name follows the identity provider's configuration; nothing is hard-coded to one provider. The claim
  carries identity, never roles: roles still come only from the `AuthorityResolver`.
- **Recording it.** `audit-core`'s `Actor` gains a `via` component: the immediate acting party, for example
  `admin-console`. `PrincipalActorResolver` fills it in. `audit-spring-boot-starter` stores it in a new
  `audit_event.actor_via` column (migration `0006`), and `GET /admin/v1/audit/events` gains a `via` filter.
- **Requiring it.** A new property, `ludwig.admin.human-callers.required-via` (a list, empty by default), sets a
  policy on the administrative surface. When it is non-empty:
  - a `USER` principal whose request carries no acting party in the list is refused `403`, and the refusal is
    audited like every other administrative denial;
  - service principals are unaffected.
- **BREAKING**: `Actor`'s canonical constructor gains `via`. The `Actor.of(...)` factories are unchanged.
- **BREAKING**: a token whose `act` claim is present but unreadable (not an object, or no `sub`) is refused `401`,
  matching the platform's rule for the `ludwig_pat` claim: present-but-unreadable is rejected, not ignored.

## Capabilities

### New Capabilities
None.

### Modified Capabilities
- `admin-authorization`: an optional requirement that people arrive through a sanctioned acting party.
- `audit-envelope`: the actor records the acting party.
- `audit-trail-search`: the trail can be filtered by acting party.

## Non-goals

- Performing token exchange. The UI and the identity provider do that, and this platform only reads the result.
- Authorizing by acting party anywhere outside the administrative surface.
- Trusting an acting party carried in a header. Only a validated token's claim counts.
- Recording the full delegation chain in the audit envelope. Only the immediate acting party goes in `via`; the
  chain stays available on the authentication object.
- mTLS and PAT callers. Neither carries an `act` claim, so for them `via` is always empty.

## Impact

- Modules, all libraries (parent `common`), with their in-repo dependents (the gate runs the union):
  - `audit-core`: 15 dependents, among them every starter that audits, plus `security-spring-boot-starter`
    and `audit-spring-boot-starter`.
  - `security-spring-boot-starter`: 16 dependents, including both services.
  - `audit-spring-boot-starter`: one dependent, `user-settings-spring-boot-starter`.

  `scripts/gate.sh --change admin-delegation-claim audit-core security-spring-boot-starter
  audit-spring-boot-starter` computes the union.
- Shared contracts: `admin-authorization`, `audit-envelope` and `audit-trail-search` are modified.
- Migration `0006-actor-via.sql` (audit).
- No POM changes.
- README (EN and RU) of all three modules, `docs/admin-guide(.ru).md` (the identity-provider setup for `act`), and
  `CHANGELOG` (EN and RU).
