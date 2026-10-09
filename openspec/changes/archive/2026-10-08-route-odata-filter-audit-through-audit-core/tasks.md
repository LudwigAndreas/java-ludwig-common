## 1. `odata-filter-spring-boot-starter` - the dependency

- [x] 1.1 Add the `audit-core` dependency to the module POM (compile scope, version from `ludwig-bom`, which
  is already imported).
  Verified by: `mvn -pl :odata-filter-spring-boot-starter -am verify`
- [x] 1.2 `scripts/manifest.sh build`, then confirm `project-index.json` shows `audit-core` under this
  module's `inRepoDependencies` and this module under `audit-core`'s `inRepoDependents`.
  Verified by: `scripts/manifest.sh module sources/odata-filter-spring-boot-starter` and
  `scripts/manifest.sh stale` exiting 0

## 2. `odata-filter-spring-boot-starter` - the event

- [x] 2.1 Add a filter-summary derivation from the validated `FilterNode` AST: the `/`-joined property paths
  named and the operators applied to each, in a stable order, with no literal values in any form.
  Verified by: `mvn -pl :odata-filter-spring-boot-starter test -Dtest=FilterSummaryTest`
- [x] 2.2 Replace `FilterAppliedEvent`'s `resolvedPredicate` and `rawFilter` components with that summary,
  and add `toAuditEvent()` returning an `AuditEvent` carrying the entity type, the summary and the caller
  roles - and no values. Javadoc states that the predicate string and the raw filter were removed because
  both are the caller's literal values verbatim.
  Verified by: `mvn -pl :odata-filter-spring-boot-starter -am verify`
- [x] 2.3 Add the unit test from design D5's table: over the parser's existing test filters, the summary
  contains every path and operator and none of the literals, including a filter whose literal is an e-mail
  address. Javadoc states why no static check can express this.
  Verified by: `mvn -pl :odata-filter-spring-boot-starter test -Dtest=FilterSummaryTest`

## 3. `odata-filter-spring-boot-starter` - the sink call

- [x] 3.1 Inject `AuditSink` into `ODataFilterService` and call `record(event.toAuditEvent())` on a
  successful parse, with **no** try/catch - `AuditFailurePolicy` owns that decision.
  Verified by: `mvn -pl :odata-filter-spring-boot-starter test -Dtest=ODataFilterServiceAuditTest`
- [x] 3.2 Keep publishing the application event, and rewrite `FilterAppliedEvent`'s class javadoc so it no
  longer describes itself as the way to build an audit trail.
  Verified by: `mvn -pl :odata-filter-spring-boot-starter test -Dtest=ODataFilterServiceAuditTest` asserting
  one parse both records to the sink and publishes the event
- [x] 3.3 Wire the sink in `ODataFilterAutoConfiguration`, and add a test asserting a parse succeeds and
  records when the application's sink is the no-op one.
  Verified by: `mvn -pl :odata-filter-spring-boot-starter test -Dtest=*AutoConfiguration*Test`
- [x] 3.4 Add a test asserting a sink that throws is **not** caught by this module, so the configured
  `AuditFailurePolicy` is what decides the outcome.
  Verified by: `mvn -pl :odata-filter-spring-boot-starter test -Dtest=ODataFilterServiceAuditTest`

## 4. `architecture-rules` - close the gap

- [x] 4.1 Add the rule from design D5 to the existing `RuleGroup.AUDIT`: a type declared in a package named
  `audit` must declare a `toAuditEvent()` or a method returning an `AuditEvent`, or be handed to an
  `AuditSink` within its module. Javadoc states the anchor is the module's own declaration of intent and
  that a rename is the one evasion no check can catch.
  Verified by: `mvn -pl :architecture-rules -am verify`
- [x] 4.2 Confirm the rule actually fires: it fails against the pre-change `FilterAppliedEvent` shape and
  passes after task 2.2.
  Verified by: `mvn -pl :architecture-rules test` with the fixture, before and after
- [x] 4.3 Confirm it does not fire on the nine modules that already comply.
  Verified by: `mvn -pl :crud-service-example -am verify` and `mvn -pl :notification-service -am verify`,
  both of which run the rule group against their own and their dependencies' packages

## 5. Documentation

- [x] 5.1 Update `sources/odata-filter-spring-boot-starter/README.md` **and** `README.ru.md`: the
  "write an `@EventListener` for it to build an audit trail" sentence becomes the sink story, naming the
  audit category used and stating that its `AuditFailurePolicy` - and therefore whether a sink outage fails
  a query - is the deployment's configuration.
  Verified by: `openspec validate route-odata-filter-audit-through-audit-core`, and both files carry the same
  `##` heading set
- [x] 5.2 Add `## [Unreleased]` entries to `CHANGELOG.md` and `CHANGELOG.ru.md` under `Changed` and
  `Removed`, naming the removed record components as breaking.
  Verified by: both locales carry the same `##` heading set

## 6. The verification gate, in full

`scripts/gate.sh --list --change route-odata-filter-audit-through-audit-core
odata-filter-spring-boot-starter architecture-rules` prints the commands below and works out the dependents;
module names are passed bare and it prints the `:artifactId` form. Run `--list` first and confirm it agrees
with this list.

- [x] 6.1 `scripts/manifest.sh build` (the module POM changed)
- [x] 6.2 `scripts/check_image_pins.sh` and `scripts/check_api_baseline.sh`
- [x] 6.3 `mvn -q validate`
- [x] 6.4 The two touched modules: `mvn -pl :odata-filter-spring-boot-starter -am verify` and
  `mvn -pl :architecture-rules -am verify`
- [x] 6.5 Every in-repo dependent: `mvn -pl :crud-service-example -am verify`,
  `mvn -pl :export-spring-boot-starter -am verify`, `mvn -pl :notification-service -am verify`,
  `mvn -pl :user-settings-spring-boot-starter -am verify`. These are also the modules that enable
  `architecture-rules`' rule sets, so they are what proves task 4.3
- [x] 6.6 `openspec validate route-odata-filter-audit-through-audit-core`
- [x] 6.7 `scripts/manifest.sh stale` exits 0
- [x] 6.8 Write `receipt.json` per `docs/agent-state.md`, with the real gate results, test counts, retries
  and anything unresolved
