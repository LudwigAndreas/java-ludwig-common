## Purpose

Gives administrators a view of every submitter's file-action submissions and the means to cancel one, without
exposing the uploaded file or its rejected rows.

## ADDED Requirements

### Requirement: Every submitter's submissions are readable

`file-action-spring-boot-starter` SHALL declare `fileactions.read`, `fileactions.operate` and
`fileactions.privileged`, and serve under `fileactions.read`:

- `GET /admin/v1/fileactions/submissions?action=&submitter=&status=&cursor=`;
- `GET /admin/v1/fileactions/submissions/{id}` - the submission in the platform operation envelope with its
  submitter, action and reject count, without the file or any rejected row's content.

#### Scenario: Support looks at a user's failed import
- **WHEN** a caller holding `fileactions.read` opens another user's submission
- **THEN** its status, counts and failure are returned, and no row content is

### Requirement: Any submission can be cancelled

`POST /admin/v1/fileactions/submissions/{id}/cancel` (`fileactions.operate`) SHALL cancel exactly as the
submitter's own cancel does and answer `202` with the envelope; a terminal submission SHALL return its envelope.

#### Scenario: An administrator cancels a deferred submission
- **WHEN** an administrator cancels another user's deferred submission
- **THEN** the response is `202`, the deferred worker does not apply it, and the audit event names the actor and the
  submitter
