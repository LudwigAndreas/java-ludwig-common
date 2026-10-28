## ADDED Requirements

### Requirement: The actor records the party that acted for them
An audit event's actor SHALL carry `via`, the subject of the immediate acting party named by the request's validated
delegation claim (`ludwig.security.jwt.actor-claim`, default `act`, read as RFC 8693 `act.sub`). It SHALL be
empty when the request carried no such claim. `via` SHALL be distinct from `on_behalf_of`, which keeps its meaning.
A token whose delegation claim is present but unreadable SHALL be refused with `401`.

#### Scenario: An administrator acts through the administrative UI
- **WHEN** alice, with a token whose `act.sub` is `admin-console`, discards an outbox message
- **THEN** the audit event's actor subject is `alice`, its `via` is `admin-console`, and its `on_behalf_of` is empty

#### Scenario: The claim name follows the identity provider
- **WHEN** `ludwig.security.jwt.actor-claim` is `azp_actor` and a token carries `azp_actor.sub` = `ops-portal`
- **THEN** events recorded for that request carry `via` = `ops-portal`

#### Scenario: A malformed delegation claim
- **WHEN** a token carries `act` as the string `admin-console` rather than an object with `sub`
- **THEN** the request is refused with `401`
