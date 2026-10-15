## Purpose

The one implementation of the outbound credentials every client starter needs - a cached machine
token renewed before it expires and minted once however many calls ask, and the caller's own token
for relay - so that the HTTP and gRPC clients cannot drift apart in how they authenticate.

## ADDED Requirements

### Requirement: A machine token is minted once and renewed before it expires
For a client using the OAuth2 client-credentials grant, a token SHALL be shared by every call until
it is within the client's refresh skew of expiry; then exactly one new token SHALL be minted however
many calls need one at that moment. This SHALL hold identically for HTTP clients and gRPC clients.

#### Scenario: Concurrent HTTP calls at expiry
- **WHEN** 200 calls through one REST client start as its machine token passes into the refresh
  skew
- **THEN** the token endpoint is called exactly once and every call sends the new token

#### Scenario: Concurrent gRPC calls at expiry
- **WHEN** 200 calls through one gRPC client start as its machine token passes into the refresh skew
- **THEN** the token endpoint is called exactly once and every call sends the new token

#### Scenario: A peer rejects a cached token
- **WHEN** a peer rejects the cached token and the client asks for a fresh one
- **THEN** a new token is minted, and callers that asked at the same moment receive that token
  rather than causing a second mint

#### Scenario: The token endpoint refuses
- **WHEN** the token endpoint answers an error
- **THEN** the call fails as an authentication failure naming the client and the registration, and
  nothing is sent to the peer

### Requirement: Relay sends the caller's own token or nothing
A client configured to relay SHALL send the bearer token of the request it is serving, and SHALL
fail the call before sending anything when that request carries no token.

#### Scenario: A REST client relays inside an HTTP request
- **WHEN** a relay REST client is called while serving an HTTP request authenticated with a bearer
  token
- **THEN** the outbound request carries that token

#### Scenario: A relay client is called with no inbound token
- **WHEN** a relay client of either kind is called outside an authenticated request
- **THEN** the call fails as an authentication failure and no request is sent

### Requirement: The shared token types are the only ones
No client starter SHALL declare a token cache, a client-credentials minter or an inbound-token
reader of its own. The removal of `rest-client-spring-boot-starter`'s previously published token
types in 3.0.0, without a prior deprecation, SHALL be recorded as a justified exception in the
API-compatibility configuration rather than passing unremarked.

#### Scenario: A client starter declares its own token cache
- **WHEN** a class in a client starter caches tokens or mints client-credentials tokens itself
- **THEN** that module's architecture test fails, naming the class and the shared type to use

#### Scenario: The 3.0.0 API check meets the removed types
- **WHEN** the API-compatibility check compares `rest-client-spring-boot-starter` 3.0.0 with its
  2.x baseline
- **THEN** the removal of its two token types is reported as an accepted difference whose
  justification names this change and the owner's decision, and any other removal still fails
