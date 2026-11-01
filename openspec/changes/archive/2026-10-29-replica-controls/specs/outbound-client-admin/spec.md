## Purpose

Lets an operator inspect a service's named outbound HTTP clients, force fresh machine tokens, and read or set their
circuit breakers on every replica, without a restart.

## ADDED Requirements

### Requirement: Named clients are listable without credentials
Where the replica-control channel and the administrative surface are present, `rest-client-spring-boot-starter` SHALL
declare `restclient.read`, `restclient.operate` and `restclient.privileged`, and `GET /admin/v1/restclient/clients`
(`restclient.read`) SHALL list every named client with its base URL host, auth kind and resilience settings. No
secret, token or credential SHALL appear.

#### Scenario: Listing clients
- **WHEN** an operator lists clients in crud-service-example
- **THEN** each named client appears once, and no client secret or token appears in the response

### Requirement: A client's machine token can be reset everywhere
The replica control `restclienttokenreset` with `{client}` (`restclient.operate`) SHALL discard the cached token of a
client-credentials client on every targeted replica, so that its next call mints a new one. A client that does not
use client credentials, or an unknown client, SHALL be `400`.

#### Scenario: The identity provider rotated its signing key
- **WHEN** an operator resets the token of client `partner` in a service with two replicas
- **THEN** the command succeeds on both, and the next call through `partner` on each replica carries a newly minted
  token

### Requirement: Circuit breakers are readable and settable per replica
The replica control `restclientbreakers` (`restclient.read`) SHALL return, from every targeted replica, each circuit
breaker's name, state and failure rate. The control `restclientbreaker` with `{client, state}` (`restclient.privileged`,
reason required), with `state` one of `CLOSED`, `FORCED_OPEN` or `DISABLED`, SHALL set that client's breaker on
every targeted replica.

#### Scenario: A partner is back but one replica's breaker is still open
- **WHEN** an operator reads the breakers
- **THEN** the command resource shows each replica's own state, including the one replica that is `OPEN`

#### Scenario: Taking a misbehaving partner out of service
- **WHEN** an operator holding `restclient.privileged` sets client `partner` to `FORCED_OPEN` with a reason
- **THEN** every replica refuses calls through `partner` without contacting it, and the audit event records the
  reason

#### Scenario: Forcing needs the privileged tier
- **WHEN** a caller holding only `restclient.operate` submits `restclientbreaker`
- **THEN** the response is `403`
