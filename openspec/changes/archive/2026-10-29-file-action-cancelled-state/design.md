## Context

The change stays inside `file-action-spring-boot-starter`; no module boundary is crossed and no dependency is
added. A design is written because it changes two shared contracts (`interactive-file-action`,
`file-action-admin`) and has a concurrency edge: a cancel racing the worker.

Today a cancel only sets `cancellation_requested`. `ApplyPass` checks the flag between batches and returns
`ApplyOutcome(cancelled = true)`. Both callers (`FileActionService.finish` for the direct and deferred paths,
`applyFromArtifact` for confirm) map the outcome through `stateFor`, which never reads `cancelled`.

## Goals / Non-Goals

**Goals:** a stop is recorded as a stop, in the row, the envelope and the audit trail, on every path that can
stop: direct, deferred and confirm.

**Non-Goals:** see the proposal. Additionally, no new column; the flag and the state are enough.

## Decisions

1. **`CANCELLED` as a `FileActionState` value, mapped to `OperationStatus.CANCELLED`.** The user chose this
   over `REJECTED` with a `cancelled` code, which would make a requested stop read as a failure
   (`isFailure()`), and over leaving the domain state alone with only the envelope changing, which would be
   impossible because `detail()` is the state name. It is the same pattern `EXPIRED` already uses.
   `OperationVocabularyRules` keeps passing: clause 1 needs *every* constant to be a vocabulary word.
2. **`stateFor` checks `cancelled()` first.** A stopped outcome is `CANCELLED` whatever was applied before it.
   `failureCodeFor` returns null for it, because a stop is not a failure.
3. **A checkpoint before the apply.** `FileActionService` checks the flag after binding and before calling
   `ApplyPass` (and before `applyFromArtifact` transitions to `APPLYING`), ending the submission `CANCELLED` with
   `rowsApplied: 0`. Without it, an `ALL_OR_NOTHING` or document action, which has no between-batch checkpoint,
   would ignore a cancel that arrived while it was queued.
4. **`VALIDATED` ends at once.** Nothing is running on it, so waiting for a checkpoint would leave it to expire as
   `EXPIRED`. The cancel transitions it to `CANCELLED` in the same transaction that sets the flag, through a
   conditional update (`WHERE state = 'VALIDATED'`) in `FileActionSubmissionQueryRepositoryImpl`, the same QueryDSL
   update shape the deferred claim already uses. If a concurrent confirm moved it to `APPLYING` first, the update
   matches no row and the cancel falls back to the flag and `202`. The entity's `@Version` would also catch the race,
   but as an exception rather than an answer.
5. **Audit.** A new `FileActionAuditActions.CANCELLED = "file-action.cancelled"`, recorded once, when the state is
   written, with `success = true`. `file-action.cancellation-requested` is unchanged: it records the request,
   this records the outcome.
6. **No new mechanical check.** The convention ("a domain lifecycle maps onto the core") is already owned by
   `architecture-rules` (`RuleGroup.OPERATIONS`). The change only corrects the spec's description of that rule.

**Dependency-direction check.** Does `file-action-spring-boot-starter` gain an in-repo dependency? No. The only
in-repo dependent is `crud-service-example`, and no edge changes, so no cycle is possible.

**POMs.** No POM changes: not `pom.xml`, `ludwig-bom` or `ludwig-service-parent`.

## Risks / Trade-offs

- [A consumer has an exhaustive `switch` over `FileActionState`.] → It no longer compiles, which is the intent of a
  major release. The README and CHANGELOG call it out.
- [A cancel and a confirm race on `VALIDATED`.] → The conditional update in decision 4 means exactly one wins. The
  loser sees the other's state: confirm gets `409`, or cancel gets `202` and the flag.
- [Historical rows stay `APPLIED` after a cancel.] → Accepted (non-goal). Those rows have `cancellation_requested
  = true` and can be found by SQL if anybody needs them.

## Migration Plan

Deploy as part of the major release. Rolling back: an older replica reading a `CANCELLED` row fails to map the
enum. Roll back only together with a `UPDATE file_action_submission SET state = 'APPLIED' WHERE state =
'CANCELLED'`, which restores today's (wrong) recording.
