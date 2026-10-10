## Why

A running pod cannot currently be traced back to the source it was built from. `git-commit-id-maven-plugin`
runs in `ludwig-service-parent` and its output reaches exactly one place — the
`org.opencontainers.image.revision` image label — which is readable by someone with registry access and
is not readable from a log line, an incident dashboard or a support ticket. The commit, the branch, the
build timestamp, the CI build number and whether the tree was dirty are not baked into the artifact at
all, so nothing downstream can surface them.

Two claims in the repository are already false, which is how the gap stayed invisible:

- `build/ludwig-service-parent/pom.xml:793` states the plugin *"drops a git.properties into the jar that
  Boot's actuator serves from /actuator/info."* It does not: `generateGitPropertiesFile` defaults to
  `false` and is set nowhere in the repository. No artifact contains git metadata, and `/actuator/info`
  has no git section.
- `ServiceIdentityResolver.java:65-67` falls back to a `build.version` property with the comment
  *"present whenever the build-info goal ran."* `spring-boot-maven-plugin`'s `build-info` goal is bound
  nowhere, so that branch is unreachable and version resolution depends entirely on the repackaged jar
  manifest — which the same file notes is absent in an IDE.

Both are prose that drifted from reality with no mechanical check to catch it, which is precisely what
the *encode every rule twice* convention exists to prevent.

Separately, `observability-spring-boot-starter` defaults structured JSON logging to **on** in every
environment, and instructs each service to switch it off in its `local` profile by hand. That advice is
copy-paste that a new service forgets, so a developer's console is JSON until someone notices.

## What Changes

**Build metadata is baked into every service artifact.**

- `ludwig-service-parent` generates `git.properties` (commit id, abbreviated commit id, branch, commit
  time, dirty flag) and `build-info.properties` (group, artifact, name, version, build time) into the
  artifact. Both already have plugins configured in that POM; neither is currently producing a file.
- The CI build number is contributed as a build-info property from an explicitly passed value, so it is
  absent rather than wrong on a local build.
- Metadata is read from those resources at runtime. Nothing invokes `git` in a running process, so an
  artifact promoted from staging to production reports the build it was built from rather than the
  environment it landed in.

**A new `BuildIdentity` record carries build provenance, separately from `ServiceIdentity`.**

- `ServiceIdentity` is deliberately left alone. It is a public record whose five components become
  Micrometer common tags and OpenTelemetry resource attributes; adding components would be an
  API-breaking change under revapi, and `branch` or `dirty` as a metric tag is a cardinality defect
  rather than a feature.
- `BuildIdentity` holds commit id, abbreviated commit id, branch, build timestamp, CI build number and
  the dirty flag, each optional, resolved once at startup.

**Build identity reaches the log stream.**

- A startup log event records the full application and build identity as one structured event, so the
  first line of a pod's logs answers "what is this and what was it built from".
- The abbreviated commit id becomes a field on **every** log event, alongside the service name and
  version that are already there. Commit and version move together, so this adds no field that varies
  independently of one already present.
- `LogFieldNames` gains the field in all three field sets (`ecs`, `otel`, `flat`).

**The console format default becomes profile-dependent.**

- JSON stays the default everywhere except when the `local` profile is active, where the default becomes
  Logback's human-readable pattern. It remains a *default*, published in the existing lowest-precedence
  property source, so any explicit setting still wins.
- The human-readable pattern gains the service identity, the correlation id and the commit id, which it
  carries none of today. Switching format currently loses every piece of correlation metadata; after
  this change it loses only the machine-readability.

**The rules this change introduces each get a check.**

- A new `RuleGroup.LOGGING` in `architecture-rules` fails the build when a module other than
  `observability-spring-boot-starter` implements a Logback `Encoder` or `Layout`, or declares a second
  type holding build provenance — the same shape of rule as `RuleGroup.AUDIT` and `RuleGroup.CACHING`,
  for the same reason. The same group forbids invoking a git process or reading a `.git` path from
  production sources, which is the "build time, not runtime" rule made mechanical.
- A new `scripts/check_build_metadata.sh` + `.py`, registered in `gate.sh`, asserts that
  `ludwig-service-parent` actually configures both metadata plugins to emit their files. Plugin
  configuration is a POM-text fact that neither ArchUnit nor Checkstyle can see — the same reason
  `check_migrations` and `check_image_pins` are scripts.
- No check forbids a service-authored `logback-spring.xml`, and deliberately so.
  `JsonLoggingInitializer` already documents that it replaces only Logback's own layout-wrapping
  encoders and leaves a hand-configured appender untouched, because that appender represents a decision
  someone made on purpose. The rule worth enforcing is "no second encoder *implementation*", which the
  ArchUnit rule covers; forbidding the configuration file would make an existing, reasoned escape hatch
  unreachable.
- A service-level integration test asserts the two metadata resources are on the classpath and carry a
  commit id. That is the check that would have caught both drifted comments, and it is the one that
  fails if a future POM edit silently stops generating them.

## Capabilities

### New Capabilities

- `build-identity`: what build provenance is baked into a service artifact, where each field comes
  from, that it is obtained at build time and never by querying git at runtime, and that it survives
  promotion of one artifact between environments.
- `service-log-stream`: the console log stream — that there is exactly one log pipeline owned by one
  module, how the human-readable and JSON formats are selected, and the identity and correlation fields
  every log event carries in either format.

### Modified Capabilities

None. `enforcement-triad` governs *which* tool a new check belongs to, and this change follows that
split (ArchUnit for the type-level rule, a gate script for the filesystem facts) rather than altering
it. `published-api-document` is likewise unmodified: the change is additive by construction, which is
why `ServiceIdentity` is not widened.

## Non-goals

- **Local file logging.** The original intent asked for a rotating, compressed, retention-bounded file
  appender enabled by default. It is dropped. These services ship as container images; a default-on file
  appender writes into the ephemeral layer, competes with the node agent for the same bytes, vanishes on
  restart, and can fill a node if the budget is misconfigured. `observability-spring-boot-starter`'s
  existing stdout-only stance stands.
- **The CI release gate.** The intent asked CI to fail a release artifact missing mandatory metadata.
  Out of scope at the author's direction — the `-Prelease` profile and the pipeline are not touched by
  this change. This proposal makes the metadata *exist*; deciding which fields are mandatory for a
  release, and enforcing it, is handled separately.
- **A release identifier distinct from the version.** The intent listed one. It is deliberately omitted:
  GitVersion computes the version from the tag, so the tag already *is* the release identifier, and a
  second field holding the same string is one more thing that can disagree with itself. The
  `build-identity` spec records the omission and its reason, so it is not re-added as an oversight.
- **A new module.** A `logging-spring-boot-starter` would need `ServiceIdentity`, `CorrelationContext`
  and the JSON encoder from `observability-spring-boot-starter`, which would need the build identity
  back — a reactor cycle that `ModuleIndependenceTest` fails. It would also be a second log-shaping
  mechanism on a platform whose discipline is one audit sink, one operation envelope, one cache
  primitive and one `ProblemDetail` pipeline. This change is a delta to the existing module.
- **Tracing, metrics and correlation behaviour.** `service-log-stream` is scoped to the console log
  stream. The sampler, the OTLP exporter, the RED metrics, the cardinality cap and the correlation id's
  propagation across HTTP and Kafka are unchanged and out of this capability's scope.
- **Exporting logs over OTLP**, and **instrumenting WebClient** — both already documented non-goals of
  the module, unchanged.

## Impact

### Modules touched

| Module | Directory | Role / POM tier |
|---|---|---|
| `observability-spring-boot-starter` | `sources/observability-spring-boot-starter/` | starter (library, parented by the reactor root `common`, imports `ludwig-bom`) |
| `ludwig-service-parent` | `build/ludwig-service-parent/` | parent (packaging `pom`, parented by `spring-boot-starter-parent`, imports `ludwig-bom`) |
| `architecture-rules` | `build/architecture-rules/` | rules (library, parented by `common`, imports `ludwig-bom`) |
| `crud-service-example` | `services/crud-service-example/` | service (parented by `ludwig-service-parent`) — host for the metadata integration test |

### In-repo dependents the gate must run

`observability-spring-boot-starter` has 6 in-repo dependents, all of which the gate runs:
`export-spring-boot-starter`, `file-action-spring-boot-starter`, `notification-service`,
`reconciliation-spring-boot-starter`, `rest-client-spring-boot-starter`, `user-settings-spring-boot-starter`.

`architecture-rules` has 9 test-scoped dependents: `crud-service-example`,
`file-action-spring-boot-starter`, `file-ingest-spring-boot-starter`, `messaging-spring-boot-starter`,
`notification-service`, `object-storage-spring-boot-starter`, `odata-filter-spring-boot-starter`,
`pat-spring-boot-starter`, `user-settings-spring-boot-starter`.

`ludwig-service-parent` reports no in-repo dependents, because a parent relationship is not a
dependency edge in `project-index.json`. That is misleading and the gate knows it: **because this change
edits `ludwig-service-parent`, the gate is `mvn clean install` over the whole reactor**, not a narrow
`-pl` build. `gate.sh` substitutes the full build when a bom or parent module is named.

### Other impact

- **New in-repo dependency: none.** `observability-spring-boot-starter` already depends on `audit-core`,
  `hot-reload-spring-boot-starter` and `web-core-spring-boot-starter`. Build metadata is read from
  classpath resources, so no new edge is added and the reactor DAG is unchanged.
- **Log volume and schema.** One additional short field per log event, and one new structural field name
  in each of the three field sets. A log aggregator with a strict index template needs the new field
  mapped before it ingests it.
- **Artifact contents.** Every service jar gains `git.properties` and `build-info.properties`, and
  `/actuator/info` gains `git` and `build` sections it did not have. `info` is already in the default
  `management.endpoints.web.exposure.include`, so this becomes reachable wherever the management port is
  — branch name and commit id are disclosed to anything that can reach it, which is a deliberate and
  documented consequence rather than an accident.
- **Build reproducibility.** jib's `creationTime` and `filesModificationTime` stay at the epoch so that
  building one commit twice yields an identical digest. A build timestamp baked into
  `build-info.properties` breaks that for the jar layer. The design must decide whether the timestamp is
  reduced in precision or excluded from the comparison; it is a real trade-off against an existing,
  documented property.
- **Developer-visible behaviour change.** A service running under the `local` profile now gets a text
  console where it previously got JSON. Services already carrying the manual
  `ludwig.observability.logging.json.enabled: false` override are unaffected — their explicit setting
  still wins over the default.
