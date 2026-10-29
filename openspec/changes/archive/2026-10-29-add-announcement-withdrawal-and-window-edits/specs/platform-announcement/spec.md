## ADDED Requirements

### Requirement: A published announcement can be withdrawn, and the record of it is kept

An announcer SHALL be able to withdraw a published announcement, optionally giving a reason.
A withdrawn announcement SHALL be absent from every recipient's list, unread count and read by
identifier from the moment of withdrawal, whatever its window says. The announcement SHALL NOT be
deleted by withdrawal. It SHALL record who withdrew it, when, and the reason. The admin view SHALL
show this. Retention SHALL purge a withdrawn announcement on the same terms as any other whose
visibility has ended, measured from the withdrawal.

#### Scenario: An open announcement is withdrawn

- **WHEN** an announcer withdraws an announcement whose window is open
- **THEN** the response is `200` with the announcement showing its withdrawal instant, actor and
  reason. A recipient listing announcements no longer sees it, their unread count no longer includes
  it, and fetching it by identifier answers `404`

#### Scenario: A not-yet-open announcement is withdrawn

- **WHEN** an announcer withdraws an announcement whose start is in the future
- **THEN** it never becomes visible to any recipient, including after its start has passed

#### Scenario: Withdrawing twice

- **WHEN** an announcer withdraws an announcement that is already withdrawn
- **THEN** the response is `200` with the original withdrawal unchanged, and no second audit record
  is written

#### Scenario: Withdrawal without the announcer role

- **WHEN** a caller without `NOTIFICATION_ANNOUNCER` requests a withdrawal
- **THEN** the request is rejected with `403` and the announcement is unchanged

#### Scenario: Withdrawal is audited

- **WHEN** an announcement is withdrawn
- **THEN** one `announcement-withdrawn` audit record names the announcement, its category and
  audience, the actor and the reason

#### Scenario: A withdrawn announcement ages out

- **WHEN** the retention window elapses after an announcement was withdrawn
- **THEN** the announcement, its content and every dismissal marker for it are gone, even if its
  original window end has not yet passed

### Requirement: Withdrawal stops the email fan-out, and does not recall what was sent

Withdrawing an announcement whose category emails SHALL cancel its fan-out if the fan-out has not
finished. Deliveries already created SHALL NOT be recalled. An email already sent cannot be taken
back, and the withdrawal SHALL NOT claim otherwise.

#### Scenario: Withdrawal during a running fan-out

- **WHEN** an announcement is withdrawn while its email fan-out is still running
- **THEN** the fan-out stops creating deliveries and finishes as cancelled. Deliveries created before
  the withdrawal are kept and counted in the run's status

#### Scenario: Withdrawal after the fan-out finished

- **WHEN** an announcement is withdrawn after its email fan-out has finished
- **THEN** the run's status is unchanged and the withdrawal succeeds

#### Scenario: Withdrawal of an inbox-only announcement

- **WHEN** an announcement whose category does not email is withdrawn
- **THEN** the withdrawal succeeds and no email run is created or touched

### Requirement: An announcement's window can be changed, within guards

An announcer SHALL be able to change a published announcement's window. The end SHALL be movable
earlier or later, but SHALL NOT be set at or before the current instant. The start SHALL be changeable
only while the window has not yet opened, and the new start SHALL NOT be in the past. After a change
the start SHALL still precede the end, and the length SHALL still be within the configured maximum.
A window change SHALL be audited with the previous and the new start and end. The audience SHALL
remain unchangeable.

#### Scenario: The end is extended

- **WHEN** an announcer moves an open announcement's end later, within the configured maximum
- **THEN** the response is `200` with the new window, and recipients keep seeing it until the new end

#### Scenario: The end is brought forward

- **WHEN** an announcer moves an open announcement's end to a later instant than now but earlier than
  before
- **THEN** recipients stop seeing it at the new end

#### Scenario: The end is moved into the past

- **WHEN** a window change sets the end at or before the current instant
- **THEN** the request is rejected with `422` and the window is unchanged. Withdrawal, not a past
  end, is the way to take an announcement down now

#### Scenario: The start of an open window is changed

- **WHEN** a window change gives a start different from the current one for an announcement whose
  window has already opened
- **THEN** the request is rejected with `409`. Recipients have already seen it, so the start can no
  longer be moved

#### Scenario: The start of a future window is changed

- **WHEN** an announcer moves the start of an announcement whose window has not opened, to a
  non-past instant before the end
- **THEN** the response is `200` and the announcement first becomes visible at the new start

#### Scenario: A changed window exceeds the maximum

- **WHEN** a window change produces a window longer than the configured maximum
- **THEN** the request is rejected with `422`, naming both the requested and the permitted length

#### Scenario: A window change is audited

- **WHEN** a window change succeeds
- **THEN** one `announcement-window-changed` audit record names the announcement, the actor, and the
  previous and new start and end

### Requirement: A withdrawn announcement is final

A withdrawn announcement SHALL NOT have its window or its content changed.

#### Scenario: Changing the window of a withdrawn announcement

- **WHEN** a window change is requested for a withdrawn announcement
- **THEN** the request is rejected with `409` and nothing is changed or audited

#### Scenario: Correcting a withdrawn announcement

- **WHEN** a content correction is requested for a withdrawn announcement
- **THEN** the request is rejected with `409` and the stored content is unchanged
