# Durable run state: `state.json` and `receipt.json`

The conversation is not the system of record. A context reset, a new session or a model swap must
not lose what a change is for, what was already decided, or what was already tried and failed.

Two files per change, both inside `openspec/changes/<name>/`, both preserved by the archive:

| File | Written | Read |
|---|---|---|
| `state.json` | before implementation starts, then updated at every task boundary | **first**, on every resume |
| `receipt.json` | once, at the end | by whoever compares two runs |

## `state.json`

```json
{
  "change": "add-cache-starter",
  "contract": {
    "goal": "one named-cache abstraction replacing the two Caffeine triads",
    "output": "cache-spring-boot-starter + security/user-settings migrated",
    "constraints": ["no new in-repo dependency for security", "security TTL ceiling enforced"],
    "done_when": ["mvn -q validate",
                  "mvn -pl cache-spring-boot-starter -am verify",
                  "mvn -pl security-spring-boot-starter -am verify",
                  "openspec validate add-cache-starter"]
  },
  "current_task": "3.2",
  "decisions": ["local-only default; shared tier opt-in per cache"],
  "failures": ["shared eviction retry: no metric yet"],
  "open_questions": ["does evict(PrincipalRef) keep the key-set scan?"],
  "pending_human": []
}
```

Two properties make this work rather than decorate it. They are the whole point, and an agent that
treats the file as a scratch pad gets none of the benefit.

### The `contract` block is written before the first edit and never edited afterwards

Not "rarely edited" — **never**, except as an explicit scope change that is itself logged as a
`decisions` entry saying what changed and why. It is what stops a change from being silently
redefined into something easier that still reports success, which is the most common way agent work
passes review and fails in production.

`done_when` holds the actual commands, not a description of them. `scripts/gate.sh --list <module>`
prints exactly the list to paste in.

### `decisions` and `failures` are append-only

Never rewritten, never pruned, never reordered. A decision recorded once does not get re-argued next
session. A failure recorded once does not get rediscovered — which is what otherwise happens when a
resumed session cheerfully re-attempts the approach the previous one proved does not work.

`open_questions` is the one list that may shrink: an answered question moves into `decisions` with
its answer. `pending_human` holds anything that cannot progress without a person, and a non-empty
`pending_human` is a reason to stop and report rather than to guess.

### Resuming

On resume, with nothing but the change name:

1. Read `openspec/changes/<name>/state.json`. The `contract` says what "done" means; `current_task`
   says where to continue; `decisions` and `failures` say what not to revisit.
2. Read `openspec/changes/<name>/tasks.md` for the task at `current_task`.
3. Bootstrap the code index (`set_project_path`, `build_deep_index`) and continue.

Nothing else should be needed. If a resume cannot reconstruct what it is doing from those, the state
file is missing something — add it, rather than reconstructing from the diff.

## Bounded retries

The retry cap lives here and in `openspec/config.yaml`, deliberately **not** in the agent's
judgement:

- **Three attempts per task.** Each attempt must change something identifiable. Re-running the same
  edit is not a second attempt.
- **On the third failure, stop.** Append the gap to `failures`, set `pending_human` if a person is
  needed, and report.
- **Never make progress by lowering the bar.** Do not widen the scope, weaken a Checkstyle or
  ArchUnit rule, delete or `@Disabled` a failing test, or narrow the change to the part that passes.
  A blocked task reported honestly is a useful outcome; a green build that hid the problem is not.

The model repairs the local gap; whether another attempt is allowed is not the model's decision.

## `receipt.json`

Written once, at the end, and kept in the archive.

```json
{
  "change": "add-cache-starter",
  "model": "claude-opus-5",
  "index_version": "0665e28ffa69a979b74da19c17b7c6d4766df5bba89512de1b0d20448b313552",
  "modules_touched": ["cache-spring-boot-starter", "security-spring-boot-starter"],
  "gate": { "validate": "pass", "verify": "pass", "openspec_validate": "pass" },
  "tests": { "added": 14, "passed": 212, "failed": 0 },
  "retries": 2,
  "human_corrections": 1,
  "rollback_point": "2071728a1c4d9f0e5b3a7c2d8e6f4a1b9c0d3e5f",
  "unresolved": ["evict(PrincipalRef) still O(n)"]
}
```

| Field | Where it comes from |
|---|---|
| `model` | the model the run executed on — the reason the field exists |
| `index_version` | `index.freshness.pomSetSha` in `project-index.json` |
| `modules_touched` | module names as `project-index.json` spells them |
| `gate` | the real result of each gate command; never a prediction |
| `retries` | attempts beyond the first, summed across tasks |
| `human_corrections` | times a person had to intervene to unblock or redirect |
| `rollback_point` | `git rev-parse HEAD` **before** the first edit of the change |
| `unresolved` | anything left broken, slow or unproven. Unflattering entries are the useful ones |

The receipt is what makes a model swap measurable. Running the same class of change on Claude Opus
and on DeepSeek-V4-Flash and comparing `retries`, `human_corrections` and `unresolved` says whether a
difference in quality came from a harness change or a model change — which is otherwise unanswerable,
and is the whole reason both models stay in play.

Do not build a trace store. Git plus these two files in the change directory is the smallest thing
that closes the loop, and the archive already preserves them.
