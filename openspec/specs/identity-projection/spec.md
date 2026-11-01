# identity-projection Specification

## Purpose

A service's local, queryable copy of the users and roles its identity providers know, fed through one
provider-neutral change feed by any number of declared sources, so that authorization never calls a provider and a
new provider needs a feed, not a fork of the projection.
## Requirements
### Requirement: Sources are declared, and every change names one
A service SHALL declare each identity source it accepts under `ludwig.identity.sources.<name>`. A source name SHALL
be lower-case letters, digits and hyphens, starting with a letter. Every change applied to the projection SHALL name
its source. A feed naming a source that is not declared, or a declared name outside the grammar, SHALL fail
startup, naming the source. A declared source that no feed serves SHALL be logged once at startup, and the service
SHALL start.

#### Scenario: A feed names an undeclared source
- **WHEN** a feed is configured for source `staff` and `ludwig.identity.sources.staff` is not declared
- **THEN** the application fails to start, naming `staff` and the declared sources

#### Scenario: A declared source without a feed
- **WHEN** `ludwig.identity.sources.customers` is declared and no feed serves it
- **THEN** the application starts, and the startup log names `customers` as a source no feed serves

### Requirement: A change carries the user's complete state
An upsert SHALL carry the user's subject and profile. A role set it carries SHALL be the complete set and SHALL
replace the roles the projection holds for that user, never applied as a delta; an empty set withdraws every role.
A profile field the upsert leaves unset, including the role set, SHALL keep its stored value, because providers send
partial profiles. A disable SHALL set the user's status to disabled, and a
remove SHALL set it to removed; neither SHALL delete the row, so audit records and references to the subject keep
resolving. A change with a blank subject SHALL be discarded and counted.

#### Scenario: A role is withdrawn
- **WHEN** a user holds `ORDER_AGENT` and `CATALOG_ADMIN` and an upsert for them carries only `ORDER_AGENT`
- **THEN** the user holds only `ORDER_AGENT`

#### Scenario: An upsert without a tenant
- **WHEN** a user is stored with tenant `t-1` and an upsert for them carries no tenant
- **THEN** the user's tenant is still `t-1`

#### Scenario: A user is removed upstream
- **WHEN** a remove is applied for a user
- **THEN** the user's row remains with status removed, and the user's calls are refused as for a disabled user

### Requirement: Changes are idempotent and ordered by when they happened
Each change SHALL carry the time it happened upstream. A change for a user whose stored state happened at the same
time or later SHALL be skipped and counted as stale, not applied and not treated as an error. Applying the same
change twice SHALL leave the same state as applying it once.

#### Scenario: A late event arrives after a newer one
- **WHEN** an upsert that happened at 10:00 granting `CATALOG_ADMIN` arrives after the user's 10:05 upsert without it
- **THEN** the user does not hold `CATALOG_ADMIN`, and the change is counted with outcome `stale`

#### Scenario: A change is replayed
- **WHEN** the same upsert is applied twice
- **THEN** the user's row is identical after both applications

### Requirement: A subject belongs to one source
A user SHALL belong to the source that first projected them. A change for that subject from any other source SHALL
NOT be applied; it SHALL be counted with outcome `foreign-source` and logged at error level naming both sources.

#### Scenario: Two providers claim one subject
- **WHEN** subject `u-1` was projected from `staff` and a change for `u-1` arrives from `customers`
- **THEN** `u-1`'s row is unchanged, and the change is counted with outcome `foreign-source`

### Requirement: Provider roles are mapped per source
A source MAY declare a role mapping from a provider role or group to one or more platform roles, and whether roles
it does not map are kept or dropped (kept by default). A mapped role SHALL be replaced by its targets; role names
SHALL be normalised so that `ROLE_X` and `X` are the same role. The mapping SHALL be applied to every change of
that source, whatever feed delivered it.

#### Scenario: A group maps to two platform roles
- **WHEN** source `staff` maps `okta-catalog-admins` to `CATALOG_ADMIN` and `ORDER_AGENT`, and an upsert from
  `staff` carries `okta-catalog-admins`
- **THEN** the user holds `CATALOG_ADMIN` and `ORDER_AGENT`, and not `okta-catalog-admins`

#### Scenario: Unmapped roles are dropped
- **WHEN** source `customers` drops unmapped roles and an upsert carries a role it does not map
- **THEN** the user does not hold that role

### Requirement: A full sweep disables the users a source no longer lists
A feed that can list a source completely SHALL be able to run a sweep: every active user of that source that the
sweep did not list SHALL be disabled when the sweep completes, and users of other sources SHALL be untouched. A
sweep that would disable more than `ludwig.identity.sweep.max-disable-ratio` (default `0.1`) of the source's active
users SHALL be aborted with no user changed, logged at error level and counted with outcome `aborted`. An abandoned
sweep SHALL change nothing.

#### Scenario: A user deleted while no event was delivered
- **WHEN** source `staff` has 100 active users and a sweep lists 99 of them
- **THEN** the unlisted user is disabled, and the other 99 are unchanged

#### Scenario: A user omitted once is listed again
- **WHEN** a sweep disabled a user it did not list, and a later upsert for that user arrives whose time is older than
  the user's last applied change
- **THEN** the upsert is applied and the user is active again, because a sweep's verdict rests on absence, not on a
  change at the source

#### Scenario: The provider answers with an empty listing
- **WHEN** a sweep of a source with 100 active users lists none
- **THEN** the sweep is aborted, every user stays active, and the sweep is counted with outcome `aborted`

#### Scenario: Another source is untouched
- **WHEN** a sweep of `staff` completes
- **THEN** no user of `customers` changes status

### Requirement: A change is visible on the next request after it commits
A change that alters a user's roles or status SHALL evict that user's cached authorities after its transaction
commits, so the user's next request is authorized on the new state. The authority cache's expiry SHALL remain the
bound for a change the projection never received.

#### Scenario: A role is revoked
- **WHEN** an upsert withdrawing `CATALOG_ADMIN` from a user commits
- **THEN** the user's next request needing `CATALOG_ADMIN` is refused

### Requirement: Disabling a user is announced
A change that sets a user's status to disabled or removed - from any feed, and from a sweep - SHALL announce the
disabled principal to the rest of the application after it commits, so state keyed on the subject (such as personal
access tokens) can be revoked.

#### Scenario: A sweep disables a user who holds a personal access token
- **WHEN** a sweep disables a user who holds a live personal access token
- **THEN** that token is revoked

### Requirement: Contact data is projected only where enabled
Contact fields (e-mail, alternate e-mail, phone, chat handle and their verification flags) SHALL be stored only with
`ludwig.identity.contact.enabled=true`, and SHALL be cleared from a user's row on that user's next change when it is
off.

#### Scenario: A service that does not send messages
- **WHEN** an upsert carrying an e-mail address is applied with `ludwig.identity.contact.enabled=false`
- **THEN** the user's row holds no e-mail address

### Requirement: Every change is counted
Each applied, skipped or refused change SHALL increment `ludwig_identity_changes_total` tagged with its source, type
(`upsert`, `disable`, `remove`) and outcome (`applied`, `stale`, `foreign-source`, `discarded`); each sweep SHALL
increment `ludwig_identity_sweeps_total` tagged with its source and outcome (`completed`, `aborted`), and record the
users it disabled in `ludwig_identity_sweep_disabled_total`.

#### Scenario: A stale change
- **WHEN** a stale upsert from `staff` is skipped
- **THEN** `ludwig_identity_changes_total{source="staff",type="upsert",outcome="stale"}` increases by one

### Requirement: The former topic key is gone
`ludwig.identity.kafka.topic` SHALL NOT be read. When it is set, the application SHALL fail to start, naming the key
and its replacement, `ludwig.identity.kafka.subscriptions.<source>.topic`.

#### Scenario: An old topic key
- **WHEN** a service still sets `ludwig.identity.kafka.topic`
- **THEN** the application fails to start, naming `ludwig.identity.kafka.subscriptions.<source>.topic`
