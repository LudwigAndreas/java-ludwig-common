## Design

Not required, and this section records why rather than being deleted, so that a reader does not
wonder whether it was skipped by accident.

`openspec/config.yaml` requires a design for a change that adds a module, crosses a module
boundary, or changes a shared contract in `openspec/specs/`. This change does none of the three: it
edits two Markdown files inside one module.

**Dependency direction:** no in-repo dependency is added or removed, so there is no direction to
check. `job-core`'s `inRepoDependencies` stays `[test-support (test)]` and its `inRepoDependents`
stays the same seven modules.

**Which of the three POMs changes:** none. No POM is touched, so no plugin configuration, no
third-party version and no parentage changes.

**New conventions introduced, and their checks:** none. The one convention this change might have
suggested — that every module README links its capability spec — is explicitly a non-goal, because
twenty-eight of twenty-nine modules have no spec and a check would be disabled on its first run.
