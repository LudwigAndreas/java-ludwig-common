## MODIFIED Requirements

### Requirement: A PAT is exchanged for a short-lived assertion, and services see the PAT only where a deployment deliberately allows it

The issuer SHALL expose an RFC 8693 token-exchange endpoint that accepts a PAT as the subject token and
returns a short-lived JWT. No service SHALL hold PAT secret material.

Whether a service accepts a PAT **as a request credential** is now a deployment decision rather than a
platform invariant. By default it does not, and the exchange is the only path. A deployment that enables
`ludwig.security.pat.filter.enabled` adds the `pat-direct-authentication` path deliberately, and that
capability states what it costs.

**This reverses part of the shipped requirement, and the reversal is recorded here rather than left for
code to contradict.** The original read *"No module in this repository other than
`pat-spring-boot-starter` SHALL accept a PAT as a request credential"*, with a scenario asserting that a
PAT presented to a service directly is not authenticated. That was written on the assumption of an edge
able to perform the exchange. The platform this ships to has a company-provided gateway that does
session-cookie-to-JWT only, cannot be extended, and redirects a request with no session to OIDC - so
under the original requirement a personal access token could not be used at all.

What does **not** change: no service holds the table, a digest or a key id. One table, one revocation
point. The objection that made per-service *storage* unacceptable is fully preserved; what is relaxed is
per-service *authentication*, against that one table.

#### Scenario: A client presents a PAT to a service with the filter disabled
- **WHEN** a request reaches a service with an `lpat_`-prefixed bearer credential and
  `ludwig.security.pat.filter.enabled` is at its default
- **THEN** it is not authenticated, because nothing in the service has a verifier for that format - the
  credential is rejected as an unparseable bearer token rather than partially handled

#### Scenario: A client presents a PAT to a service with the filter enabled
- **WHEN** the same request reaches a service where the deployment has enabled the filter
- **THEN** it is authenticated as the token's owner with the owner's live authorities intersected with
  the token's scopes - the same effective authority the exchange route produces, reached differently

#### Scenario: A service's storage is examined under either configuration
- **THEN** it holds no token row, no digest and no key id. This half of the original requirement is
  unchanged and is what keeps revocation single-pointed

#### Scenario: A valid PAT is exchanged
- **WHEN** a live, unexpired, unrevoked PAT is presented to the exchange from a permitted source
- **THEN** the response carries a signed JWT whose `sub` is the PAT's owner and which carries the
  `ludwig_pat` claim holding the PAT id and its scope set

#### Scenario: The exchanged assertion is verified by a service
- **WHEN** the returned assertion reaches any service running `security-spring-boot-starter`
- **THEN** it is verified by the existing resource-server chain with no additional filter, and the
  principal's authorities are the owner's live authorities intersected with the claim's scopes

### Requirement: The client sees one static header and never performs an exchange

A client holding a PAT SHALL authenticate by presenting it as an ordinary bearer credential on its
request to the service. It SHALL NOT be required to call the exchange, to track an assertion's expiry,
or to refresh anything.

**Unchanged in substance and strengthened in reach.** Under the exchange route this holds because the
edge does the work; under `pat-direct-authentication` it holds because the service does. Either way the
client sends one static header, which is the property that makes this usable from `curl`, from CI and
from an MCP client - and the reason a client-side exchange was rejected in both designs.

#### Scenario: An automation tool authenticates under either route
- **WHEN** a client sends `Authorization: Bearer lpat_...`
- **THEN** the request succeeds with the owner's live authority intersected with the token's scopes, and
  the client has made exactly one request and holds no short-lived credential

#### Scenario: The same PAT is used for months
- **THEN** no client-side change is ever required for assertion rotation, because the client never held
  an assertion

#### Scenario: A deployment migrates from the filter to the edge exchange
- **WHEN** the company gateway gains PAT support and the filter is disabled
- **THEN** no client changes, because neither route ever asked anything of the client
