## Context

This change touches four unrelated places: repository tooling and three modules (`pat-spring-boot-starter`,
`test-support-local`, and `job-core`'s tests). No module boundary is crossed. A design is needed because two
shared contracts change, and because two of the four tasks begin with an investigation whose outcome is not yet
known.

## Goals / Non-Goals

**Goals:**
- `scripts/gate.sh` runs past its first command.
- A gate failure is a real failure.
- An operator's revoke cannot lose a race with telemetry.

**Non-Goals:** see the proposal.

## Decisions

1. **Image pins: restrict Markdown to code, rather than an exclusion list or a stricter token rule.**
   - Excluding `openspec/changes/archive/` would hide a real tag-only reference in an archived design.
   - Requiring a `/` or `:` in the token would stop flagging a bare postgres image key in real YAML, which is a genuine
     unpinned reference.

   In Markdown, an image reference a reader can copy is always in a code fence or a code span. The checker
   tracks fence state per file and, outside fences, examines only the contents of backtick spans. The other file
   types are unchanged.

   The `--self-test` mirrors `check_migrations.py`: fixtures live in `scripts/testdata/check_image_pins/{good,bad}`,
   and the list of tracked files excludes `scripts/testdata/*`.
2. **PAT last use: a targeted, version-less update.** Today's write does a `findById`, changes three fields and
   saves. That save bumps `AuditedEntity`'s `@Version`, so a revoke that read the row earlier fails with
   `ObjectOptimisticLockingFailureException` at its own save; the IT failed at exactly that line.

   The replacement is a QueryDSL update in `PatQueryRepositoryImpl`, the module's existing custom repository:
   `UPDATE pat SET last_used_at=?, last_used_ip=?, use_count = use_count + 1 WHERE id=?`, run in its own
   transaction and leaving `version` alone. `use_count + 1` in SQL also stops two concurrent uses from losing an
   increment, which the read-modify-save had been doing.

   Leaving the version untouched is safe: those three columns are telemetry, and nothing makes a decision by
   comparing them under optimistic locking. The spec scenario is covered by a deterministic test: an executor
   that parks the write on a latch, a revoke, then the write is released.
3. **`StubFaultIT.slow`: find the cause before choosing a fix.** The working hypothesis comes from the test's own
   comment: WireMock records a request when it *finishes* serving it, and with a 1,500 ms delay against a
   10-second await, the second request should be recorded long before the wait ends. A count of one therefore
   points at the retry not happening, or at the journal being reset between the two requests (`fail()` resets
   state on a context shared with earlier tests), rather than at the wait being too short.

   The task is to reproduce with a loop of runs, identify which of these it is, and fix that cause. Raising the
   timeout does not count as a fix.
4. **`JdbcRunLockIT`: pin the SSL mode the test means.** The failure is `PSQLException: An error occurred while
   setting up the SSL connection`. pgjdbc defaults to `sslmode=prefer` and negotiates SSL with a container that
   was not configured for it. The test builds its own `PGSimpleDataSource`, so it sets `sslMode=disable` there.
   The Spring-managed ITs connect through `@ServiceConnection` and have never shown this failure, so the shared
   `Containers` in `test-support` is not changed. If reproduction shows a different cause, it is recorded in
   `state.json` and the fix follows the cause.

**Dependency-direction check.** Does any touched module gain an in-repo dependency? No. No `<dependency>` element
changes in any POM, so the graph is unchanged and no cycle is possible.

**POMs.** No POM changes: not the root `pom.xml`, `ludwig-bom` or `ludwig-service-parent`.

**Mechanical checks.** The new `--self-test` is the check that the checker keeps working, and it is added to
`gate.sh`'s `COMMANDS` next to `check_image_pins.sh`. Today nothing runs the other three scripts' self-tests either;
that gap is recorded in the follow-ups list rather than widened into this change.

## Risks / Trade-offs

- [An image reference written in plain Markdown prose, outside code, is no longer checked.] → Such a reference is
  not copyable as a command, and `container-image-pinning` concerns references people run. The README convention
  in this repository already puts every one in a fence.
- [The version-less write could overwrite a newer last-used time with an older one.] → Guard it with
  `WHERE last_used_at IS NULL OR last_used_at < :now`. Time only moves forward for the field that matters to
  dormancy detection.
