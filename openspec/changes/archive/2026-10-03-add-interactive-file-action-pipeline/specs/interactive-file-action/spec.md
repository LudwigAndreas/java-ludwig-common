## Purpose

The platform's one path for a user-submitted file that performs a business action: admission and the
size and format budget, persistence before parsing, the `UPLOADED/VALIDATED/APPLIED` lifecycle and its
mapping onto the operation envelope, bounded-memory reading of untrusted spreadsheets, row addressing
by header name, the commit policy, the row-reject artifacts, and the single place in the platform where
`MultipartFile` may appear.

## ADDED Requirements

### Requirement: The submitted bytes are persisted to the object store before anything parses them
Every submission SHALL write the uploaded content to `ObjectStore` under its SHA-256, computed in the
same pass, before any reader is opened - regardless of size, format or execution mode. The module SHALL
NOT contain a path that parses a file it has not stored.

#### Scenario: A small file is submitted to an INLINE action

- **WHEN** a 40 KiB CSV is posted to an action configured `execution: INLINE`
- **THEN** an object exists under the configured uploads prefix whose key ends with the content's
  SHA-256, and the submission row records that URI
- **AND** the response is `200` with a terminal `OperationResponse`

#### Scenario: The process dies after the upload is stored and before the rows are applied

- **WHEN** the instance handling a `DEFERRED` submission terminates after the object is stored and the
  submission row is written, but before the apply completes
- **THEN** another instance claims the submission by its lease and applies it from the stored object
- **AND** no byte of the submission is read from `java.io.tmpdir`

#### Scenario: A change proposes streaming a small upload without storing it

- **WHEN** a change adds a code path that parses a submission without a prior `ObjectStore.put`
- **THEN** the change is rejected, because the error report, the retry, the audit record and the
  confirm step all read the stored object

### Requirement: Execution mode is configuration and both modes answer with the same envelope
An action SHALL declare `execution: INLINE` or `execution: DEFERRED`. The submit endpoint SHALL answer
with an `OperationResponse` built by `OperationResponses` in both cases, and a client SHALL NOT have to
branch on which mode produced it.

#### Scenario: An INLINE action completes during the request

- **WHEN** a submission to an `INLINE` action is read, bound, validated and applied within the request
- **THEN** the response is `200` carrying a terminal `OperationResponse` with a `result` link

#### Scenario: A DEFERRED action is accepted

- **WHEN** a submission to a `DEFERRED` action is accepted
- **THEN** the response is `202` with `status: PENDING`, the status-resource header and `Retry-After`

#### Scenario: An action is switched from INLINE to DEFERRED in configuration

- **WHEN** only `execution` changes from `INLINE` to `DEFERRED` for an existing action
- **THEN** no client-visible contract changes other than the status code and the added headers, and the
  response type is the same

#### Scenario: An INLINE action is configured above the inline ceiling

- **WHEN** an action declares `execution: INLINE` and a `max-rows` greater than
  `ludwig.file-action.inline.max-rows`
- **THEN** the application fails to start, naming the action and both values

### Requirement: The module's lifecycle is a domain lifecycle and does not restate OperationStatus
`FileActionState` SHALL be `UPLOADED VALIDATED APPLYING APPLIED REJECTED EXPIRED`, SHALL map onto
`OperationStatus`, and SHALL be published in `OperationResponse.detail()`. It SHALL NOT declare a value
that restates any of the six core states.

#### Scenario: A submission is awaiting confirmation

- **WHEN** a `CONFIRM`-mode submission has been validated and nobody has confirmed it
- **THEN** a poll returns `status: PENDING` with `detail: VALIDATED`

#### Scenario: Someone adds a core state to the domain enum

- **WHEN** a change adds `COMPLETED`, `CANCELLED` or `FAILED` to `FileActionState`
- **THEN** `RuleGroup.OPERATIONS` fails the build

#### Scenario: A submission is cancelled after it has already been applied

- **WHEN** `POST /file-actions/{action}/{id}/cancel` names a submission in `APPLIED`
- **THEN** the response is `200` with the terminal envelope, not `409`

#### Scenario: A running submission is cancelled

- **WHEN** `POST /file-actions/{action}/{id}/cancel` names a submission in `APPLYING`
- **THEN** the response is `202`, because the stop is requested cooperatively and not yet achieved

### Requirement: A CONFIRM-mode action applies the rows it showed the user, not a re-read of the file
Under `mode: CONFIRM`, validation SHALL persist the bound rows as an artifact and confirmation SHALL
apply that artifact. The submitted file SHALL NOT be re-read or re-bound at confirmation time.

#### Scenario: Reference data changes between validation and confirmation

- **WHEN** a submission is validated, a reference table the binding coerces against is then changed, and
  the submission is confirmed
- **THEN** the rows applied are exactly the rows the validation response reported

#### Scenario: Nobody confirms within the TTL

- **WHEN** `confirm-ttl` elapses on a `VALIDATED` submission
- **THEN** the submission moves to `EXPIRED`, a poll returns `status: EXPIRED`, and a confirmation
  attempt is refused with a `ProblemDetail`
- **AND** `EXPIRED` is not reported as a failure

#### Scenario: A VALIDATE_ONLY action is confirmed

- **WHEN** `POST .../confirm` names a submission belonging to a `VALIDATE_ONLY` action
- **THEN** the request is refused with a `ProblemDetail`, and nothing is applied

### Requirement: An untrusted spreadsheet is read with bounded memory, and the DOM readers are forbidden
XLSX SHALL be read through POI's `XSSFReader` SAX path. The module SHALL NOT reference `XSSFWorkbook`,
`HSSFWorkbook` or `WorkbookFactory`, and `SXSSFWorkbook` SHALL appear only in the package that writes
templates and annotated reports. Heap use SHALL be bounded by the configured ceiling rather than by the
size of the submitted file.

#### Scenario: A large workbook is read under a heap ceiling

- **WHEN** a 25 MiB workbook of 100,000 rows is submitted
- **THEN** the submission completes within the module's declared heap budget, asserted by an integration
  test that fails if the budget is exceeded

#### Scenario: Someone introduces a DOM read

- **WHEN** a change adds a reference to `XSSFWorkbook`, `HSSFWorkbook` or `WorkbookFactory` anywhere in
  the module
- **THEN** `PoiConfinementTest` fails the build

#### Scenario: Someone materialises the upload

- **WHEN** a change calls `readAllBytes`, `Files.readAllLines`, `MultipartFile.getBytes` or
  `IOUtils.toByteArray` in the module
- **THEN** `NoMaterialisationTest` fails the build

#### Scenario: A zip bomb is submitted

- **WHEN** a workbook whose compression ratio or entry count exceeds the configured ceilings is submitted
- **THEN** it is refused with a `ProblemDetail` before any sheet is parsed, and the submission is
  `REJECTED`

#### Scenario: A legacy .xls workbook is submitted

- **WHEN** a BIFF `.xls` file is submitted
- **THEN** it is refused with a `ProblemDetail` naming `.xlsx` as the supported format, because the
  BIFF reader has no streaming mode and would make the heap a function of a user-supplied file

#### Scenario: The declared content type disagrees with the content

- **WHEN** a CSV file is submitted with a filename of `.xlsx` and an XLSX content type
- **THEN** the format is decided by the content's magic bytes, not by the declared type

#### Scenario: A workbook carries external links

- **WHEN** a submitted workbook contains an `/xl/externalLinks` part
- **THEN** it is refused with a `ProblemDetail` and nothing in it is dereferenced

### Requirement: MultipartFile appears nowhere in the platform except this module
`MultipartFile` SHALL appear only in `file-action-spring-boot-starter`. No other module SHALL declare it
as a parameter, field or return type, because an upload endpoint written by hand is the thing this
module exists to replace.

#### Scenario: A service writes its own upload endpoint

- **WHEN** a controller outside `file-action-spring-boot-starter` declares a `MultipartFile` parameter,
  field or return type
- **THEN** `RuleGroup.UPLOADS` fails that module's build

### Requirement: The author declares a typed binding, and columns are matched by header name
A `RowBinding<R>` SHALL be a code-declared constant naming the sheet, the columns, their header names
and aliases, whether each is required, and the record type the row binds to. Columns SHALL be matched
by header name; no part of the module SHALL address a column by index.

#### Scenario: A user inserts a column

- **WHEN** a user inserts an unmapped column between two mapped columns and submits the file
- **THEN** every mapped value still binds to the same field

#### Scenario: A required column is absent

- **WHEN** a submitted file has no header matching a required column's name or any of its aliases
- **THEN** the whole file is refused with a `ProblemDetail` naming the missing column, and no row is
  reported as rejected

#### Scenario: A value is coerced in the caller's locale

- **WHEN** a cell holds `1 234,56` and the caller's `UserPreferences` locale uses a comma decimal
  separator
- **THEN** it binds to `1234.56`

#### Scenario: A binding references a message key that is missing from a locale

- **WHEN** a `RowBinding` names a column or message key absent from the module's i18n bundle in either
  locale
- **THEN** the application fails to start, naming the key and the locale

#### Scenario: A template is requested

- **WHEN** `GET /file-actions/{action}/template` is called
- **THEN** a workbook is returned whose header row is generated from that action's `RowBinding`, so a
  file built from it binds without a missing-column error

### Requirement: The handler is one of two sealed shapes, and commit policy applies to only one
`FileActionHandler<R>` SHALL be sealed over exactly `RowHandler<R>` - one row at a time, partial success
possible - and `DocumentHandler<R>` - all rows as one business fact. A `DocumentHandler` SHALL be
all-or-nothing by construction, and a row-level commit policy SHALL NOT be configurable for one.

#### Scenario: A DocumentHandler is configured with a row-level commit policy

- **WHEN** an action whose bean is a `DocumentHandler` declares `commit-policy: PER_ROW` or `PER_BATCH`
- **THEN** the application fails to start, naming the action and the handler shape

#### Scenario: A third handler shape is proposed

- **WHEN** a change adds an implementation of `FileActionHandler` that is neither `RowHandler` nor
  `DocumentHandler`
- **THEN** compilation fails, because the interface is sealed

### Requirement: Commit policy is declared per action and has no default
Every action SHALL declare `commit-policy` as one of `ALL_OR_NOTHING`, `PER_BATCH` or `PER_ROW`. The
module SHALL NOT supply a default, because every candidate default silently produces the wrong outcome
for some domain and the symptom is applied business data.

#### Scenario: An action omits the commit policy

- **WHEN** an action is configured without `commit-policy`
- **THEN** the application fails to start, naming the action and the three permitted values

#### Scenario: PER_ROW with one invalid row

- **WHEN** 400 rows are submitted to a `PER_ROW` action and row 14 fails validation
- **THEN** 399 rows are applied, row 14 is recorded as a reject, and the submission reaches `APPLIED`

#### Scenario: ALL_OR_NOTHING with one invalid row

- **WHEN** 400 rows are submitted to an `ALL_OR_NOTHING` action and row 14 fails validation
- **THEN** nothing is applied and the submission reaches `REJECTED`

#### Scenario: PER_BATCH with a failure mid-batch

- **WHEN** a `PER_BATCH` action with `batch-size: 500` fails on row 640
- **THEN** rows 1-500 are applied, rows 501-1000 are not, and the submission reports which batch failed

#### Scenario: Too many rows reject

- **WHEN** the fraction of rejected rows exceeds `reject-threshold`
- **THEN** the submission is `REJECTED` as a whole and nothing is applied, whatever the commit policy,
  because the usual cause is that the file is the wrong file

### Requirement: Row rejects are a paged resource and a downloadable artifact, never a ProblemDetail
A file-level refusal SHALL be reported as a `ProblemDetail` through `web-core`'s pipeline. Row-level
rejects SHALL NOT be: they SHALL be persisted as a bounded sample, served as a `PageResponse`, and
written in full as an artifact when configured.

#### Scenario: Thousands of rows reject

- **WHEN** 3,000 of 10,000 rows reject
- **THEN** the submit response carries the counts and no row list, `GET .../rejects` serves them as a
  `PageResponse`, and the database holds at most the configured sample size

#### Scenario: An annotated workbook is configured

- **WHEN** an action declares `error-report: ANNOTATED_WORKBOOK` and a submission has rejects
- **THEN** `GET .../error-report` serves the submitted workbook with a reject column appended per row,
  in the caller's locale

#### Scenario: A reject is addressed

- **WHEN** a row rejects
- **THEN** the reject names the sheet, the 1-based row number, the column's header name, a stable code
  and a localised message

#### Scenario: A generated report would carry a formula

- **WHEN** a value written into a generated CSV or workbook begins with `=`, `+`, `-` or `@`
- **THEN** it is written so that a spreadsheet application does not evaluate it

### Requirement: Scanning is a declared choice that fails closed
`ludwig.file-action.scanning.mode` SHALL be `required`, `optional` or `disabled`, and SHALL default to
`required`. The module SHALL ship the `FileScanner` SPI and no implementation, and SHALL NOT start with
`required` and no scanner present.

#### Scenario: Scanning is required and no scanner is present

- **WHEN** the application starts with `scanning.mode: required` and no `FileScanner` bean
- **THEN** startup fails, naming the property and the value that disables the requirement

#### Scenario: A scanner rejects a submission

- **WHEN** the configured `FileScanner` reports a submission unsafe
- **THEN** the submission is `REJECTED`, nothing is parsed, the stored object is deleted, and the
  outcome is recorded through `audit-core`'s sink

### Requirement: A re-submitted identical file does not act twice
A submission SHALL be claimed through `IdempotencyStore` on the content's SHA-256, the action and the
caller scope. `IdempotencyFilter` SHALL NOT be applied to the submit endpoints, because it buffers the
whole request body to fingerprint it.

#### Scenario: A user double-submits the same file

- **WHEN** the same content is posted twice to the same action by the same caller
- **THEN** the second request returns the first submission's envelope and nothing is applied twice

#### Scenario: A different file reuses an idempotency key

- **WHEN** a caller supplies an idempotency key already claimed for different content
- **THEN** the request is refused with the fingerprint-mismatch `ProblemDetail` rather than acted on

#### Scenario: The idempotency filter is configured over a submit endpoint

- **WHEN** `IdempotencyEndpointMatcher` would match a file-action submit path
- **THEN** startup fails, because the filter buffers the whole request body and the body here is a file

### Requirement: Submissions above the module's ceiling are refused toward the ingest path
A submission exceeding its action's `max-size` or `max-rows` SHALL be refused with a `ProblemDetail`
that names the limit and names `file-ingest-spring-boot-starter` as the path for a file of that size.
The module SHALL NOT raise its own ceiling to accommodate a scheduled bulk import.

#### Scenario: A file exceeds the configured size

- **WHEN** a file larger than the action's `max-size` is submitted
- **THEN** it is refused with a `ProblemDetail` that names the limit and names
  `file-ingest-spring-boot-starter` as the path for a file of that size
- **AND** the request is refused without the whole body being read into the application

#### Scenario: A file exceeds the configured row count

- **WHEN** a file is read past the action's `max-rows`
- **THEN** reading stops, the submission is `REJECTED`, and the reported row count is the limit rather
  than the file's true length

### Requirement: Retention removes stored uploads and artifacts
Stored uploads and artifacts SHALL be deleted once `storage.retention` has elapsed for a terminal
submission, by a scheduled job holding the `file-action-retention` run lock. A submission whose result
has been removed SHALL report `EXPIRED` rather than a failure.

#### Scenario: Retention elapses

- **WHEN** `storage.retention` has elapsed for a terminal submission
- **THEN** a scheduled job holding the `file-action-retention` run lock deletes its upload and its
  artifacts, and a poll of the submission reports `status: EXPIRED` with no result link

### Requirement: Configuration and code halves are reconciled at startup
A `@FileAction` bean and a configuration block under `ludwig.file-action.actions` SHALL be two halves of
one action, and a mismatch SHALL fail startup rather than the first upload.

#### Scenario: A bean names an unconfigured action

- **WHEN** a `@FileAction("order-import")` bean exists and `ludwig.file-action.actions.order-import` is
  absent
- **THEN** startup fails, naming the action and both halves

#### Scenario: An action requires an authority and security is absent

- **WHEN** an action declares `required-authority` and `security-spring-boot-starter` is not on the
  classpath
- **THEN** startup fails rather than serving the endpoint unprotected
