## MODIFIED Requirements

### Requirement: Every submitter's submissions are readable

`file-action-spring-boot-starter` SHALL declare `fileaction.submission.read` (`READ`) and
`fileaction.submission.cancel` (`CHANGE`) on the `ADMIN` surface, and serve under `fileaction.submission.read`:

- `GET /admin/v1/fileactions/submissions?$filter=&cursor=`;
- `GET /admin/v1/fileactions/submissions/{id}` - the submission in the platform operation envelope with its
  submitter, action and reject count, without the file or any rejected row's content.

#### Scenario: Support looks at a user's failed import
- **WHEN** a caller holding `fileaction.submission.read` opens another user's submission
- **THEN** its status, counts and failure are returned, and no row content is

### Requirement: Any submission can be cancelled

`POST /admin/v1/fileactions/submissions/{id}/cancel` (`fileaction.submission.cancel`) SHALL cancel exactly as the
submitter's own cancel does: `202` with the envelope while the stop is pending, `200` with the terminal envelope
once the submission is terminal, including a submission it ended `CANCELLED` itself.

#### Scenario: An administrator cancels a deferred submission
- **WHEN** an administrator cancels another user's deferred submission before the worker applies it
- **THEN** the response is `202`, the deferred worker does not apply it, the submission ends `CANCELLED`, and the
  audit events name the actor and the submitter

#### Scenario: An administrator cancels a submission awaiting confirmation
- **WHEN** an administrator cancels another user's `VALIDATED` submission
- **THEN** the response is `200` with `status: CANCELLED`

#### Scenario: Listing cancelled submissions
- **WHEN** `GET /admin/v1/fileactions/submissions?status=CANCELLED` is called after a submission was cancelled
- **THEN** that submission is in the page
