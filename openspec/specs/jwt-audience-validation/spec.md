# JWT audience validation

## Purpose
A resource server that skips the audience check accepts any token its issuer minted for any other
service. This capability states that a configured audience is always enforced.

## Requirements

### Requirement: A configured audience is enforced whatever the decoder is built from
When `ludwig.security.jwt.audiences` is not empty, a bearer token whose `aud` claim contains none
of the configured audiences SHALL be rejected with 401. This SHALL hold when the decoder is
configured by `issuer-uri`, by `jwk-set-uri`, or supplied by the application as a bean.

#### Scenario: The decoder is configured by issuer-uri
- **WHEN** a service is configured with `spring.security.oauth2.resourceserver.jwt.issuer-uri` and
  `ludwig.security.jwt.audiences=notification-service`, and a request carries a validly signed
  token whose audience is `some-other-service`
- **THEN** the service answers 401

#### Scenario: The token carries a configured audience
- **WHEN** the same service receives a validly signed token whose audience is
  `notification-service`
- **THEN** the token is accepted

#### Scenario: The token carries no audience
- **WHEN** the same service receives a validly signed token with no `aud` claim
- **THEN** the service answers 401

#### Scenario: No audience is configured
- **WHEN** `ludwig.security.jwt.audiences` is empty
- **THEN** no audience check is added by this module, as before

### Requirement: The service says at start-up that the check is in force
When an audience check is installed, the service SHALL log once which audiences it enforces.

#### Scenario: A service starts with an audience configured
- **WHEN** a service configured by `issuer-uri` with one audience starts
- **THEN** its log contains one line naming that audience as enforced
