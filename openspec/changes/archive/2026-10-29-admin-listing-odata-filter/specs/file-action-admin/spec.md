## MODIFIED Requirements

### Requirement: Every submitter's submissions are readable

`file-action-spring-boot-starter` SHALL declare `fileactions.read`, `fileactions.operate` and
`fileactions.privileged`, and serve under `fileactions.read`:

- `GET /admin/v1/fileactions/submissions?$filter=&cursor=`;
- `GET /admin/v1/fileactions/submissions/{id}` - the submission in the platform operation envelope with its
  submitter, action and reject count, without the file or any rejected row's content.

#### Scenario: Support looks at a user's failed import
- **WHEN** a caller holding `fileactions.read` opens another user's submission
- **THEN** its status, counts and failure are returned, and no row content is

## ADDED Requirements

### Requirement: Submissions are filterable on their action, submitter and outcome
`$filter` on the administrative submissions listing SHALL accept `action`, `state`, `submittedBy`, `sourceFormat`,
`failureCode`, `rowsRejected`, `submittedAt`, `startedAt` and `finishedAt`. The object location, the declared file
name and the failure arguments SHALL NOT be filterable.

#### Scenario: Imports that rejected rows
- **WHEN** an operator lists `$filter=action eq 'product-import' and rowsRejected gt 0`
- **THEN** only product-import submissions that rejected at least one row are returned
