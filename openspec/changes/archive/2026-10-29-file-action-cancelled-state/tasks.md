## 1. Lifecycle

- [x] 1.1 Add `CANCELLED(OperationStatus.CANCELLED)` to `FileActionState`, update its javadoc diagram and the
      paragraph naming `RuleGroup.OPERATIONS`; verify `mvn -pl :file-action-spring-boot-starter test` (the module's
      ArchUnit test still passes)
- [x] 1.2 Add `FileActionAuditActions.CANCELLED = "file-action.cancelled"`; verify `mvn -pl
      :file-action-spring-boot-starter compile`

## 2. Ending a stopped submission

- [x] 2.1 `stateFor` returns `CANCELLED` for `outcome.cancelled()`, `failureCodeFor` returns null for it, and
      `finish`/`applyFromArtifact` record `file-action.cancelled` instead of `file-action.applied` for a stop; add a unit
      test over `stateFor`; verify `mvn -pl :file-action-spring-boot-starter test`
- [x] 2.2 Checkpoint before the apply on the direct, deferred and confirm paths (design decision 3); verify the
      deferred-before-claim scenario in a new `FileActionCancelIT`, `mvn -pl :file-action-spring-boot-starter verify
      -Dit.test=FileActionCancelIT`
- [x] 2.3 `cancel` and `cancelByAdministrator` end a `VALIDATED` submission `CANCELLED` through a conditional
      transition in `SubmissionStore`, falling back to the flag if the update matches nothing; cover the
      validated-cancel, confirm-after-cancel (409) and cancel-again scenarios in `FileActionCancelIT`; verify as 2.2
- [x] 2.4 Cover the `PER_BATCH` between-batches scenario in `FileActionCancelIT` (a handler that blocks on a latch
      after batch one); verify as 2.2

## 3. Administrative surface

- [x] 3.1 `FileActionAdminIT`: replace the "nothing applied" assertion with `CANCELLED`, and add the `VALIDATED` and
      `?status=CANCELLED` scenarios; verify `mvn -pl :file-action-spring-boot-starter verify -Dit.test=FileActionAdminIT`

## 4. Documentation

- [x] 4.1 README.md and README.ru.md: lifecycle diagram and cancel semantics; verify the two locales have the same
      heading count, `diff <(grep -c '^#' README.md) <(grep -c '^#' README.ru.md)` in the module directory
- [x] 4.2 Remove the file-action item from `openspec/intents/admin-surface-follow-ups.md` §4; verify `openspec
      validate file-action-cancelled-state --strict`

## 5. Verification gate

- [x] 5.1 `scripts/gate.sh --change file-action-cancelled-state file-action-spring-boot-starter` (runs `mvn -q
      validate`, `mvn -pl :file-action-spring-boot-starter -am verify`, `mvn -pl :crud-service-example -am verify`,
      `openspec validate file-action-cancelled-state`)
