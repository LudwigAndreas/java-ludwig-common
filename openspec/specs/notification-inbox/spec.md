# The notification inbox

## Purpose

Holds what the platform has told a person, as a durable list that person owns and reads back — with
a read state only they can move, a lifetime that ends when they have read it rather than when an
operational retention window closes, and an API that can only ever show a caller their own items.

## Requirements

### Requirement: An inbox item is a separate aggregate from the delivery that produced it

An inbox item SHALL be stored separately from the delivery row, and read state SHALL NOT be a field
on any delivery entity. The two are written by different parties and have different lifetimes: a
delivery row is a work-queue row written by the service and kept narrow because it is scanned and
updated by the claim query continuously; an inbox item is a document mutated by its recipient and
kept until they have read it.

A read-state field appearing on a delivery entity SHALL fail the build.

#### Scenario: An item is created

- **WHEN** an in-app delivery is settled
- **THEN** an inbox item exists carrying its own identifier, its owner, its rendered content, its
  creation instant and an unset read state, and the delivery row carries no read state

#### Scenario: Read state is added to a delivery entity

- **WHEN** a field recording that a recipient has seen, read or dismissed something is declared on
  a delivery entity
- **THEN** the build fails

#### Scenario: An item outlives its delivery

- **WHEN** the delivery that produced an unread item has been purged by the delivery retention
  window
- **THEN** the item is still present and still readable by its owner, and the API does not require
  the delivery to answer

### Requirement: An item's content is stored separately from delivery content

An inbox item's rendered content SHALL be stored on the inbox aggregate and SHALL NOT be stored in
the delivery content table. That table exists so an operator can answer "what did we send?", is
treated as the most sensitive data the service holds, and is purged on a short window with a
startup check that a body never outlives its delivery — all three of which are wrong for content a
recipient has not read yet and is meant to read.

An in-app body written to the delivery content table SHALL fail the build.

#### Scenario: Delivery content is purged while an item is unread

- **WHEN** the delivery content retention window elapses and the purge runs
- **THEN** the unread item's subject and body are unchanged and still returned by the read API

#### Scenario: An in-app body is written to the delivery content table

- **WHEN** code writes the rendered body of an `IN_APP` delivery to the delivery content table
- **THEN** the build fails

### Requirement: An item has three recipient-driven state transitions and they are monotonic

An item SHALL carry three independent instants — seen, read and dismissed — each initially unset,
each settable only by the item's owner, and each SHALL NOT be reset once set. Marking an item read
SHALL also mark it seen if it was not already. A dismissed item SHALL remain retrievable by
identifier and SHALL be excluded from the default list.

#### Scenario: The owner marks an item read

- **WHEN** the owner marks an unread item read
- **THEN** its read instant and its seen instant are both set, and the unread count drops by one

#### Scenario: The owner marks an already-read item read again

- **WHEN** the owner marks an item read that is already read
- **THEN** the request succeeds and the original read instant is unchanged, so the operation is
  idempotent and a retry cannot rewrite history

#### Scenario: The owner dismisses an item

- **WHEN** the owner dismisses an item
- **THEN** it no longer appears in the default list, it still appears when dismissed items are
  explicitly requested, and fetching it by identifier still succeeds

#### Scenario: Something other than the owner attempts a transition

- **WHEN** a caller who does not own the item attempts to mark it read
- **THEN** the request is refused and the item is unchanged

### Requirement: The owner can mark every item read in one request

The service SHALL offer a single operation that marks all of the caller's unread items read. It
SHALL apply only to the caller's own items, SHALL report how many items it moved, and SHALL be safe
to repeat.

#### Scenario: A recipient clears their inbox

- **WHEN** the owner of twelve unread items marks all read
- **THEN** the response reports twelve, the unread count is zero, and no other recipient's item is
  affected

#### Scenario: The operation is repeated

- **WHEN** the same caller marks all read again immediately
- **THEN** the response reports zero and no read instant is rewritten

### Requirement: A caller can only ever address their own inbox

Every inbox endpoint SHALL resolve the owner from the authenticated caller and SHALL NOT accept an
owner as a parameter. A request naming another subject's item SHALL be refused, and the refusal
SHALL NOT distinguish "not yours" from "does not exist", because distinguishing them makes the
endpoint an existence oracle for other people's notifications.

The owner SHALL NOT be a filterable field, so no query option can enumerate or probe by it.

#### Scenario: A caller lists their inbox

- **WHEN** an authenticated caller lists their inbox
- **THEN** every item returned belongs to that caller, with no parameter having selected them

#### Scenario: A caller fetches another subject's item by identifier

- **WHEN** a caller requests an item identifier belonging to somebody else
- **THEN** the response is the same refusal as for an identifier that does not exist at all

#### Scenario: A caller tries to filter by owner

- **WHEN** a query option filters on the owner field by any spelling of its name
- **THEN** the request is rejected as naming an unfilterable field, in the same way the service
  already rejects a filter on a recipient address

#### Scenario: An unauthenticated request

- **WHEN** an inbox endpoint is called without an authenticated caller
- **THEN** the request is refused and nothing is read or written

### Requirement: The inbox list uses the platform query contract

The list endpoint SHALL accept the platform's standard query options and return the platform's
standard page envelope, including the caller's absolute position, and SHALL publish its filterable
surface as a document rather than requiring a caller to discover it by probing. It SHALL default to
newest first.

#### Scenario: A caller pages through their inbox

- **WHEN** a caller requests the second page of their inbox
- **THEN** the envelope states the caller's absolute position and the items are ordered newest
  first by default

#### Scenario: A caller asks what they may filter on

- **WHEN** a caller requests the filterable surface of the inbox
- **THEN** the document lists only the fields this caller may use, and the owner field is absent
  from it

#### Scenario: A caller filters to unread items of one category

- **WHEN** a caller filters on read state and category together
- **THEN** only their matching items are returned, and the count reflects the filter

### Requirement: The unread count is a separate, cheap operation

The service SHALL expose the caller's unread item count as its own operation, so a client
rendering an unread badge does not have to page the inbox to produce it. It SHALL honour the same
owner scoping as the list and SHALL exclude dismissed items.

#### Scenario: A client renders an unread badge

- **WHEN** a caller requests their unread count
- **THEN** the response is a count of their own undismissed unread items, with no item content

#### Scenario: Every item has been read

- **WHEN** a caller with no unread items requests the count
- **THEN** the response is zero rather than an error or an empty body

### Requirement: Retention is anchored on being read, never on being created

An inbox item SHALL be purged on its own retention window, measured from the instant it was read
or dismissed. An item that has been neither read nor dismissed SHALL NOT be purged by elapsed age
alone. The window SHALL be configurable, and the relationship between it and the delivery windows
SHALL be checked at startup in the same way the existing windows check each other.

A deployment that needs a hard ceiling on unread items SHALL state it as its own separate
configured maximum age, so that purging something nobody has read is always an explicit decision
rather than a side effect of the ordinary window.

#### Scenario: A read item ages out

- **WHEN** the inbox retention window elapses after an item was read
- **THEN** the item and its content are purged

#### Scenario: An unread item ages past the window

- **WHEN** an item has existed unread for longer than the inbox retention window and no unread
  ceiling is configured
- **THEN** the item is still present and still readable by its owner

#### Scenario: An unread ceiling is configured

- **WHEN** an unread ceiling is configured and an unread item exceeds it
- **THEN** the item is purged, and the purge is reported distinctly from the ordinary
  read-anchored purge so an operator can see that unread notifications were discarded

#### Scenario: Startup with an inconsistent window

- **WHEN** the configured inbox window would purge an item's content before the item itself
- **THEN** the application fails to start, rather than running a purge that orphans content

### Requirement: Inbox text is localized and never hard-coded

Every piece of user-facing text the inbox API produces SHALL come from the service's message
bundles, present in every supported locale with matching key sets, and an item's stored content
SHALL have been rendered in the recipient's own language at the time it was produced.

#### Scenario: A recipient reads an item in their language

- **WHEN** a recipient whose language is not the platform default reads an item
- **THEN** the item's subject and body are in the recipient's language, as rendered when the
  notification was produced

#### Scenario: A bundle key is missing from one locale

- **WHEN** a key exists in one locale's bundle and not another's
- **THEN** the build fails

#### Scenario: An inbox error is returned

- **WHEN** an inbox request is refused
- **THEN** the response is the platform's standard problem shape, produced by the single
  problem-detail pipeline, rather than a shape specific to this feature
