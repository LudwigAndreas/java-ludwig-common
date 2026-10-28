## ADDED Requirements

### Requirement: People can be required to arrive through a sanctioned acting party
When `ludwig.admin.human-callers.required-via` is non-empty, a request to the administrative surface by a `USER`
principal SHALL be refused with `403` unless its validated token names, as the immediate acting party, one of the
listed subjects. The refusal SHALL be audited as an administrative denial. Service, partner and system principals
SHALL NOT be affected. When the list is empty, no acting party SHALL be required.

#### Scenario: A person calls with a token from the administrative UI
- **WHEN** `required-via` is `[admin-console]` and alice calls `GET /admin/v1/outbox/messages` with a token whose
  `act.sub` is `admin-console` and she holds `outbox.read`
- **THEN** the response is `200`

#### Scenario: A person calls directly
- **WHEN** `required-via` is `[admin-console]` and alice calls the same endpoint with a token that has no `act`
  claim
- **THEN** the response is `403`, and the audit trail records the denial with alice as actor and no acting party

#### Scenario: Automation is unaffected
- **WHEN** `required-via` is `[admin-console]` and a service principal holding `outbox.read` calls the endpoint
  without an `act` claim
- **THEN** the response is `200`
