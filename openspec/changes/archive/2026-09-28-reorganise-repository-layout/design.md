## Design

### Dependency direction

**Question:** does this change add or alter any in-repo dependency edge?

**Answer: no.** Not one `<dependency>` element changes. The reactor DAG after the move is
edge-for-edge identical to the DAG before it, because Maven builds that graph from
`groupId:artifactId` coordinates and not from directory positions. `project-index.json`'s
`inRepoDependencies` and `inRepoDependents` must therefore be byte-identical after regeneration
apart from the `pom` path field — and that is the check this change uses to prove it did not
disturb the graph.

`cache-spring-boot-starter` keeps its zero in-repo dependencies, so
`ModuleIndependenceTest` is unaffected and `security-spring-boot-starter` can still depend on it.

### Which of the three POMs changes, and why

**All three, plus all 29.**

- **Root `pom.xml`** — every `<module>` entry gains a directory prefix, and the Checkstyle
  `configLocation` / `suppressionsLocation` move from
  `${maven.multiModuleProjectDirectory}/checkstyle-rules/...` to `.../build/checkstyle-rules/...`.
  These are the only two filesystem paths in the build.
- **`ludwig-bom`** — its explicit `<relativePath>../pom.xml</relativePath>` becomes `../../pom.xml`.
- **`ludwig-service-parent`** — its own parent is the external `spring-boot-starter-parent` with an
  empty `<relativePath/>`, so it is unaffected. Its `${checkstyle.config.location}` is a
  **classpath** location, not a filesystem one, and must not be touched.
- **Every module parented by `common`** — gains an explicit
  `<relativePath>../../pom.xml</relativePath>`. See the Impact section of the proposal: this is the
  one failure that would not announce itself.
- **The two services** — `<relativePath>../../build/ludwig-service-parent/pom.xml</relativePath>` becomes
  `../../build/ludwig-service-parent/pom.xml`.

### Why `-pl :artifactId` replaces `-pl <module>`

`mvn -pl` accepts either a module *path* or `:artifactId`. Paths are what the repository used, and
they are exactly what this change invalidates — `mvn -pl job-core -am verify` stops working and
becomes `mvn -pl sources/job-core -am verify`.

`scripts/gate.sh` and the Jenkinsfile switch to the `:artifactId` form instead of learning the new
paths. It is shorter, it is stable across any future move, and it means the gate commands printed
by `scripts/manifest.sh module <path>` stay correct if this layout is ever revised again. This is
the one place the change makes something *more* robust rather than merely relocating it.

### New conventions, and the checks for them

The placement rule is new, so per this repository's standing principle it needs a check and not
only prose:

- **Check:** `scripts/manifest.sh build` derives each module's role from its POM and its directory
  from the filesystem. A new `--check-layout` mode fails when a module's directory does not match
  its role — a service outside `services/`, a library or starter outside `sources/`, the BOM or a
  rules jar outside `build/`. The gate runs it.
- **Guide:** the `repository-layout` capability spec, and a short section in `CLAUDE.md`.

This belongs in `manifest.sh` rather than in ArchUnit or Checkstyle: it is a fact about the
*filesystem*, which neither of those tools can see. ArchUnit reads bytecode and Checkstyle reads
source text; a directory's position is neither. That is the triad's boundary being respected, not
dodged.

### Risk, and what it would look like if it went wrong

The dangerous failure is silent: a module whose parent no longer resolves in-reactor and is served
instead from `~/.m2`. It does not error — it builds against a stale `common`, so plugin
configuration, the Checkstyle binding and the annotation-processor ordering all quietly revert to
whatever was last installed.

`mvn clean install` from a reactor root whose `common` is **not** installed would catch it, but the
cheap and reliable detection is `mvn help:effective-pom` on a moved module, checking that the
Checkstyle plugin is bound — and, more simply, that the full reactor builds after
`rm -rf ~/.m2/repository/ru/ludwigandreas`. The tasks use the latter.
