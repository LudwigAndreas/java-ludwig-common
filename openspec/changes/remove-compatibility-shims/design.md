## Context

Each item to remove carries its own condition, written beside it in the code. This change checks
each condition against the release history (`git tag`: `v1.1.0` 2026-09-28, `v2.0.0` 2026-10-10)
before removing the item.

## Goals / Non-Goals

**Goals:** the five removals the proposal lists, each with its `revapi.differences` entry, and each
backed by a test that fails if the removed thing comes back.

**Non-Goals:** the proposal's non-goals. None of the four survivors it names is a compatibility shim.

## Decisions

### D1 - The headers: both halves go, not only the write

`MessageHeaders` sets two separate conditions. The write may go once retention has passed with the
dual-writing producer deployed. The read may go once no consumer can replay from before that point.
The dual write shipped in v1.1.0, so every record any 1.1.0+ producer ever wrote carries the
canonical name. Keeping the read only makes a difference for records written by a pre-1.1.0
producer. Nothing in this repository configures a compacted topic, where such a record could survive
indefinitely. A deployment that has one is covered by the changelog's upgrade precondition.

Considered and rejected:

- Keeping the read as a private fallback. That keeps the shim, minus its name.
- Failing loudly on a legacy-only record. That needs the legacy names in the code to recognise them,
  which is the shim again.

`VersionGatingDeserializer` reads the version header itself, upstream of `EnvelopeReader`, so it is
narrowed in the same change. Otherwise the two would disagree about one record's version.

### D2 - odata-filter: web-core becomes required, the advice goes

**Dependency-direction check.** Does `web-core-spring-boot-starter` already depend on
`odata-filter-spring-boot-starter`, directly or transitively? No. The dependents of
`odata-filter-spring-boot-starter` are `crud-service-example`, `export-spring-boot-starter`,
`notification-service` and `user-settings-spring-boot-starter`, and `web-core-spring-boot-starter` is
not among them. The edge odata-filter → web-core **already exists** as `<optional>true</optional>`.
This change removes `<optional>`, so it adds no edge and no cycle.

This does not drag Spring MVC into a non-web consumer, which is the property the autoconfiguration's
javadoc promises. `web-core-spring-boot-starter` declares only `spring-web` as non-optional, and
`spring-webmvc` stays optional there. The `@ConditionalOnClass(ExceptionProblemMapper.class)` and
`@ConditionalOnClass(ProblemMessageBundle.class)` guards on the mapper and the bundle become dead,
and are removed.

`ODataFilterException` keeps extending `RuntimeException`, not `LocalizedException`. That choice is
about a batch job throwing it, which is unchanged, and changing it is not a compatibility removal.

**Check:** a module-local ArchUnit test, "this module ships no `@RestControllerAdvice`", in the shape
`file-action`, `file-ingest` and `idempotency` already use. That puts the check in
`architecture-rules`' domain, structure. The odata `ArchitectureTest` disabled
`web.controllers-do-not-call-controllers` and `rest-paths` with the reason "the only controller is the
legacy advice". The reason is now false, so both are re-enabled. If either fires on
`FilterMetadataController`, it stays disabled, its reason is rewritten to say so, and the decision is
recorded in `state.json`.

### D3 - Records keep one constructor

`LogFieldNames` and `JsonLogEncoderConfig` each lose their non-canonical constructor. Their factories
(`ecs()`, `otel()`, `flat()`) and `JsonLoggingInitializer` already use the canonical one. A caller that
used the short form passes `null` for `commitId` or `BuildIdentity.absent()` for `build`, which is what
the deleted constructors did. The compact constructor of `JsonLogEncoderConfig` already maps `null`
to `absent()`.

**Check:** none mechanical beyond revapi. "No constructor exists only for compatibility" is a fact
about intent, and no bytecode or source rule can tell such a constructor from a convenience
constructor. The `major-release-removals` capability states the rule, and revapi forces a written
justification for every future removal.

### D4 - Redaction: one marker, and `isMask` means it

`LEGACY_SETTINGS_MASK` is read only by `AuditStoreIntegrationTest`, which proves that `audit-0004`
rewrote `[redacted]`. The test spells the literal itself, with a `SUPPRESS CHECKSTYLE ID
SecondRedactionMask` line if the rule fires on a test constant. `LEGACY_HEADER_MASK` is read by
nothing. Once both are gone, `isMask` is `MASK.equals(value)`. It is kept because it is the readable
predicate, and it is narrowed rather than removed.

The `SecondRedactionMask` Checkstyle rule then has one suppression fewer to tolerate. That is a
tightening, not a weakening.

### D5 - The revapi entries

There is one entry per removed element, in the format `consolidate-grpc-contracts-and-tokens` set:

- `java.field.removed` ×6: the four `MessageHeaders.LEGACY_*` fields and the two `Redaction.LEGACY_*`
  fields.
- `java.method.removed` ×3: the two constructors and `ODataFilterWebAutoConfiguration.odataFilterExceptionHandler()`.
- `java.class.removed` ×1: `ODataFilterExceptionHandler`.

Each `justification` names this change, the owner's 3.0.0 decision and the replacement.

Two more, `java.annotation.removed` ×2, cover the `@ConditionalOnClass` guards dropped from the odata
mapper and bundle bean methods. A removed `static final String` is `java.field.removedWithConstant`, not
`java.field.removed`. The first build against a real baseline showed this, and the six field entries use
that code.

**What revapi checks here, and what it does not.** At a major increment the root POM's semver rule
(`"major": "breaking"`) accepts every breaking difference, entry or not. So at 3.0.0 the entries are the
written record, not a gate. They were verified by building the touched modules as `2.0.1-SNAPSHOT`
against a locally built 2.0.0, where an unmatched difference fails: none of the twelve differences was
reported.

### POMs

- Root `pom.xml`: the revapi entries.
- `ludwig-bom`: no change.
- `ludwig-service-parent`: no change.
- Module POMs: `odata-filter-spring-boot-starter` loses one `<optional>`, and
  `grpc-client-spring-boot-starter` gets a comment correction.

## Risks / Trade-offs

- **Wire: a pre-1.1.0 record on a long-retention or compacted topic** reads without its type or
  version. The type then reads as unknown and the version as 1. Mitigation: the changelog states the
  precondition. The release history shows the dual write predates every supported producer.
- **Configuration: a deployment that set `odata.filter.web.problem-detail-advice-enabled=false`** to
  hand-write its own handler now gets the pipeline's rendering as well. Its own advice still precedes
  the shared one, which registers at `LOWEST_PRECEDENCE` (`problem-detail-pipeline`). The changelog
  names the property as removed.
- **A non-web consumer of odata-filter** gains `web-core-spring-boot-starter` and `spring-web` on its
  classpath, but not `spring-webmvc`. Its autoconfiguration is servlet-conditional.
