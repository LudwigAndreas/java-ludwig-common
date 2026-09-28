## Design

### Dependency direction

**Question:** the aggregator depends on all 27 jar modules. Does anything those modules depend on,
directly or transitively, depend on the aggregator?

**Answer: no, and it cannot.** `jacoco-aggregate` is a new leaf. `project-index.json` shows its
`inRepoDependents` as empty and every other module's list unchanged apart from the aggregator
itself, which the manifest deliberately filters out. A cycle would require some existing module to
declare a dependency on a module that did not exist until this change.

The edge direction is the reverse of the usual concern: this module is a *consumer* of everything,
which is why it must be built last. Maven orders it last automatically from the dependency graph;
it is also listed last in `<modules>` so a reader sees the intent.

### Which of the three POMs changes, and why

- **Root `pom.xml`** — JaCoCo moves from `<pluginManagement>` to the active `<build><plugins>`, and
  one `<module>` entry is added. The managed `<version>` and the three executions
  (`prepare-agent`, `report`, `check` with empty rules) are unchanged; only the activation moves.
- **`ludwig-bom`** — unchanged. The aggregator publishes nothing and is not a dependency of
  anything, so it does not belong in the version registry.
- **`ludwig-service-parent`** — unchanged. Services keep their own merged-exec report and their
  enforced thresholds.

### Why activation moves to the root rather than adding three more declarations

The failure this change repairs is that opting in was per-module. Fixing the five stragglers by
adding five more `<plugin>` blocks would leave the same trap armed for module thirty. Activating
once at the root makes the question un-askable: a module parented by `common` is instrumented
because it is a module, not because somebody remembered.

The 20 existing declarations are verified bare — `<groupId>` and `<artifactId>` and nothing else —
before deletion, so nothing configured is lost. `ludwig-service-parent`'s declaration **is**
configured and is not touched.

### Exec files, and why the default is right

`report-aggregate`'s `dataFileIncludes` defaults to every `*.exec` in a module's `target/`. Library
modules produce one (`jacoco.exec`). Services produce three: `jacoco.exec`, `jacoco-it.exec` and
the `jacoco-merged.exec` that `ludwig-service-parent` merges from the first two.

Loading all three is **correct, not double-counting**. JaCoCo merges execution data by class id and
ORs the probe arrays, so a probe hit recorded in both `jacoco.exec` and `jacoco-merged.exec` is one
covered probe, not two. The default is therefore left alone: an explicit include list would be one
more thing to keep in step with `ludwig-service-parent`'s merge configuration, for no gain.

### New conventions, and the checks for them

The aggregator must live in `build/` and must not pollute every module's gate. Both are mechanised
rather than left to prose:

- `classify()` in `scripts/project_index.py` gains an `aggregate` role, and `LAYOUT` maps it to
  `build/`. `scripts/manifest.sh layout` — which the gate runs — fails if it is moved.
- The manifest filters the aggregator out of every module's `inRepoDependents`, with the reason in
  a comment at the point of the filter, because it is a rule a reader would otherwise mistake for a
  bug.

### What could make the gate pass while the result is wrong

A green build proves the report was generated, not that it covered anything. The tasks therefore
assert on the report's **content**: that the aggregate XML names all 27 modules' packages and that
the five previously-uninstrumented modules now appear with non-zero instruction counts. Without
that, deleting every dependency from the aggregator would still build green and report 0%.
