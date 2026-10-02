## Why

A user drags a spreadsheet into a browser and expects a business action to happen: 400 rows of an
Excel sheet become 400 orders, or one order with 400 lines. The platform cannot do this today, and
the gap is specific rather than general.

Everything around the hole already exists. `export-spring-boot-starter` goes the other way (rows to a
file). `file-ingest-spring-boot-starter` goes this way but is *scheduled*: it discovers an object a
partner dropped in a bucket, parses it against a byte-offset checkpoint, writes staging rows and
merges them into a target with hand-written SQL. Its `RecordApplier` contract is "records to staging
rows plus a `MERGE` statement", which is the right contract for four million rows at six-thirty in the
morning and the wrong one for a person who is waiting for an answer and needs to be told that row 14
has an unknown SKU. `object-storage-spring-boot-starter` has the bucket, `job-core` has the lock and
the backoff, `idempotency-spring-boot-starter` has the claim, `web-core-spring-boot-starter` has the
operation envelope and the caller's locale - and **there is not one `MultipartFile` in the
repository**. The inbound interactive edge is simply absent.

Without a module for it, each service that needs a file upload writes it, and the parts that get
written badly are predictable: `new XSSFWorkbook(in)` on an untrusted upload (a DOM read - a 4 MB
crafted workbook becomes a heap the size of the pod), `MultipartFile.getBytes()`, a row error list
returned as a 400 with three thousand entries in it, dates parsed with `Locale.getDefault()`, and no
record of what was actually uploaded when the user disputes the result three days later.

## What Changes

A new starter, `file-action-spring-boot-starter`, owns the whole path from a multipart POST to a
business action, and a service author writes three things: a typed `RowBinding`, a handler, and a
block of YAML.

- **The bytes always go to the object store first**, before any parsing, under their SHA-256. This is
  not a tier of behaviour - it is the only behaviour. Spring has already spooled the multipart to
  `java.io.tmpdir` by the time a controller method runs, so the "lightweight synchronous" path was
  never streaming from the socket; it was streaming from a pod's ephemeral disk, which cannot be
  retried, audited, re-read to build an error report, or survive the pod dying mid-parse.
- **Synchronous and asynchronous stop being two APIs.** `execution: INLINE | DEFERRED` is a property.
  Both return the same `OperationResponse`; inline is the case where the envelope is already terminal.
  The shared contract already permits exactly this (`long-running-operations`: "A 202 may carry a
  terminal envelope, and a 200 must"), so no contract changes.
- **A three-phase lifecycle**, because "drag in a sheet and 400 orders appear" with no confirm step is
  400 wrong orders and no undo: `UPLOADED -> VALIDATED -> APPLIED`, with `VALIDATED` persisted and
  expiring. `mode: DIRECT | CONFIRM | VALIDATE_ONLY`, defaulting to `CONFIRM`.
- **Row-level errors are a first-class artifact, not a `ProblemDetail`.** A `ProblemDetail` says the
  *file* is unacceptable; a paged error resource plus a downloadable annotated workbook says which
  *rows* are, addressed by sheet, 1-based row number and column *header name*, in the caller's locale.
- **Bounded memory on untrusted input is the module's core guarantee.** XLSX is read through POI's
  `XSSFReader` SAX path only, never a `Workbook` DOM, under explicit ZIP ratio, entry-count and
  uncompressed-size budgets. `.xls` (BIFF) is refused, because `HSSFWorkbook` has no streaming mode at
  all and supporting it would mean an unbounded heap on a user-supplied file.
- **The two declarations the module cannot infer** are forced to be explicit, on the `CachePurpose`
  precedent: `commit-policy` (`ALL_OR_NOTHING | PER_BATCH | PER_ROW` - what happens to the other 399
  rows when row 14 is wrong) and `scanning.mode` (`required | optional | disabled`, defaulting to
  `required` so that a deployment with no scanner fails at startup rather than silently accepting
  unscanned user files).
- **A template endpoint** generates the correct header row from the declared binding, so a user cannot
  get the columns wrong in the first place.
- A working `order-import` is wired into `crud-service-example` and exercised by integration tests, on
  the precedent that `export`'s reference report is.

## Capabilities

### New Capabilities

- `interactive-file-action`: the platform's one path for a user-submitted file that performs a
  business action - admission and the size/format budget, persistence before parsing, the
  `UPLOADED/VALIDATED/APPLIED` lifecycle and its mapping onto the operation envelope, the
  bounded-memory reader confinement, header-name row addressing, the commit policy, the row-reject
  artifacts, and the one place `MultipartFile` may appear in the platform.

### Modified Capabilities

None. This was checked rather than assumed, per contract:

- `long-running-operations` - the module maps onto it and adds nothing. Its existing requirement "A
  202 may carry a terminal envelope, and a 200 must" already covers the inline path, and the
  `UPLOADED/VALIDATED/APPLIED/REJECTED/EXPIRED` lifecycle is a richer domain lifecycle carried in
  `OperationResponse.detail()`, which the spec's second scenario explicitly permits. No new status
  enum restating the six core states.
- `object-store` - uses `put`, `open`, ranged `open` and `list` as they are.
- `problem-detail-pipeline` - contributes mappers, ships no `@RestControllerAdvice`.
- `idempotency-claim` - uses `IdempotencyStore` directly and deliberately **not** `IdempotencyFilter`,
  which its own `CachedBodyRequest` javadoc rules out for bodies "large enough to matter".
- `i18n-bundles`, `data-access`, `test-layout`, `repository-layout`, `enforcement-triad`,
  `pom-topology`, `module-dependency-direction`, `run-lock`, `audit-envelope` - conformed to, not
  changed.

## Impact

### Modules

| Module | POM tier | Change |
|---|---|---|
| `file-action-spring-boot-starter` | **new** library/starter - `sources/file-action-spring-boot-starter`, parented by the reactor root `common` with `<relativePath>../../pom.xml</relativePath>`, imports `ludwig-bom`, package root `ru.ludwigandreas.fileaction` | the whole module |
| `ludwig-bom` | bom (published parentless) | one new third-party version: `commons-csv`. No new XLSX dependency - `poi`/`poi-ooxml` 5.4.1 are already pinned and already ship `XSSFReader` |
| `common` (root `pom.xml`) | parent of the library modules | one `<module>` entry |
| `architecture-rules` | rules library, parent `common` | new `RuleGroup.UPLOADS`: `MultipartFile` may not appear outside this module |
| `crud-service-example` | service, parent `ludwig-service-parent` | reference `order-import` action, Liquibase changeset, integration tests |
| `PROJECT_INDEX.md` / `project-index.json` | - | regenerated by `scripts/manifest.sh build` |

### In-repo dependencies the new module takes

`web-core-spring-boot-starter`, `object-storage-spring-boot-starter`, `db-core`, `job-core`,
`idempotency-spring-boot-starter`, `audit-core` (all compile); `security-spring-boot-starter`,
`observability-spring-boot-starter`, `outbox-spring-boot-starter` (optional);
`test-support`, `test-support-security`, `architecture-rules` (test). The dependency-direction check
is in `design.md`.

### In-repo dependents the gate has to run

- `file-action-spring-boot-starter`: `crud-service-example` (new, after the reference action lands).
- `architecture-rules`: `crud-service-example`, `file-ingest-spring-boot-starter`,
  `messaging-spring-boot-starter`, `notification-service`, `object-storage-spring-boot-starter`,
  `user-settings-spring-boot-starter` (all test scope).
- `ludwig-bom` and the root `pom.xml`: every module. Per `CLAUDE.md` there is no narrower gate than
  `mvn clean install`, and that is what this change's gate is.
- `crud-service-example`: none.

## Non-goals

- **Not a replacement for `file-ingest-spring-boot-starter`, and not a competitor to it.** This module
  is sized for an interactive upload - a configured ceiling, default 25 MiB and 100,000 rows. Above
  the ceiling it refuses with a `ProblemDetail` that names the ingest path. It has no checkpoint, no
  resume, no staging table and no `MERGE`, and it is not to grow them: a resumable byte-offset
  checkpoint on a request a person is waiting for is dead weight, and the two modules' parser
  contracts differ for that reason (byte-addressed and resumable there, header-bound and
  cell-addressed here).
- **No shared operation table**, per the `long-running-operations` contract. This module keeps its own
  two tables and maps onto the envelope at its edge.
- **No `file-format-core` module.** Verified rather than assumed: `file-ingest` ships no concrete
  parser, so there is nothing to consolidate and a shared format module would be speculative. The
  condition under which it should be created is recorded in `design.md`.
- **No antivirus implementation.** A `FileScanner` SPI with no shipped implementation; ICAP, clamd and
  a cloud scanner are deployment choices.
- **No `.xls`, no `.ods`, no `.xml`, no PDF, no archive-of-files upload.** `xlsx` and `csv` only, and
  the allow-list is closed.
- **No UI.** The module ships HTTP endpoints and a template generator, not a drag-and-drop widget.
- **No reuse of `export`'s `ReportWriter`.** The deliberate, named duplication and its reason are in
  `design.md`.
- **No change to any existing shared contract**, per the section above.
