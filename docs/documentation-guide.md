# Writing documentation for `ludwig-common`

**English** · **[Русский](documentation-guide.ru.md)**

Who this is for: anyone who adds a module, changes one, or writes a runbook. It says what a module
README contains, in which order, and how it is kept true.

## 1. Where documentation lives

| Document | Location | Audience | Kept true by |
|---|---|---|---|
| Module README | `<module>/README.md` + `<module>/README.ru.md` | developers, analysts, administrators | the change that touches the module (OpenSpec task) |
| Platform overview and module map | `README.md` (root) | everyone | the change that adds or removes a module |
| Developer guide | [`docs/developer-guide.md`](developer-guide.md) | developers | review |
| Development and release process | [`docs/runbook.md`](runbook.md) | developers, release managers | review |
| Administrator guide | [`docs/admin-guide.md`](admin-guide.md) | platform administrators, SRE | review |
| Analyst guide | [`docs/analyst-guide.md`](analyst-guide.md) | system analysts | review |
| Data scopes and role mappings | [`docs/data-scope-configuration.md`](data-scope-configuration.md) | developers, administrators | review |
| HTTP and event contracts | `docs/api/*.openapi.yaml`, `docs/api/*.asyncapi.yaml` | analysts, consumers | generated; `ApiDocumentIT` fails the build on drift |
| Behavioural requirements | `openspec/specs/<capability>/spec.md` | analysts, reviewers | OpenSpec archive step |
| Release notes | `CHANGELOG.md` + `CHANGELOG.ru.md` | consumers | `-Prelease` fails without an entry in both |
| What is enforced and what is not | [`docs/harness-enforcement.md`](harness-enforcement.md) | developers, reviewers | review |
| Agent operating rules | `docs/agent-operations.md`, `docs/agent-state.md`, `docs/code-index.md` | coding agents | review |
| Module manifest | `PROJECT_INDEX.md`, `project-index.json` | everyone | generated: `scripts/manifest.sh build` |

Javadoc carries the contract of each public type and method. A README does not repeat Javadoc; it
says how the pieces fit together, how to operate them, and why they are shaped the way they are.

## 2. Rules every document follows

1. **Describe the current state only.** No "previously", "no longer", "was replaced by", "we first
   tried", "this fixes the bug where". History belongs in `CHANGELOG.md`, in the OpenSpec archive
   (`openspec/changes/archive/`) and in git. A reader needs to know what the module does and why;
   how it got there does not help them operate it.
2. **State the reason, not the story.** "Shutdown waits for the in-flight poll, so a rolling deploy
   does not strand claimed rows for `stale-timeout`" is a reason. "Before 1.1 rows were stranded" is
   a story.
3. **Both locales, same change.** `README.md` and `README.ru.md` have the same sections in the same
   order and say the same thing. A README updated in one locale only is an incomplete change
   (`openspec/config.yaml`, rule for module tasks). Each file starts with the language switch line.
4. **Image references carry a digest.** `name:tag@sha256:...`, also in examples.
   `scripts/check_image_pins.sh` scans the code blocks and inline code of every README; prose is not checked.
5. **Property tables state the default.** Every `ludwig.*` property the module binds appears in the
   Configuration table with its default and meaning. If a property is not in the table it is not
   supported.
6. **Say "not applicable" rather than omitting a section.** A missing "Audit" section reads as "nobody
   thought about it"; `Not applicable: this library performs no state change` reads as a decision.
7. **Known gaps are written down.** If the module should emit a metric, a health indicator or an
   audit event and does not, the Observability or Audit section says so under **Known gaps**. A gap
   in a README is a backlog item; a gap nobody wrote down is a surprise in production.

## 3. The module README template

Sections appear in this order. Headings are exactly these, so a reader can jump between modules.

```markdown
# <artifactId>

***English** · [Русский](README.ru.md)*

<One paragraph: what the module does and when a service uses it. No history.>

| | |
|---|---|
| Role | library / starter / service / contract / build tooling / test support |
| Depends on (in-repo) | links, or "nothing" |
| Used by | links (from PROJECT_INDEX.md) |
| Surfaces | REST / Kafka / gRPC / actuator / none |

## Usage
Dependency snippet, the minimal working setup, then the main API. Code examples compile.

## Configuration
| Property | Default | Meaning |
One table for every `ludwig.<module>.*` property. Group with sub-headings if long.

## Deployment
What has to exist before the module works: database schema (Liquibase changelog path and how it is
applied), topics, buckets, secrets, network access, the image (for services). For a library with
nothing to deploy: "Nothing to deploy: <reason>".

## Observability
- **Metrics** - table: name, type, tags, meaning.
- **Logs** - logger names, what is logged at which level, what is never logged.
- **Traces** - spans/observations created, propagation.
- **Health** - health indicators and which probe group they belong to.
- **Recommended alerts** - expression or condition, and why it matters.
- **Known gaps** - what should be observable and is not.

## Audit
Which `AuditEvent` categories/actions the module records, with what outcome and when, and what is
redacted. Or "Not applicable: <reason>".

## Runbook
| Symptom | Likely cause | What to do |
Concrete symptoms an operator sees (a metric, a log line, an HTTP status), not abstract failure
modes. Each "What to do" is an action, including the SQL or command when there is one.

## Testing
How the module is tested, what needs Docker, and what a consuming service should test itself.

## Design decisions
Short list of the rules that look surprising and why they hold. Final decisions only.
```

`README.ru.md` uses the same order with these headings: `Использование`, `Конфигурация`,
`Развёртывание`, `Наблюдаемость`, `Аудит`, `Runbook`, `Тестирование`, `Проектные решения`; the table
rows are `Роль`, `Зависит от (в репозитории)`, `Используется`, `Поверхности`. The language line is
`*[English](README.md) · **Русский***`.

Optional sections go between **Usage** and **Configuration** when a module needs them (for example
"Routing", "Schema", "Extension points"). Keep the fixed sections in the fixed order.

### What does not go in a README

- Change history, migration notes for a past release (those go in `CHANGELOG.md` under the release
  that introduced the change).
- Content generated elsewhere: the module list of `PROJECT_INDEX.md`, the API documents in
  `docs/api/`.
- A copy of a requirement from `openspec/specs/`. Link to it.

## 4. Writing a runbook entry

A runbook entry is for an operator at 03:00 who has an alert and no context.

| Do | Do not |
|---|---|
| Start from the symptom the operator sees: the alert, the metric, the log message, the HTTP status | Start from the internal component that failed |
| Give the query or command to confirm the cause | Say "check the database" |
| Say whether the action is safe to repeat | Leave the operator guessing whether re-running duplicates data |
| Say when to escalate and to whom (the owning team of the module) | End with "investigate" |

Example:

| Symptom | Likely cause | What to do |
|---|---|---|
| `ludwig_outbox_deadlettered_total` increases | a destination rejects the payload, or was down longer than the retry budget | `SELECT destination, last_error, count(*) FROM outbox_message WHERE status = 'DEAD_LETTER' GROUP BY 1, 2;` Fix the destination, then requeue: `UPDATE outbox_message SET status = 'PENDING', attempts = 0, next_attempt_at = now(), locked_at = NULL, locked_by = NULL WHERE status = 'DEAD_LETTER' AND destination = '<dest>';` The message is sent again with the same `ludwig-idempotency-key` header, so a consumer with dedup enabled drops a copy it already processed |

Platform-wide procedures (deploys, rollbacks, secrets rotation, database migrations) belong in
[`docs/admin-guide.md`](admin-guide.md); module READMEs link there instead of repeating them.

## 5. Checklist for a change that touches a module

- [ ] `README.md` and `README.ru.md` updated, same sections.
- [ ] Every new `ludwig.*` property in the Configuration table with its default.
- [ ] Every new metric in the Observability table; new failure modes in the Runbook.
- [ ] New audit actions in the Audit section.
- [ ] `CHANGELOG.md` and `CHANGELOG.ru.md` under `## [Unreleased]`.
- [ ] If the HTTP or event surface changed: `docs/api/` regenerated (`ApiDocumentIT`, see
      [`docs/api/README.md`](api/README.md)).
- [ ] If a module was added or removed: root `README.md` module map and `scripts/manifest.sh build`.
- [ ] `scripts/check_image_pins.sh` passes.
