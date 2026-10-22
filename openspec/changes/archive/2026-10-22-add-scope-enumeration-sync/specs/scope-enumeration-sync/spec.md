## Purpose

Keeps a local copy of a partner-side collection in step with the partner: for each local scope row,
every item the partner lists under that scope exists locally, changes to it are applied, and items
that stop being listed are reported to the integration so it can decide what removal means.

## ADDED Requirements

### Requirement: An item with no local counterpart is created
The system SHALL hand every item returned by a scope's feed to the integration for creation or
update, whether or not a local row for it already exists, and SHALL record the item as a member of
that scope.

#### Scenario: First walk of a scope
- **WHEN** a scope with no recorded members is walked and its feed returns three items
- **THEN** the integration is asked to apply each of the three items, flagged as new, and three
  membership rows exist for that scope

#### Scenario: Item already known and changed
- **WHEN** a walk returns an item whose membership row exists with a different payload hash
- **THEN** the integration is asked to apply the item, flagged as existing, and the membership row
  carries the new hash

#### Scenario: Item already known and unchanged
- **WHEN** a walk returns an item whose membership row exists with the same payload hash
- **THEN** the integration is not called for that item and the membership row is marked as seen in
  the current walk

#### Scenario: Integration declines an item
- **WHEN** the integration answers that an item is not wanted
- **THEN** no membership row exists for that item after the page is applied, and if one existed
  before, the integration is told the member left the scope

### Requirement: Walk state is kept per scope
The system SHALL keep the paging cursor, the incremental watermark, the walk generation and the
time of the last completed walk separately for every scope of a task.

#### Scenario: A scope added later gets a full first walk
- **WHEN** a task has one scope whose watermark is yesterday and a second scope is discovered today
- **THEN** the second scope's first walk is a full walk with no changed-since bound

#### Scenario: An interrupted walk resumes
- **WHEN** a walk of a scope stops after its second page has been applied and the process restarts
- **THEN** the next walk of that scope requests the third page and does not request the first two

#### Scenario: The cursor never runs ahead of the data
- **WHEN** a page cannot be fetched, or cannot be applied as a whole
- **THEN** the persisted cursor for that scope still addresses that page

### Requirement: Scopes are discovered from local rows and walked when due
The system SHALL obtain the set of scopes from the integration, SHALL walk a scope when its full or
incremental interval has elapsed or it has never completed a walk, and SHALL walk scopes that have
never completed before scopes that are merely overdue.

#### Scenario: New scope is walked first
- **WHEN** one scope has never completed a walk, another is one minute overdue, and a run may walk
  only one scope
- **THEN** the scope that has never completed is the one walked

#### Scenario: Scope that is not due is left alone
- **WHEN** a scope completed a full walk within the configured full interval and no incremental
  interval is configured
- **THEN** a run makes no call to the partner for that scope

#### Scenario: Scope removed locally
- **WHEN** a scope's local row no longer exists at the time it would be walked
- **THEN** its walk state and membership rows are deleted and the integration is not told that any
  member left

#### Scenario: Single fixed scope
- **WHEN** an integration declares one fixed scope that corresponds to no local row
- **THEN** the task walks that one scope on the same terms as any discovered scope

### Requirement: Incremental walks are used only when the feed supports them
The system SHALL bound a walk by the scope's watermark only when the integration declares that its
feed accepts a changed-since bound and an incremental interval is configured, and SHALL advance a
scope's watermark only when a walk of that scope completes.

#### Scenario: Incremental walk between full walks
- **WHEN** a scope's incremental interval has elapsed and its full interval has not
- **THEN** the feed is called with that scope's watermark as the changed-since bound

#### Scenario: Watermark does not move on an interrupted walk
- **WHEN** a walk stops at the run timeout before its last page
- **THEN** the scope's watermark is unchanged

#### Scenario: Feed without changed-since
- **WHEN** an incremental interval is configured for a task whose feed does not declare
  changed-since support
- **THEN** the application context fails to start and the message names the task

### Requirement: Members that leave a scope are reported, not removed
After a completed full walk the system SHALL tell the integration, once per member, about every
member of that scope not seen for the configured number of consecutive full walks, and SHALL then
delete that member's membership row. The system SHALL NOT delete or modify any row owned by the
integration.

#### Scenario: Member absent from a full walk
- **WHEN** a scope has members A and B and a completed full walk returns only A
- **THEN** the integration is told B left the scope, and no membership row for B exists afterwards

#### Scenario: Member in two scopes leaves one
- **WHEN** an item is a member of scopes X and Y and a completed full walk of X no longer returns it
- **THEN** the integration is told the member left X, and its membership row for Y is untouched

#### Scenario: Member returns after leaving
- **WHEN** a member reported as having left a scope is returned by a later walk of that scope
- **THEN** the integration is asked to apply it, flagged as new

#### Scenario: Integration defines no removal behaviour
- **WHEN** a member leaves a scope and the integration has not defined what leaving means
- **THEN** the membership row is deleted, no integration-owned row changes, and the unhandled
  removal is counted in a metric

### Requirement: A removal sweep runs only on trustworthy evidence
The system SHALL NOT report any member as having left a scope after an incremental walk, after a
walk that did not reach its last page, or after a walk in which any page or member failed.

#### Scenario: Incremental walk does not sweep
- **WHEN** an incremental walk of a scope completes and returns one of its two members
- **THEN** the integration is not told that the other member left

#### Scenario: Walk with a failed member does not sweep
- **WHEN** a full walk reaches its last page and one member failed to apply
- **THEN** no member of that scope is reported as having left

#### Scenario: Consecutive misses required
- **WHEN** two consecutive misses are configured and a member is absent from one completed full walk
- **THEN** the member is not reported as having left until a second consecutive completed full walk
  also omits it

### Requirement: An implausibly large sweep is withheld
The system SHALL withhold a sweep that would report more than the configured fraction of a scope's
members as having left, SHALL make the withheld sweep visible to an operator, and SHALL perform it
only after an operator confirms it.

#### Scenario: Partner returns an empty list
- **WHEN** a scope with one hundred members completes a full walk that returns no items and the
  configured fraction is one half
- **THEN** no member is reported as having left, and the scope is shown as having a withheld sweep

#### Scenario: Operator confirms
- **WHEN** an operator confirms a withheld sweep for a scope
- **THEN** the withheld members are reported to the integration as having left

#### Scenario: Operator discards
- **WHEN** an operator discards a withheld sweep for a scope
- **THEN** no member is reported as having left and the scope becomes due for a full walk

### Requirement: One failing member does not block its scope
The system SHALL apply a page of members atomically, and when a page fails SHALL retry its members
individually so that members that can be applied are applied. A member that keeps failing SHALL be
retried with backoff and quarantined after the task's attempt budget.

#### Scenario: One bad member in a page
- **WHEN** a page of ten items contains one the integration throws on
- **THEN** the other nine are applied and recorded, the failing one is recorded as failed with its
  error, and the walk continues to the next page

#### Scenario: Budget exhausted
- **WHEN** a member has failed as many times as the task's attempt budget allows
- **THEN** it is marked quarantined, is not handed to the integration again until an operator
  requeues it, and its scope does not sweep while it remains quarantined

### Requirement: Enumeration runs are exclusive per task
The system SHALL ensure that at most one instance walks the scopes of a given enumeration task at a
time, using the platform's run lease.

#### Scenario: Two instances
- **WHEN** two instances start a run of the same enumeration task at the same moment
- **THEN** exactly one of them calls the partner and the other records a skipped run

### Requirement: Enumeration does not write to the staging inbox
The system SHALL NOT create staged inbox records for the members of an enumeration task.

#### Scenario: Walk leaves the inbox untouched
- **WHEN** an enumeration task completes a walk that applies members
- **THEN** the staged-record table contains no row for that task

### Requirement: Invalid enumeration configuration prevents startup
The system SHALL refuse to start, reporting every problem found in one pass, when an enumeration
task's configuration is individually valid and jointly wrong.

#### Scenario: Task bean without configuration
- **WHEN** an enumeration task is declared with no matching configuration block, or a configuration
  block has no matching task
- **THEN** the application context fails to start and the message names the task

#### Scenario: Name shared with a key-addressed task
- **WHEN** an enumeration task and a key-addressed task are configured under the same name
- **THEN** the application context fails to start and the message names the shared name

#### Scenario: Unknown named resource
- **WHEN** an enumeration task references a rate limit or REST client that is not configured
- **THEN** the application context fails to start and the message names the missing resource

#### Scenario: Sweep settings out of range
- **WHEN** the sweep fraction is not greater than zero and at most one, or the consecutive-miss
  count is less than one
- **THEN** the application context fails to start and the message names the setting

### Requirement: Scope freshness and sweep state are observable
The system SHALL expose, per enumeration task, how far the most overdue scope is past its due time,
the number of quarantined members and the number of withheld sweeps, and SHALL record walk start,
walk completion, member removal and every operator action through the platform audit trail.

#### Scenario: Overdue scope
- **WHEN** a scope is ten minutes past the time its next walk was due
- **THEN** the task's scope freshness lag reports at least six hundred seconds

#### Scenario: All scopes within budget
- **WHEN** every scope of a task completed its walk within the configured intervals
- **THEN** the task's scope freshness lag reports zero

#### Scenario: Removal is audited
- **WHEN** a member is reported as having left a scope
- **THEN** an audit event in the reconciliation category names the task, the scope and the member

### Requirement: Operators can inspect and drive a scope
The system SHALL let an operator, through the existing reconciliation management endpoint, see a
task's scopes with their walk state, start a walk of one scope, preview a walk without writing,
reset one scope's walk state, requeue quarantined members, and confirm or discard a withheld sweep.
Confirming a withheld sweep SHALL require both the destructive-operations setting and an explicit
confirmation in the call.

#### Scenario: Preview writes nothing
- **WHEN** an operator previews a walk of a scope
- **THEN** the response reports how many items would be new, changed, unchanged and absent, and no
  membership row, cursor or integration-owned row changes

#### Scenario: Confirm without the setting
- **WHEN** an operator confirms a withheld sweep while destructive operations are not enabled
- **THEN** the call is refused and no member is reported as having left

#### Scenario: Reset
- **WHEN** an operator resets a scope
- **THEN** its cursor and watermark are cleared, its membership rows are kept, and its next walk is
  a full walk
