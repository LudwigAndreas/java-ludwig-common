## ADDED Requirements

### Requirement: PAT failures enter the existing pipeline as contributed mappers

`pat-spring-boot-starter` SHALL contribute exception mappers into `web-core`'s single RFC 9457
pipeline and SHALL NOT declare a `@RestControllerAdvice` of its own. Every PAT problem document's
`title` and `detail` SHALL resolve from the module's own i18n bundle, present in both locales with
identical key sets.

#### Scenario: A PAT management request is rejected
- **WHEN** issuance is refused for a lifetime beyond the ceiling, an empty scope set, a scope the
  owner does not hold, or an owner the caller may not act for
- **THEN** the response is a localized `ProblemDetail` from the shared pipeline, with a distinct
  `type` per failure so a client can branch on it without parsing prose

#### Scenario: The module is added to an application that already has its own advice
- **WHEN** the application declares its own advice or its own mapper for the same exception
- **THEN** the application's mapper wins, by the existing precedence requirement - adding this module
  does not change how a consumer overrides a library

### Requirement: A PAT-credentialed caller on the management surface is `403`, not `401`

A request authenticated by a PAT-derived assertion that reaches a PAT management endpoint SHALL
produce a `403` with a problem `type` distinct from an ordinary authorization denial.

The caller is authenticated and their identity is established; what is refused is the *credential*.
A `401` would tell a client to re-authenticate, which they can do successfully and still be refused -
a loop. The distinct type exists so a client library can report "this endpoint cannot be used with a
token" rather than "access denied", which is the difference between a five-minute fix and a support
ticket.

#### Scenario: A PAT-backed caller calls the issuance endpoint
- **WHEN** the request's credential is a PAT and the endpoint is a PAT management endpoint
- **THEN** the response is `403` with the credential-not-permitted problem type, and its `detail`
  names the restriction in the caller's locale

#### Scenario: The same caller lacks the authority as well
- **WHEN** the caller would also fail ordinary authorization
- **THEN** the credential refusal is what is reported, because it is evaluated first and is the
  actionable one - telling the caller to acquire an authority they cannot use with this credential
  would send them down the wrong path

### Requirement: An exchange failure body carries no reason

The problem document returned by a failed token exchange SHALL be identical for every cause, and
SHALL NOT carry a field, code or message distinguishing an unknown key id from a bad secret, a failed
checksum, a revoked token, an expired token or a refused source.

This is a deliberate departure from the pipeline's usual helpfulness and the departure is the point.
The pipeline's existing requirement that a problem body never leaks operational detail by default is
the same instinct; here it is absolute, because any distinguishable failure on this endpoint is an
oracle for an attacker testing a credential guess. The reason is tagged in metrics and recorded in
audit, where it costs the defender nothing.

#### Scenario: Two different exchange failures are compared
- **WHEN** an unknown key id and a known key id with a wrong secret are both presented
- **THEN** the two response bodies and statuses are identical

#### Scenario: An operator needs the reason
- **WHEN** exchange failures are examined in metrics or audit
- **THEN** the cause is present there, tagged, for every failure the uniform response concealed

### Requirement: A rejected credential has a problem type distinct from a missing authority

The pipeline SHALL define a problem `type` meaning "the presented credential was not accepted", and
that type SHALL be distinct from the type used when an authenticated caller lacks an authority. The
credential-rejected document SHALL carry the location at which the caller can manage their
credentials. Its constant SHALL live in `pat-core`, so that the edge - which returns this document on
the issuer's behalf and is not part of this repository - configures against a definition rather than
inventing one.

The two cases demand opposite actions from an automation client. A rejected credential means reissue
the token; a missing authority means request a role. A client that cannot distinguish them sends its
user down the wrong path, and for an unattended pipeline the wrong path is a silent stall.

#### Scenario: A client receives a credential rejection
- **WHEN** a PAT cannot be exchanged for any reason
- **THEN** the document carries the credential-rejected type and the management location, and
  nothing identifying which cause applied

#### Scenario: A client receives an authorization denial
- **WHEN** a PAT-backed request is authenticated but the owner lacks the required authority
- **THEN** the document carries the ordinary authorization-denied type, not the credential-rejected
  one - the credential worked, and reissuing it would change nothing

#### Scenario: The type constants are consumed from outside this repository
- **WHEN** the edge is configured to render the credential-rejected response
- **THEN** the type and the management location are read from `pat-core`'s published constants, which
  both sides of the seam share for the same reason the `ludwig_pat` claim name is defined once
