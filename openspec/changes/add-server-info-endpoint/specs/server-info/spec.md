## Purpose

Defines the short, curated description of a running service that a user interface may show - which
service answered, at which version, in which environment, built from which commit and when - and
fixes the set of fields it may carry, so that the same document is safe to serve to an anonymous
caller and never grows into a copy of the operator-facing management endpoint.

## ADDED Requirements

### Requirement: A service describes itself at a well-known path

A servlet service that includes the platform's observability starter SHALL answer `GET /server/info`
on its application port with status 200 and a JSON object describing the running process.

The path SHALL be the same in every service and SHALL NOT be configurable. A user interface that
calls several services needs one path it can append to each of them, and a path prefix in front of a
service belongs to whatever routes to it.

The values SHALL be those of the build identity and the service identity the process already
resolved at startup. Producing the response SHALL NOT read the source repository, start a process or
call another service.

#### Scenario: A fully provisioned service is asked

- **WHEN** `GET /server/info` is sent to a service packaged by the platform's service parent from a
  git checkout, with its name, version and environment configured
- **THEN** the response status is 200 and the content type is `application/json`
- **AND** the body carries `service`, `version`, `environment`, `commit` and `built`

#### Scenario: The values agree with what the service logs at startup

- **WHEN** the response is compared with the service's startup identity event
- **THEN** `service`, `version` and `environment` are identical to that event's
- **AND** `commit` is that event's commit in its abbreviated form

#### Scenario: A service with no servlet container

- **WHEN** an application that is not a servlet web application includes the observability starter
- **THEN** it starts normally and registers no handler for `/server/info`

### Requirement: The document is a fixed allow-list

The response body SHALL contain no member other than `service`, `version`, `environment`, `commit`
and `built`.

`commit` SHALL be the abbreviated commit id. `built` SHALL be a calendar date in `YYYY-MM-DD` form,
taken in UTC: the platform records the build time at day precision, so a time of day would be a
value that was never measured.

The branch name, the dirty-tree indicator, the CI build number, the full commit id, the service
namespace and the instance identifier SHALL NOT appear. The first three are operator facts already
available on the management endpoint; the instance identifier names a replica, which is deployment
topology. Keeping them out is what lets a service serve this document without authentication.

#### Scenario: Operator-only provenance is withheld

- **WHEN** `GET /server/info` is sent to a service whose build identity carries a branch, a dirty
  indicator, a CI build number and a full commit id
- **THEN** the response body contains none of those four values
- **AND** it contains no member naming the namespace or the instance

#### Scenario: The build date carries no time of day

- **WHEN** the service's build time is recorded as `2026-09-16T00:00:00Z`
- **THEN** `built` is `2026-09-16`

#### Scenario: A field is added to the build identity later

- **WHEN** the resolved build identity gains a new field
- **THEN** the response body is unchanged until this requirement is changed to name it

### Requirement: An unresolved value is absent

Each member SHALL be resolved independently, and a value that could not be resolved SHALL be omitted
from the body. It SHALL NOT be written as `null`, as an empty string or as a placeholder such as
`unknown`.

A process with no resolved identity at all SHALL still answer 200, with an empty JSON object.

#### Scenario: A process started from an IDE

- **WHEN** `GET /server/info` is sent to a service started without packaged build provenance
- **THEN** the response status is 200
- **AND** the body has no `commit` and no `built` member
- **AND** the members that were resolved are present

#### Scenario: Nothing was resolved

- **WHEN** the service has no name, version, environment or build provenance
- **THEN** the response status is 200 and the body is `{}`

### Requirement: The endpoint can be switched off and decides nothing about access

Setting `ludwig.observability.server.info.enabled` to `false` SHALL remove the endpoint, so that
`GET /server/info` is answered as any unmapped path is. The default SHALL be `true`. Switching the
observability starter off as a whole SHALL remove it as well.

The endpoint SHALL NOT itself decide who may call it. Whether it is reachable without authentication
is decided by the service's own public-path configuration.

#### Scenario: Switched off

- **WHEN** a service starts with `ludwig.observability.server.info.enabled=false`
- **THEN** `GET /server/info` is answered with status 404

#### Scenario: A service declares it public

- **WHEN** a service that lists `/server/info` among its public paths receives the request with no
  credentials
- **THEN** the response status is 200

#### Scenario: A service does not declare it public

- **WHEN** a service that requires authentication and does not list `/server/info` among its public
  paths receives the request with no credentials
- **THEN** the response status is 401
