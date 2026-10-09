# Platform announcements

## Purpose

Lets the platform tell an audience defined by a rule — everybody, or everybody holding a role —
something it has decided they should know, as one stored document those people read and dismiss,
rather than as one copy per person.

## Requirements

### Requirement: An announcement is one row carrying its audience as a predicate

An announcement SHALL be stored once, whatever the size of its audience, and SHALL carry its audience
as a kind and a value rather than as a materialized list of subjects. Publishing to an audience of
one hundred thousand people SHALL write the same number of rows as publishing to an audience of ten.

A materialized audience on the announcement SHALL fail the build.

#### Scenario: An announcement is published to everybody

- **WHEN** an announcement is published with the audience `EVERYONE`
- **THEN** exactly one announcement row and one content row exist, and no per-user row of any kind
  has been created

#### Scenario: An announcement is published to a role

- **WHEN** an announcement is published with the audience `ROLE(ADMIN)`
- **THEN** the stored row records the kind and the role code, and no membership has been enumerated

#### Scenario: Somebody adds a subject list to the announcement

- **WHEN** a field holding a collection of subjects, or a join table keyed by announcement and
  subject for the purpose of recording the audience, is declared on the announcement
- **THEN** the build fails, because the row count is the whole point of the aggregate

### Requirement: Visibility is evaluated when the announcement is read, not when it is published

Who can see an announcement SHALL be resolved at read time from the caller's current roles. A
recipient whose role is revoked SHALL stop seeing an announcement targeted at that role on their next
read, and a recipient who gains the role SHALL see announcements published before they held it, for
as long as those are inside their visibility window.

The resolution SHALL use the roles already carried on the authenticated principal, and SHALL NOT
require a second identity lookup per announcement.

#### Scenario: A role is revoked

- **WHEN** a recipient who could see a `ROLE(ADMIN)` announcement has that role removed
- **THEN** the announcement is absent from their next list and their unread count drops, with no
  purge, rewrite or background job having run

#### Scenario: A role is granted

- **WHEN** a recipient is granted `ADMIN` while an announcement targeted at that role is still
  inside its visibility window
- **THEN** that announcement appears in their list

#### Scenario: A user who did not exist at publication time

- **WHEN** a user is created after an `EVERYONE` announcement was published, and its window is still
  open
- **THEN** they see it, which is the behaviour a per-recipient fan-out cannot produce

#### Scenario: An announcement for a role the caller does not hold

- **WHEN** a caller who holds no `ADMIN` role lists their announcements
- **THEN** no `ROLE(ADMIN)` announcement is returned, and no endpoint reveals that one exists

### Requirement: An announcement is visible only inside its window

An announcement SHALL carry a start and an end instant. It SHALL NOT be visible before the start or
at and after the end, and the end SHALL be mandatory — an announcement with no end is a banner
nobody removes. The permitted length SHALL be capped by configuration.

#### Scenario: A future announcement

- **WHEN** an announcement's start is in the future
- **THEN** it is absent from every recipient's list and from every unread count until that instant

#### Scenario: An expired announcement

- **WHEN** an announcement's end has passed
- **THEN** it is absent from the list and from the count, and it is not an error to fetch it by
  identifier until retention removes it

#### Scenario: A window longer than the configured maximum

- **WHEN** an announcement is published with a window exceeding the configured maximum
- **THEN** the request is rejected, naming both the requested and the permitted length

#### Scenario: No end is given

- **WHEN** an announcement is published with no end instant
- **THEN** the request is rejected

### Requirement: A recipient can dismiss an announcement, and dismissal is one lazy row

A recipient SHALL be able to dismiss an announcement, which removes it from their list and from
their unread count while leaving it visible to everybody else. Dismissal SHALL write exactly one row
for that recipient and that announcement, created on first dismissal and never in advance, and SHALL
be idempotent.

A dismissed announcement SHALL remain retrievable by identifier while its window is open.

#### Scenario: A recipient dismisses an announcement

- **WHEN** a recipient dismisses an announcement
- **THEN** it leaves their list and their count, one marker row exists for them, and no other
  recipient's view changes

#### Scenario: Dismissal is repeated

- **WHEN** the same recipient dismisses the same announcement again
- **THEN** the request succeeds and the original instant is unchanged

#### Scenario: Nobody has dismissed an announcement

- **WHEN** an announcement has been seen by many recipients and dismissed by none
- **THEN** no marker rows exist at all

#### Scenario: A dismissed announcement is fetched directly

- **WHEN** a recipient fetches an announcement they have dismissed, by identifier
- **THEN** it is returned, because dismissing is not deleting and a client may hold a link to it

### Requirement: Announcements are their own feed, and the inbox is unchanged

The announcement feed SHALL be separate endpoints from the inbox, with its own list, its own unread
count and its own dismissal. The inbox's queries SHALL continue to be constrained by owner alone and
SHALL NOT gain a derived visibility term.

The feed SHALL use the platform's standard query options and page envelope and SHALL publish its
filterable surface, and the audience fields SHALL NOT be filterable.

#### Scenario: A recipient reads both feeds

- **WHEN** a recipient lists their inbox and their announcements
- **THEN** the inbox contains only items addressed to them and the announcement feed only
  announcements visible to them, and neither contains the other's rows

#### Scenario: A caller tries to filter by audience

- **WHEN** a query option filters on the audience kind or value
- **THEN** the request is rejected as naming an unfilterable field, because filtering by audience
  would let a caller enumerate which roles have been addressed

#### Scenario: The inbox query is changed to include announcements

- **WHEN** the inbox's list or count query is given a term resolving announcement visibility
- **THEN** it is a finding: the inbox's correctness currently rests on a single owner predicate, and
  a derived term on the service's most-polled endpoint is a materially different risk

### Requirement: Only configured audiences and configured roles may be targeted

A deployment SHALL declare which audience kinds it permits and SHALL declare an allowlist of role
codes that may be targeted. A publish naming an audience kind that is not permitted, or a role that
is not on the allowlist, SHALL be rejected.

The allowlist SHALL NOT be bypassable by a request, and a bypass SHALL fail the build. Targeting a
role is distinct from being permitted to publish, which is resource-level authorization.

#### Scenario: An allowed role is targeted

- **WHEN** an announcement targets a role on the allowlist
- **THEN** it is published

#### Scenario: A role that is not on the allowlist

- **WHEN** an announcement targets a role the deployment has not listed
- **THEN** the request is rejected, and the message does not reveal whether that role exists — a
  free-form role target would otherwise enumerate the role space

#### Scenario: An audience kind the deployment has disabled

- **WHEN** a deployment permits only `ROLE` and an announcement is published to `EVERYONE`
- **THEN** the request is rejected

#### Scenario: A caller who may not publish

- **WHEN** an authenticated caller without the publishing role attempts to publish
- **THEN** the request is refused, independently of what the audience allowlist permits

### Requirement: The category decides the channels and the class, and a request cannot

A deployment SHALL declare a catalogue of announcement categories, each naming the channels that
category is delivered over and its category class. A publish SHALL name a category and SHALL NOT
carry a channel set, a per-request bypass, or a flag selecting whether email is sent.

A category not in the catalogue SHALL be rejected at publish, and a catalogue naming an unknown
channel or class SHALL fail startup.

#### Scenario: A category delivering to the inbox only

- **WHEN** an announcement is published in a category whose channels are the inbox alone
- **THEN** it becomes visible and no email delivery is created

#### Scenario: A category delivering to the inbox and email

- **WHEN** an announcement is published in a category naming both
- **THEN** it becomes visible immediately and an email fan-out is started

#### Scenario: A request tries to choose its own channels

- **WHEN** a publish request carries a channel set or a flag asking for email
- **THEN** it is a finding: from inside any one team its own announcement always looks important, so
  the decision belongs to whoever owns the catalogue, exactly as the transactional bypass does

#### Scenario: An unknown category

- **WHEN** an announcement names a category the deployment has not configured
- **THEN** the request is rejected rather than defaulted, because a default would silently pick a
  channel set and a declinability

### Requirement: The `PLATFORM` category class bypasses opt-out and honours quiet hours

A third category class SHALL exist for a notification the recipient cannot decline but which is not
urgent. It SHALL bypass per-category opt-out, and it SHALL NOT bypass quiet hours.

The existing single bypass SHALL be split into its two halves so that this combination is
representable. `TRANSACTIONAL` SHALL continue to bypass both and `MARKETING` SHALL continue to
bypass neither.

#### Scenario: A platform announcement reaches somebody who opted out of the category

- **WHEN** a `PLATFORM` announcement is delivered to a recipient who has opted out of that category
- **THEN** it is delivered, because the class is undeclinable

#### Scenario: A platform announcement would email somebody during their quiet hours

- **WHEN** a `PLATFORM` announcement's email delivery is settled while the recipient is inside their
  quiet window
- **THEN** it is deferred to the end of the window rather than sent, because the class is
  undeclinable but not urgent

#### Scenario: A transactional notification during quiet hours

- **WHEN** a `TRANSACTIONAL` notification is settled inside a recipient's quiet window
- **THEN** it is sent immediately, unchanged from today

#### Scenario: A marketing notification for somebody who opted out

- **WHEN** a `MARKETING` notification is settled for a recipient who opted out of its category
- **THEN** it is suppressed, unchanged from today

#### Scenario: A platform announcement to the inbox during quiet hours

- **WHEN** a `PLATFORM` announcement is visible in the inbox while the recipient is inside their
  quiet window
- **THEN** it is visible, because quiet hours do not apply to a passive destination at all

### Requirement: An announcement is rendered once per supported locale, at publish

An announcement's content SHALL be rendered once for each locale the deployment supports, stored one
row per locale, and read back in the recipient's own locale falling back to the configured default —
the same resolution rule the template resolver already applies.

Every locale SHALL be rendered before anything is stored, so that a template which fails in one
locale fails the whole publish rather than producing a half-translated announcement. The number of
content rows SHALL depend on the number of supported locales and SHALL NOT depend on the size of the
audience.

#### Scenario: Two supported locales

- **WHEN** an announcement is published in a deployment supporting English and Russian
- **THEN** two content rows exist, and the number is the same whether the audience is ten people or
  a hundred thousand

#### Scenario: A recipient reads in their own language

- **WHEN** a recipient whose locale is Russian reads an announcement
- **THEN** they get the Russian content

#### Scenario: A recipient whose locale the deployment does not support

- **WHEN** a recipient's locale has no content row
- **THEN** they get the default locale's content in full, rather than a half-translated document

#### Scenario: A template that fails in one locale

- **WHEN** the template renders in English and fails in Russian
- **THEN** the publish is rejected and no announcement, content row or fan-out run is created

#### Scenario: A template is corrected after publication

- **WHEN** the template file is corrected after an announcement was published
- **THEN** the published announcement is unchanged, because it carries what was rendered at
  publication — the same promise already made about a sent email

### Requirement: An announcement's content can be corrected, and its audience cannot

The rendered content of a published announcement SHALL be correctable in place, and the correction
SHALL be visible to every recipient who has not dismissed it. Its audience kind and value SHALL NOT
be changeable after publication.

#### Scenario: A typo is corrected

- **WHEN** a published announcement's content is corrected
- **THEN** every recipient who reads it afterwards sees the corrected text in their own locale, every
  supported locale is re-rendered together, and no per-recipient row was rewritten

#### Scenario: Somebody tries to move an announcement to another audience

- **WHEN** a request attempts to change a published announcement's audience
- **THEN** it is rejected: part of that audience may already have been emailed, so the change would
  make the record of who was told untrue

### Requirement: Announcement retention is deletion, and markers go with it

An announcement SHALL be purged on its own window, measured from the end of its visibility rather
than from its creation, and its content and every marker for it SHALL go with it. A marker SHALL NOT
outlive the announcement it refers to.

#### Scenario: An announcement ages out

- **WHEN** the retention window elapses after an announcement's visibility ended
- **THEN** the announcement, its content and every dismissal marker for it are gone

#### Scenario: A still-visible announcement

- **WHEN** an announcement's visibility has not ended
- **THEN** it is not purged, whatever its age

#### Scenario: Retention cost does not scale with audience size

- **WHEN** an `EVERYONE` announcement that nobody dismissed is purged
- **THEN** the work is a constant number of rows rather than one per user, which is the property the
  per-recipient fan-out could not have
