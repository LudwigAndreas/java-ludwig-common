## Context

Required: this change adds a module, crosses several module boundaries and introduces conventions.

The constraints that shape every decision below:

- `web-core-spring-boot-starter` has **zero in-repo dependencies** and must keep none to persistence.
  `OperationResponse`'s own javadoc says a `web-core -> db-core` edge "would be a reactor cycle waiting
  for the first module that needs both". So this cannot be "web-core with file processing", which is how
  the request was first framed. It is a new module **above** web-core.
- `MultipartFile` is already spooled by Spring's `StandardServletMultipartResolver` to
  `java.io.tmpdir` (or memory, under `spring.servlet.multipart.file-size-threshold`) before a handler
  method is entered. Nothing a controller does makes the HTTP edge streaming.
- POI's `XSSFWorkbook` is a DOM reader. `poi-ooxml` also ships `XSSFReader`, `ReadOnlySharedStringsTable`
  and `XSSFSheetXMLHandler`, which together are a SAX pull over one sheet at a time.
- `export-spring-boot-starter` already calls `new XSSFWorkbook(in)` legitimately, for an
  administrator-supplied XLSX template, and `ludwig-bom`'s own comment on `poi.version` records that
  this is accepted as trusted-ish input on the write path.
- `idempotency-spring-boot-starter`'s `CachedBodyRequest` javadoc already forbids its filter on large
  bodies in so many words.

## Goals / Non-Goals

**Goals:**

- One mechanism for a user-submitted file performing a business action, with the HTTP shape, the
  lifecycle, the error reporting and the memory guarantee owned by the module.
- A service author writes a typed binding, a handler, and YAML. Nothing else.
- Bounded heap on untrusted input, enforced by a test rather than asserted in a README.
- Both the synchronous and the deferred shape behind one response contract.

**Non-Goals:** as `proposal.md` lists them. In particular: no checkpoint, no resume, no staging table,
no `.xls`, no shipped scanner, no `file-format-core`.

## Decisions

### 1. The bytes are persisted before anything parses them, always

`ObjectStore.put` to `{uploads-prefix}/{yyyy}/{MM}/{dd}/{sha256}`, computed with a
`DigestInputStream` while the bytes are being written, so the file is read once.

The rejected alternative is the one originally proposed - stream small files straight through and only
persist the large ones. It fails on five counts, and the first is decisive: the "streaming" path is
already reading a spooled temp file, so it saves no write, it only picks the worst storage for it.
Beyond that, a request that has not persisted its input cannot be retried, cannot produce an error
report that cites the original, cannot answer a dispute, and cannot survive the pod being evicted
mid-parse while the user's browser holds an open connection.

`FilesystemObjectStore` exists, so "always the object store" does not impose S3 on a small deployment.

Retention is the module's job: `ludwig.file-action.storage.retention` (default `P7D`) drives a
`job-core` `ScheduledJob` holding the `RunLock` named `file-action-retention`, which deletes expired
uploads and artifacts and moves their submissions to `EXPIRED`.

### 2. `INLINE` vs `DEFERRED` is a property, not a second API

One submit endpoint, one response type. `OperationResponses` already refuses the malformed
combinations, and the `long-running-operations` requirement "A 202 may carry a terminal envelope, and a
200 must" is exactly this case - inline returns `200` with a terminal envelope, deferred returns `202`
with `PENDING` plus the status-resource header and `Retry-After`.

Consequences that make this worth it: a deployment that discovers its inline action is too slow flips
one property with no client change, and the client never branches on which mode it got. A guard rail
belongs with it - an inline action whose configured `max-rows` exceeds
`ludwig.file-action.inline.max-rows` (default 5,000) is **refused at startup**, because an inline
action sized for 100,000 rows is a request timeout and a held connection, discovered in production.

Deferred execution claims work with `SkipLockedClaim` and a `job-core` lease, so a pod dying mid-apply
releases the submission to another instance rather than wedging it.

### 3. The lifecycle is a domain lifecycle mapped onto the envelope, never a second status enum

```
                      ┌───────────┐
   POST ─── admit ───▶│ UPLOADED  │ PENDING / RUNNING
                      └─────┬─────┘
                            │ read + bind + validate
              ┌─────────────┼───────────────┐
              ▼             ▼               ▼
        ┌──────────┐  ┌───────────┐   ┌──────────┐
        │ REJECTED │  │ VALIDATED │   │ APPLYING │   (mode: DIRECT skips VALIDATED)
        └──────────┘  └─────┬─────┘   └────┬─────┘
          FAILED            │ confirm      │
                            ├──────────────┘
                  expires   │              ▼
                      ┌─────▼────┐   ┌──────────┐
                      │ EXPIRED  │   │ APPLIED  │ SUCCEEDED
                      └──────────┘   └──────────┘
                        EXPIRED        (or FAILED)
```

`FileActionState` has five values none of which restate `OperationStatus`'s six, and it is carried in
`OperationResponse.detail()`. This is the same shape as `reconciliation`'s `RemoteJobState`, which the
`long-running-operations` spec names as the permitted case. `RuleGroup.OPERATIONS` is what fails the
build if someone later adds `COMPLETED` or `CANCELLED` to it.

`VALIDATED` is persisted and expiring because it has to survive between two HTTP requests. What it
persists is the *bound rows*, not the raw file again: a second object under
`{artifacts-prefix}/{submission}/bound.ndjson`, so confirm does not re-read and re-bind the workbook
and therefore cannot produce a different answer from the one the user was shown. Re-binding on confirm
is the subtle bug this avoids - a changed reference table between validate and confirm would silently
apply something other than the preview.

`mode: CONFIRM` is the default. `DIRECT` exists for machine callers and for genuinely reversible
actions; `VALIDATE_ONLY` never applies anything and is what a UI calls for inline feedback.

### 4. The author writes a binding and one of two sealed handler shapes

`RowBinding<R>` is a typed constant, on the precedent `export` sets and for the reason its README
gives: a definition declared as data is "a second, untested query layer with no type checking". Columns
are matched by **header name** with declared aliases, never by index, so a user inserting a column does
not silently shift every mapping. `R` is a record carrying Bean Validation annotations; coercion runs
through `web-core`'s `UserPreferenceFormatter` so `1 234,56` and `01.02.2026` mean what the caller's
locale says they mean.

```java
public static final RowBinding<OrderLine> ORDER_LINES = RowBinding.of(OrderLine.class)
        .sheet("Orders")
        .column("sku",      "SKU").aliases("Article", "Артикул").required()
        .column("quantity", "Qty").required()
        .column("comment",  "Comment").optional()
        .build();
```

The handler is **sealed on two shapes**, and the sealing is the point:

```
sealed interface FileActionHandler<R>
   ├── RowHandler<R>       apply(R row, FileActionContext) -> per-row outcome
   └── DocumentHandler<R>  apply(Stream<R> rows, FileActionContext) -> one outcome
```

`RowHandler` is "400 rows become 400 orders" and is the shape that can have a partial success.
`DocumentHandler` is "400 rows become one order with 400 lines" - an aggregate, where a partial
result is meaningless. This distinction is why `file-ingest`'s per-record-only `RecordApplier` is not
the right contract here: that interface is per-record precisely so one bad record is isolated, which is
correct for a staging import and wrong for a document that is one business fact.

The consequence worth stating: **`commit-policy` applies only to `RowHandler`.** A `DocumentHandler` is
`ALL_OR_NOTHING` by construction, and configuring anything else for one is refused at startup rather
than quietly ignored.

There is no third shape. `Fetcher` in `reconciliation` is sealed for the same reason.

### 5. `commit-policy` is the declaration the module cannot infer

Modelled on `CachePurpose`, which `CLAUDE.md` singles out as "the only mistake the module cannot detect
for you".

| policy | one bad row | right for |
|---|---|---|
| `ALL_OR_NOTHING` | nothing is applied | a journal entry, a trial balance - anything where a partial result is a wrong result |
| `PER_BATCH` | that batch rolls back, earlier batches stand | a bulk price update, where restarting from the failed batch is cheap |
| `PER_ROW` | that row is rejected, the rest apply | an order import, a contact list |

There is no default. An action that does not declare one is refused at startup, because every possible
default is silently wrong for some caller and the failure mode is "400 orders were created from a sheet
the user expected to be rejected".

Orthogonally, `reject-threshold` (default `0.1`) aborts the whole submission when more than that
fraction of rows reject - the "wrong file entirely" case, where applying the 60% that happened to parse
is never what anyone wanted.

### 6. Bounded memory on untrusted input, and the check that enforces it

XLSX is a ZIP of XML, supplied by a user. The reader:

- opens through `ZipSecureFile` with an explicit `setMinInflateRatio` and entry-count ceiling, so a zip
  bomb is refused before any parsing;
- walks sheets with `XSSFReader` + `XSSFSheetXMLHandler`, never a `Workbook`;
- refuses a workbook whose `/xl/externalLinks` part is present;
- caps the shared-strings table, which `ReadOnlySharedStringsTable` materialises, under the declared
  `max-size` budget;
- sniffs magic bytes (`PK\x03\x04`) rather than trusting the declared content type, because users
  rename `.csv` to `.xlsx` routinely;
- refuses BIFF (`.xls`): `HSSFWorkbook` has no streaming mode, so supporting it would mean a heap
  proportional to a user-supplied file, which is the one guarantee this module exists to make.

On the way back out, a value beginning `=`, `+`, `-` or `@` in a generated CSV or annotated workbook is
prefixed, because a CSV the user re-opens in Excel is a formula-injection sink.

**Checks (encode every rule twice):**

| rule | check | owner |
|---|---|---|
| no `Workbook` DOM read in this module | `PoiConfinementTest` - ArchUnit, module-local: no reference to `XSSFWorkbook`, `HSSFWorkbook`, `WorkbookFactory`; `SXSSFWorkbook` confined to `...fileaction.format.xlsx.write` | `architecture-rules` style, module-local test |
| nothing is materialised | `NoMaterialisationTest` - ArchUnit, module-local: no `readAllBytes`, `Files.readAllLines`, `MultipartFile.getBytes`, `IOUtils.toByteArray` | module-local test |
| the memory guarantee actually holds | `LargeWorkbookHeapIT` - a 25 MiB, 100,000-row workbook under a measured heap ceiling, on the precedent of `file-ingest`'s 200 MB test | failsafe IT |
| `MultipartFile` appears nowhere else in the platform | **new `RuleGroup.UPLOADS`** in `architecture-rules` | ArchUnit |
| no status enum restating the six core states | existing `RuleGroup.OPERATIONS` | ArchUnit |
| no second cache SPI / `Caffeine` builder | existing `RuleGroup.CACHING` | ArchUnit |
| no `Locale.getDefault()` / `ZoneId.systemDefault()` | existing `RuleGroup.PRESENTATION` | ArchUnit |
| no module-local audit SPI or second redaction mask | existing `RuleGroup.AUDIT` + Checkstyle `SecondRedactionMask` | both |
| no hard-coded user-facing text | existing Checkstyle `NonAsciiSourceText` | Checkstyle |

The POI confinement is **module-local rather than a platform `RuleGroup`** because `export`
legitimately DOM-reads an administrator-supplied template, so a platform-wide ban would be red on a
module that is correct. This is the `SqlConfinementTest` precedent exactly.

**No new Checkstyle rule.** Every rule this change introduces is a bytecode fact (a type reference, a
method call, a dependency) or a startup-time configuration fact, and the triad says ArchUnit owns the
first and nothing owns the second. Which brings us to:

**Startup validation, for the rules neither ArchUnit nor Checkstyle can see.**
`FileActionConfigurationValidator` refuses the application, on the precedent of
`FileIngestConfigurationValidator`:

- a `@FileAction` bean naming a key with no configuration, or a configured key with no bean;
- an action with no `commit-policy`;
- a `commit-policy` other than `ALL_OR_NOTHING` on a `DocumentHandler`;
- `scanning.mode: required` with no `FileScanner` bean;
- an `INLINE` action whose `max-rows` exceeds the inline ceiling;
- a `RowBinding` whose column or message keys are absent from the module's i18n bundle, in either
  locale.

**Not mechanisable, and therefore carrying a comment at the point of the rule** (reported rather than
hidden, because this list is the backlog):

- a `RowHandler` must be idempotent per row - a retried batch re-applies rows whose transaction
  committed before the pod died. Nothing in bytecode can see this; it is stated on `RowHandler.apply`.
- a `DocumentHandler` must not call a partner service inside the apply transaction. Same reason,
  stated on the method.
- whether a declared `commit-policy` is the *right* one for the domain. Startup validation can force
  the declaration; only a human can judge it.
- whether a deployment's `FileScanner` actually scans. `scanning.mode` forces the choice to be made;
  it cannot verify the implementation.

### 7. Idempotency is the content hash, and the existing filter is deliberately not used

Key: `sha256(content) + action + caller scope`, claimed through `IdempotencyStore` with
`ClaimMode`, scope `IdempotencyScopes` entry `file-action:{action}`. This makes the common accident -
a user double-clicking the drop zone, or a browser retrying the POST - return the first submission's
envelope rather than creating 400 orders twice.

`IdempotencyFilter` is **not** used, and not for stylistic reasons: its `CachedBodyRequest` buffers the
whole request body to fingerprint it, and the class's own javadoc says "Buffering a request body in
memory is exactly the mistake `file-ingest` exists to avoid... An endpoint receiving bodies large
enough to matter is not one where this filter should be reading them." A 25 MiB multipart is such a
body. The module therefore uses the store's API directly, with the hash it is already computing while
streaming to the object store - one pass, no buffer. `IdempotencyEndpointMatcher` must exclude the
submit endpoints, and a startup check asserts it.

### 8. Row errors are an artifact, not a `ProblemDetail`

`ProblemDetail` answers "this file is not acceptable" - too large, wrong format, failed the scanner,
unreadable ZIP, missing a required column. Those are per-file, few, and belong in `web-core`'s single
RFC 9457 pipeline, which this module contributes mappers to.

Row rejects are a different thing: there can be thousands, they are paged, they are downloaded, and
they are addressed. A 400 carrying 3,000 entries is not a usable API.

- `file_action_row_reject` stores a **bounded** sample (default 100, configurable) - counts and the
  first N. An unbounded reject table is 100,000 rows per bad upload.
- The full set is an artifact: `error-report: NONE | CSV | ANNOTATED_WORKBOOK`. The annotated workbook
  is the submitted file with a reject column appended per row, which is the form a user can fix and
  re-submit.
- Addressing is `sheet`, 1-based `row`, column **header name**, a stable `code`, and an i18n message
  key with arguments - resolved in the caller's locale from `UserPreferences.current()`.

**The writer is this module's own, not `export`'s**, and the duplication is deliberate: `export`'s
`ReportWriter` is bound to `ReportDefinition`, `WriterContext`, parameter records and role-filtered
column sets, and an error report is none of those things. Depending on `export-spring-boot-starter`
would pull its engine and eleven optional dependencies in to reuse one `SXSSFWorkbook` wrapper. What is
shared and what is not is recorded in a comment on the writer. If a third consumer of streaming XLSX
*writing* appears, that is the point at which a shared format module is justified - not before.

### 9. Why not a `file-format-core`, and the condition that would change it

Checked, not assumed: `file-ingest-spring-boot-starter` ships **no** concrete parser - `RecordParser`
is an SPI and every implementation lives in a consuming service. `export` ships writers. So there is no
existing CSV or XLSX reader anywhere in the platform, and this module is the first. There is nothing to
consolidate, and extracting a shared module for a single consumer is the speculative-generality
mistake rather than the one-mechanism rule.

The two record contracts are genuinely different, which is the stronger argument: `RecordParser`
reports absolute byte offsets so a ranged-GET checkpoint can resume mid-object, and declares a
`CheckpointKind`. Neither has any meaning for a 400-row upload a person is waiting for. This module's
reader is header-bound and cell-addressed because its output has to be *shown to the user*, which
`RecordParser` has no vocabulary for.

CSV *dialect* looks like a near-duplicate of `export`'s `CsvProfile`, and is not: the write side needs
determinism (a fixed CRLF, a chosen BOM, a locale-driven delimiter) and the read side needs tolerance
(strip any BOM, sniff the delimiter, accept ragged rows and report them as rejects). Sharing a record
between them would force one side's concerns on the other. A comment at the reader's dialect type says
this, so the next reader does not "fix" it.

**The condition for promotion**, recorded so it is a decision rather than a drift: if a second module
needs to *read* CSV or XLSX, the codec layer (`zip` safety, the CSV lexer, the `XSSFReader` walk) moves
to a new `file-format-core` at that point, and the record contracts stay separate.

### 10. The HTTP surface, auto-registered per action

```
POST   /file-actions/{action}                    multipart → 200 | 202  OperationResponse
GET    /file-actions/{action}/{id}               → OperationResponse (+ the module's own detail)
POST   /file-actions/{action}/{id}/confirm       → 200 | 202
POST   /file-actions/{action}/{id}/cancel        → 202   (cooperative, per the contract)
GET    /file-actions/{action}/{id}/rejects       → PageResponse<RowReject>
GET    /file-actions/{action}/{id}/error-report  → the artifact
GET    /file-actions/{action}/template           → a blank workbook built from the RowBinding
```

The template endpoint is the cheapest usability win available: the header row is generated from the
same declared binding the reader validates against, so the two cannot disagree.

`cancel` answers **202, not 204**, and cancelling an already-terminal submission returns the envelope
rather than a 409 - both because the shared contract requires it. Each action's
`required-authority` is checked through `security-spring-boot-starter`, which is optional here, so an
action configured with an authority while security is absent is refused at startup.

### Configuration

```yaml
ludwig:
  file-action:
    scanning:
      mode: required                 # required | optional | disabled - no safe default, fails closed
    storage:
      uploads: s3://orders/file-action/uploads
      artifacts: s3://orders/file-action/artifacts
      retention: P7D
    inline:
      max-rows: 5000                 # ceiling an INLINE action may not exceed
    defaults:
      max-size: 25MB
      max-rows: 100000
      formats: [xlsx, csv]
      reject-sample: 100
      reject-threshold: 0.1
    actions:
      order-import:
        mode: CONFIRM                # DIRECT | CONFIRM | VALIDATE_ONLY
        execution: INLINE            # INLINE | DEFERRED
        commit-policy: PER_ROW       # no default; refused at startup if absent
        batch-size: 500
        max-size: 10MB
        max-rows: 20000
        confirm-ttl: PT30M
        error-report: ANNOTATED_WORKBOOK
        required-authority: ORDER_IMPORT
```

### Dependency-direction check

Written out as the question and the answer, per contract. The module is new, so the only risk is that
something it depends on already depends on it - impossible for a new artifact - or that it is added as
a dependency of a module one of its own dependencies needs. `project-index.json` `inRepoDependents`:

- **Does `web-core-spring-boot-starter` already depend on `file-action-spring-boot-starter`?** Its
  `inRepoDependencies` is `[]`. No.
- **Does `object-storage-spring-boot-starter`?** Its dependencies are `web-core-spring-boot-starter`,
  plus `test-support` and `architecture-rules` at test scope. No.
- **Does `db-core`?** `test-support` (test) and `web-core-spring-boot-starter` (optional compile). No.
- **Does `job-core`?** `test-support` (test) only. No.
- **Does `idempotency-spring-boot-starter`?** `audit-core`, `db-core`, `job-core`,
  `web-core-spring-boot-starter`, `test-support`. No.
- **Does `audit-core`?** `[]`. No.
- **Does `security-spring-boot-starter`?** `audit-core`, `cache-spring-boot-starter`,
  `web-core-spring-boot-starter`. No.
- **Does `crud-service-example` become a cycle?** Its `inRepoDependents` is `[]`; it is a leaf service.
  Adding `file-action-spring-boot-starter` to it is a new edge into a leaf. No.
- **`architecture-rules`** has `inRepoDependencies` `[]` and is consumed at test scope only, so adding
  `RuleGroup.UPLOADS` creates no edge at all.

No cycle. The new module sits at the same tier as `export-spring-boot-starter`, which takes a very
similar dependency set, and like `export` it is depended on only by a service.

### Which of the three POMs changes

- **`build/ludwig-bom/pom.xml`** - one new third-party version, `commons-csv`. Nothing else: `poi` and
  `poi-ooxml` 5.4.1 are already pinned and already carry `XSSFReader`, and the BOM's existing comment
  that the two must never be split across versions continues to apply. **No** new XLSX library, **no**
  Tika (see below).
- **`pom.xml` (root)** - one `<module>sources/file-action-spring-boot-starter</module>` entry. No new
  plugin and no new build configuration: the module inherits the libraries' build from the reactor root
  as every other starter does.
- **`build/ludwig-service-parent/pom.xml`** - **no change**. This is a library, not a service.

The new module's own POM is parented by `common` with `<relativePath>../../pom.xml</relativePath>` and
imports `ludwig-bom`, per `pom-topology` and the trap `CLAUDE.md` records about Maven silently
resolving a stale parent from `~/.m2`.

### Rejected alternatives worth recording

- **Put it in `web-core`.** Would force `web-core -> db-core` and `web-core -> object-storage`, which
  its own javadoc identifies as a reactor cycle waiting to happen. This is why "extend web-core" became
  "depend on web-core".
- **Extend `file-ingest`.** Would drag staging tables, scheduling, `COPY` and a SQL carve-out into an
  interactive web path, and would force this module's user-facing row addressing into a contract built
  around byte-offset resumption. `file-ingest`'s own README makes this argument against merging with
  `reconciliation`; the same argument applies here.
- **Apache Tika for content detection.** Tika is the right answer for an open-ended corpus. The
  allow-list here is closed and has two entries, so the detection is a magic-byte check of `PK\x03\x04`
  plus a decode probe - and `tika-core` would add a dependency whose job is parsing untrusted bytes,
  which is the surface this module is trying to keep small. Recorded at the sniffer.
- **`excel-streaming-reader` / `pjfanning` fork.** Unnecessary: `poi-ooxml` already ships the SAX path,
  and a second XLSX library is a second answer to "what does a malformed workbook do".
- **A shared operation table.** Forbidden by `long-running-operations`, and the reasons there apply
  unchanged.

## Risks / Trade-offs

- **The reference `order-import` makes `crud-service-example` bigger.** It is already the reference for
  `export`, `odata-filter`, `outbox` and `security`, and a starter that claims "out of the box" with no
  working consumer is a starter whose first real consumer discovers the gaps. Accepted.
- **`scanning.mode: required` by default breaks a naive first boot.** Deliberate: failing closed with a
  message naming the property is the correct trade against a deployment that silently accepts unscanned
  user files. The startup message names `disabled` explicitly so the escape is one line.
- **The annotated-workbook error report re-reads the submitted file.** It must, to annotate it, and it
  is the one place a second pass over the upload happens. Bounded by the same ceiling, streamed
  through the same `XSSFReader` path, and produced asynchronously even for an `INLINE` action.
- **`bound.ndjson` doubles the stored bytes for a `CONFIRM` action.** The alternative - re-binding on
  confirm - can apply something other than what the user approved. The bytes are cheaper than that bug,
  and retention cleans both.
- **A 25 MiB / 100,000-row ceiling will be hit** and someone will want it raised rather than moving to
  `file-ingest`. The ceiling is configurable, the `LargeWorkbookHeapIT` budget is what makes raising it
  an evidenced decision rather than a hopeful one, and the refusal `ProblemDetail` names the ingest
  path so the alternative is discoverable.
- **`DocumentHandler` invites a long apply transaction.** Mitigated by the batch bound, the javadoc on
  the method, and `DEFERRED` being the recommended execution for a large `DocumentHandler` - but not
  mechanically prevented, and listed above as such.
