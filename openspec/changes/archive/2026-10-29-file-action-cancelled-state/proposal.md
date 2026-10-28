## Why

A cancelled file-action submission is recorded as `APPLIED` (envelope `SUCCEEDED`) with zero rows and a successful
`file-action.applied` audit event. `ApplyPass` honours the cancel flag, but `FileActionService.stateFor` ignores
`ApplyOutcome.cancelled()`. The submitter's own cancel and the administrator's cancel
(`/admin/v1/fileactions/submissions/{id}/cancel`) are both affected: the client, the audit trail and support all
see a success that did not happen. Found while implementing `starter-admin-endpoints`.

Ships in the next major release; the breaking change below is permitted.

## What Changes

- `FileActionState` gains a terminal `CANCELLED`, mapped to `OperationStatus.CANCELLED`.
- A submission whose apply stopped on a cancel request ends `CANCELLED`, keeping the rows applied before the
  stop in `rowsApplied`. That count can be non-zero under `PER_BATCH`, because a stop happens between batches.
- A cancel of a submission that nothing is working on (`VALIDATED`, waiting for confirmation) ends it
  `CANCELLED` at once and answers `200` with the terminal envelope. Confirming it afterwards is refused with
  `409`, as for any terminal submission.
- A cancel of an `UPLOADED` submission (deferred, not yet claimed) or an `APPLYING` one still answers `202`. The
  worker ends it `CANCELLED` at its next checkpoint, and that checkpoint now also comes before the apply starts,
  not only between batches.
- A stop is audited as `file-action.cancelled`, never as `file-action.applied`.
- **BREAKING**: `FileActionState` gains `CANCELLED`. A consumer switching over the enum, or over
  `OperationResponse.detail()`, must handle it.
- Spec finding fixed: `interactive-file-action` says adding `CANCELLED` to `FileActionState` fails
  `RuleGroup.OPERATIONS`. It does not. `OperationVocabularyRules` flags an enum only when *every* constant is a
  vocabulary word, and `UPLOADED`, `VALIDATED`, `APPLYING` and `APPLIED` are not, which is why `EXPIRED` was
  already allowed. The requirement is restated to match the rule.

## Capabilities

### New Capabilities
None.

### Modified Capabilities
- `interactive-file-action`: the lifecycle gains `CANCELLED`, and the cancel scenarios state the terminal
  outcome.
- `file-action-admin`: an administrator's cancel ends the submission `CANCELLED`.

## Non-goals

- Rolling back rows applied before the stop. A stop happens between transactions; `rowsApplied` reports what was
  kept.
- Interrupting a batch in flight, or making `ALL_OR_NOTHING` stoppable mid-transaction.
- A database migration: `state` is `VARCHAR(32)` with no check constraint, and no existing row changes meaning.
- Re-labelling historical submissions that were recorded `APPLIED` after a cancel.

## Impact

- Module: `file-action-spring-boot-starter` (library; parent `common`). In-repo dependents:
  `crud-service-example` (service; parent `ludwig-service-parent`), which the gate runs.
- Shared contracts in `openspec/specs/`: `interactive-file-action` and `file-action-admin` are modified.
  `long-running-operations` is not changed; `CANCELLED` is already one of its six statuses.
- No POM changes and no new dependencies.
- README (EN and RU) lifecycle diagram, and `CHANGELOG` (EN and RU).
