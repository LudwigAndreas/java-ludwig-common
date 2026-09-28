## Why

`openspec/specs/` now carries a `run-lock` capability spec describing the leased run lock's
contract. Nothing in `job-core`'s README points at it, so the next person to change the lock's
behaviour will edit the README and the code and never learn that a spec also describes it — which
is how a spec becomes decorative within one release.

This is also the throwaway change used to exercise the agent harness end to end. It is deliberately
the smallest real change available: one sentence, in both locales, in one module.

## What Changes

One sentence added to the end of the "What it is not for" subsection of `job-core/README.md`, and
its translation added at the same place in `job-core/README.ru.md`, pointing at
`openspec/specs/run-lock/spec.md` and stating that a change to the lock's contract updates the spec
first.

No code changes. No POM changes. No behaviour changes.

## Non-goals

- Adding the same pointer to the other twenty-eight modules. If this reads well it should be done
  as its own change, in one pass, not smuggled into a harness test.
- Changing anything the `run-lock` spec says. The spec was written from this README; making them
  disagree in the same change that links them would be perverse.
- Adding a mechanical check that every module README links its spec. Most modules have no spec yet,
  so the check would fail on twenty-eight modules and be switched off immediately.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

None. No requirement changes: the `run-lock` spec already describes the behaviour, and this change
only makes the README point at it. `.openspec.yaml` therefore sets `skip_specs: true`.

## Shared contracts touched

`openspec/specs/run-lock` is **referenced** but not modified. No other spec in `openspec/specs/` is
affected.

## Impact

| Module | POM tier | In-repo dependents |
|---|---|---|
| `job-core` | library — parented by the reactor root `common`, imports `ludwig-bom` | `audit-spring-boot-starter`, `export-spring-boot-starter`, `file-ingest-spring-boot-starter`, `idempotency-spring-boot-starter`, `notification-service`, `outbox-spring-boot-starter`, `reconciliation-spring-boot-starter` |

The seven dependents are listed because the verification gate runs each of them. Nothing they
compile against changes — the edit is to a Markdown file — so they are expected to pass unchanged,
and running them is the point: the gate is not conditional on the agent's opinion of the blast
radius.
