# identity-source-directory Specification

## Purpose

Feeds the identity projection from a provider that exposes its users over an API: a service supplies a directory
that reads one page of users, and the platform syncs changes incrementally, sweeps the source completely on a
schedule, persists its progress and isolates failures per source.
## Requirements
### Requirement: A service supplies one directory per source
A service SHALL supply each directory as a bean that names its source and answers one query: a page of users
(each an upsert, a disable or a remove) after a cursor, optionally only those changed since a watermark, together
with the next cursor and the page's watermark. A directory naming an undeclared source, or two directories naming
the same source, SHALL fail startup, naming the source. A source served by a directory MAY also be served by
another feed.

#### Scenario: Two directories for one source
- **WHEN** two directory beans both name source `staff`
- **THEN** the application fails to start, naming `staff`

### Requirement: Incremental sync resumes from a persisted watermark
Each directory SHALL be synced on `ludwig.identity.directory.sources.<source>.sync-interval` (default `PT5M`) by
asking for the users changed since the source's stored watermark, page by page until the directory returns no next
cursor. Each page's changes and the watermark it returns SHALL be committed together, so a sync interrupted after a
page resumes after that page. A source with no stored watermark, or a directory that cannot filter by change, SHALL
be synced by a full sweep instead.

#### Scenario: A sync interrupted mid-way
- **WHEN** a sync applies pages 1 and 2 of 5 and the replica is stopped
- **THEN** the next sync starts from page 2's watermark, and pages 1 and 2 are not fetched again

#### Scenario: The first sync of a new source
- **WHEN** a directory's source has no stored watermark
- **THEN** its first sync lists the source completely and stores the watermark the sweep ends with

### Requirement: Each source is swept completely on a schedule
Each directory SHALL be swept on `ludwig.identity.directory.sources.<source>.sweep-interval` (default `P1D`): every
page is listed, and the projection's sweep rule (disable the unlisted, abort past the threshold) is applied when the
listing ends. A sweep that fails before the listing ends SHALL be abandoned and change no user's status.

#### Scenario: A user deleted upstream without a change record
- **WHEN** a user is deleted at the provider in a way its change filter does not report
- **THEN** the next sweep disables that user

#### Scenario: A sweep fails on its last page
- **WHEN** the directory fails while returning a sweep's last page
- **THEN** no user's status is changed by that sweep

### Requirement: Syncs and sweeps run once across replicas, as platform jobs
A source's sync and sweep SHALL run as the jobs `ludwig-identity-<source>-sync` and `ludwig-identity-<source>-sweep`,
each under a run lock of the same name, so that at most one replica runs each at a time. Both SHALL be listed by
`GET /admin/v1/jobs` and runnable now with `job.definition.run`.

#### Scenario: An operator forces a sweep
- **WHEN** a holder of `job.definition.run` calls `POST /admin/v1/jobs/ludwig-identity-staff-sweep/run`
- **THEN** one replica sweeps `staff`, and the call is `409` while another replica holds that job's lease

### Requirement: A failing directory changes nothing and stops only its own source
A sync whose directory call fails SHALL keep the stored watermark and every user unchanged, record the failure as the
source's last sync outcome with its error code, and retry on the next schedule. A failure of one source SHALL NOT
delay or fail any other source's sync or sweep.

#### Scenario: The provider is down
- **WHEN** `staff`'s directory times out during a sync
- **THEN** `staff`'s watermark is unchanged, its last sync outcome is failed with the error code, and `customers`
  syncs on schedule

### Requirement: Progress and outcomes are observable
Each source's watermark, last sync and last sweep (time, outcome, pages, the changes a sync read, the users a sweep
disabled) SHALL be persisted and
listed by the `identity` area's sources listing. Each sync and sweep SHALL be timed by
`ludwig_identity_directory_run_seconds` tagged with source, kind (`sync`, `sweep`) and outcome (`succeeded`,
`failed`, `aborted`).

#### Scenario: A succeeded sync
- **WHEN** a sync of `staff` succeeds
- **THEN** the sources listing shows `staff`'s new watermark and a succeeded last sync, and
  `ludwig_identity_directory_run_seconds{source="staff",kind="sync",outcome="succeeded"}` records one run
