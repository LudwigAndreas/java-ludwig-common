# Design

## Context

See `proposal.md` - Why. The constraints that shape the approach:

- An OpenAPI document can only be produced by a fully started Spring context. Both services need a
  PostgreSQL to start, because Liquibase runs and Hibernate validates its mappings at startup. Both
  already have that in tests through `test-support`'s `PostgresContainerConfiguration`, and
  `notification-service` already fetches `/v3/api-docs` through `MockMvc` in `InboxOpenApiIT`.
- `springdoc.version` (2.6.0) is already managed in `build/ludwig-bom/pom.xml`.
- `odata-filter-spring-boot-starter` declares springdoc `<optional>true</optional>`, deliberately so
  that a starter does not push Swagger UI onto a consumer. Optional dependencies are not transitive,
  which is exactly why `crud-service-example` has no springdoc today despite depending on the module
  that contributes OpenAPI descriptions.

## Goals / Non-Goals

Beyond the proposal's scope, at design level:

- **Goal**: the generated document is byte-stable across machines and runs. A comparison that fails
  spuriously gets disabled, which is worse than no comparison.
- **Non-goal**: a shared module for this. Two services get the same ~60-line test. A
  `test-support-openapi` module to hold one method would add a reactor edge and a POM for less code
  than it costs; if a third service wants it, that is the point to extract it.
- **Non-goal**: fetching the document over a real port (`@SpringBootTest(webEnvironment = RANDOM_PORT)`).
  `MockMvc` is enough, matches `InboxOpenApiIT`, and avoids a server URL in the output.

## Decisions

### YAML, not JSON

springdoc serves both `/v3/api-docs` and `/v3/api-docs.yaml`.

The committed artifact is YAML. Its entire value is that a human reads it in a diff, and OpenAPI JSON
is deeply nested, heavily quoted and - for a document of this size - produces diffs where a one-field
change touches many lines of punctuation. Client generators read YAML equally well.

*Alternative considered*: commit both. Rejected - two artifacts describing one thing is two things to
keep in step, and the JSON is one `curl` away from any running service.

### Canonical serialization: every map sorted by key, `servers` stripped

The generated document is re-serialized before comparison, with all object keys sorted and the
`servers` block removed.

Sorting is what makes the comparison trustworthy. springdoc builds the document by walking the
handler mappings, and nothing guarantees that walk is in the same order on another JVM, after a
refactor that renames a class, or once a `@RestControllerAdvice` is registered in a different order.
An unsorted document would produce diffs that are pure reordering, and the first time that happens
somebody passes `-Dludwig.apidocs.write` without reading the diff - at which point the check is
decorative. In OpenAPI, object member order carries no meaning (`paths`, `schemas`, `properties`,
`responses` are all maps); array order does (`required`, `enum`, `parameters`), and arrays are left
alone.

`servers` is derived from the request the document was fetched with, so it describes the test
harness, not the deployment. Removing it is more honest than committing `http://localhost`.

*Alternative considered*: compare parsed trees rather than bytes, which tolerates reordering without
sorting. Rejected - the committed file is then not canonical, so two authors regenerate it into two
different byte sequences that both pass, and the file churns in every unrelated commit.

### The check is an integration test, not a gate script

Stated as a non-goal in the proposal; the design-level reason is that the artifact's producer and its
verifier must be the same thing. A `scripts/check_api_docs.sh` cannot start a Spring context with a
database, so it could only assert the file exists - a check named after a property it does not test,
which is worse than an absent check because it reads as covered.

Consequence: the check runs when that service is built (`mvn -pl :<artifactId> -am verify`), which is
what the gate already runs for a change touching the service. A change that alters a service's HTTP
surface without building that service is already outside the gate.

### Refresh is an explicit opt-in, not a side effect

The test writes the generated document to `target/` on every run and compares. It overwrites
`docs/api/` only when `-Dludwig.apidocs.write=true` is passed.

A test that rewrites a tracked file as a side effect makes `mvn verify` dirty the working tree, so
`git status` stops being a reliable signal and a stale document can be committed without anyone
seeing it. Making the refresh explicit means the diff is always something the author asked for.

### `-webmvc-ui` for `crud-service-example`, matching `notification-service`

`crud-service-example` is the shape a new service is copied from, and a new service wants the UI. The
`-common` artifact is the right choice for a *starter* contributing a customizer - which is what
`odata-filter`'s POM comment says and why it chose it - and the wrong choice for a service.

## Dependency-direction check

**Does `crud-service-example` already depend on `odata-filter-spring-boot-starter`?** Yes, at
`compile` scope, per `scripts/manifest.sh module services/crud-service-example`. This change adds no
in-repo dependency to either service; the only new edge is to a third-party artifact.

**Could the new dependency create a cycle?** No. `project-index.json` `inRepoDependents` for
`crud-service-example` is `[]` and for `notification-service` is `[]` - both are leaf services that
nothing in the repository consumes. Adding a third-party dependency to a leaf cannot create an
in-repo cycle, and no in-repo edge is added in either direction.

## POM changes

| POM | Changes? | Why |
|---|---|---|
| `pom.xml` (root) | No | No library module is touched, and no plugin version changes. |
| `build/ludwig-bom/pom.xml` | No | `springdoc.version` 2.6.0 is already managed there. A version written into the service POM would be the defect the BOM exists to prevent. |
| `build/ludwig-service-parent/pom.xml` | No | Nothing here is a build decision every service inherits. A service with no HTTP surface should not acquire springdoc. |
| `services/crud-service-example/pom.xml` | **Yes** | One `<dependency>` on `springdoc-openapi-starter-webmvc-ui`, no `<version>`. |
| `services/notification-service/pom.xml` | No | It already has springdoc. |

Because a POM changes, `scripts/manifest.sh build` runs before the gate.

## Enforcement of the new conventions

| Convention | Mechanical check | Owner |
|---|---|---|
| The committed document matches what the service serves | The per-service integration test, failing at `verify` | the service's own test suite |
| The document declares the `ProblemDetail` schema | An assertion in that same test, on the generated document | the service's own test suite |
| The reference service's query options are described | An assertion on `$filter`/`$orderby`/`$top`/`$skip`/`$count` in the generated document | the service's own test suite |
| A *new* service publishes a document at all | **No check.** Stated here deliberately | - |

The last row is the honest gap. Nothing fails when somebody adds a third service with controllers and
no published document, because the check lives in each service's own tests and an absent test cannot
fail. The three enforcement tools cannot close it either: `architecture-rules` reads bytecode and
cannot see whether a file exists under `docs/api/`; `checkstyle-rules` reads source text in the module
being built; an enforcer rule runs per module and would have to know which modules serve HTTP.
`scripts/manifest.sh layout` is the only mechanism that reasons about the repository as a filesystem,
and extending it to require a `docs/api/` entry per service whose module declares a web starter is the
natural home for this - but that is a `repository-layout` capability change, and this change does not
touch a shared contract. Recorded as an open question rather than silently omitted.

Nothing here reimplements a centralised mechanism. The `ProblemDetail` schema is *described*, not
produced - `web-core`'s single advice still produces every error, and the schema declaration is a
description of that one pipeline's output. No audit sink, operation envelope or cache primitive is
involved.

## Risks / Trade-offs

- **The document is not byte-stable despite sorting** (springdoc emits a generated schema name like
  `MapStringObject` that varies, or a `$ref` whose name depends on a scan order sorting cannot
  normalize) → the comparison fails spuriously on an unrelated change. Mitigated by sorting and by
  stripping `servers`; if a residual instability appears, the fix is to normalize that specific field
  in the canonicalizer with a comment naming what was unstable, never to relax the comparison to a
  subset of the document.
- **`crud-service-example` gains Swagger UI, and with it `/swagger-ui.html` and `/v3/api-docs` on a
  deployed service.** Both are now reachable; whether they are exposed is the deployment's
  authorization concern, exactly as it already is for `notification-service`. No new precedent, but it
  is a new surface on that service and belongs in its README.
- **Two near-identical tests** → duplication that will be noticed and "fixed" by extracting a module
  before a third caller exists. Mitigated by the non-goal above being written down.
- **Adding springdoc to `crud-service-example` activates the OData customizer for the first time in
  that service**, so the generated document may reveal that the customizer's output is wrong or
  incomplete. That would be a genuine finding about `odata-filter`, not a problem with this change -
  but it could turn a documentation change into a starter fix. Accepted: if it happens, it is recorded
  as a finding and scoped separately rather than patched inside this change.

## Migration Plan

Nothing to migrate. No schema, no configuration a deployment must set, no endpoint changed. The
rollback is reverting the commit; the committed documents are descriptive and nothing reads them at
runtime.

## Open Questions

- Should `scripts/manifest.sh layout` require a `docs/api/<artifactId>.openapi.yaml` for every module
  whose POM declares `spring-boot-starter-web`? That would close the gap in the enforcement table
  above. Deferred because it changes the `repository-layout` capability, which this change
  deliberately does not touch, and it can be added later without altering anything specified here.
