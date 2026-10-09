## 1. Trace the cycles before moving anything

- [x] 1.1 Re-enable `cycles.modules-are-free-of-cycles` in
  `sources/odata-filter-spring-boot-starter/src/test/java/ru/ludwigandreas/odatafilter/architecture/ArchitectureTest.java`
  and capture the full report, so the work starts from the actual edge list rather than from design D1-D3's
  prediction.
  Verified by: `mvn -pl :odata-filter-spring-boot-starter -am test -Dtest=ArchitectureTest` failing with the
  violation list, and that list saved into the change directory as `cycles-before.txt`
- [x] 1.2 For each distinct cycle, write down the one edge that is wrong and why - "which package has a type
  that belongs in the other". Amend design D1 and D2 in place where the trace contradicts them, with the
  trace quoted.
  Verified by: `openspec validate fix-odata-filter-package-cycles`, and every cycle in `cycles-before.txt`
  having a named edge

## 2. Split the wiring from the properties (design D1)

- [x] 2.1 Move `ODataFilterProperties` out of `config` into its own package, leaving the four
  auto-configuration classes behind, so nothing in the module depends on `config` at all.
  Verified by: `mvn -pl :odata-filter-spring-boot-starter -am verify`
- [x] 2.2 Confirm `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` still
  names the four classes correctly and every one is still found.
  Verified by: `mvn -pl :odata-filter-spring-boot-starter test -Dtest=*AutoConfiguration*Test`
- [x] 2.3 Re-run the trace: the `config -> ... -> config` family should be gone. Record which cycles remain.
  Verified by: `mvn -pl :odata-filter-spring-boot-starter -am test -Dtest=ArchitectureTest`

## 3. Cut the remaining cycles (design D2)

- [x] 3.1 Apply the cut the trace named for `core <-> validation <-> policy` - either handing `validation`
  what it needs instead of `policy`'s types, or merging the two. The decision is task 1.2's, not this task's.
  Verified by: `mvn -pl :odata-filter-spring-boot-starter -am verify`
- [x] 3.2 Repeat for every cycle still in the report until the rule passes with nothing disabled for it.
  Verified by: `mvn -pl :odata-filter-spring-boot-starter -am test -Dtest=ArchitectureTest` with
  `cycles.modules-are-free-of-cycles` enabled
- [x] 3.3 Assert no public type changed behaviour: the diff for each moved type is its `package` line and its
  importers, and nothing else.
  Verified by: `git diff` reviewed per file, and the four dependents' integration tests passing unchanged in
  task 6

## 4. Bound properties (design D3)

- [x] 4.1 Decide and record which option D3 takes. If it is the annotation: declare the constraints the
  properties actually have (`maxDepth`, `maxPageSize`, `defaultPageSize`, `maxNestedPropertyDepth`,
  `maxExpressionLength` all at least 1), add `hibernate-validator` as an optional dependency with its
  version in `build/ludwig-bom/pom.xml`, and re-enable the rule. If it is the refusal: state the reason in
  this change's design and leave the rule disabled with that reason at the point of the disable.
  Verified by: `mvn -pl :odata-filter-spring-boot-starter -am verify`
- [x] 4.2 **If the annotation was taken**, repeat the regression that caused the exclusion in the first
  place: a consumer with no validation implementation must still start. `crud-service-example` is that
  consumer.
  Verified by: `mvn -pl :crud-service-example -am verify`, and the odata module's own 15 integration tests
  passing
- [x] 4.3 Add a test asserting a `max-page-size` of 0 is refused at startup rather than accepted and failing
  every query - the defect the missing validation currently allows.
  Verified by: `mvn -pl :odata-filter-spring-boot-starter test -Dtest=*Properties*Test`

## 5. Documentation

- [x] 5.1 Remove the "known debt" section from the module's `ArchitectureTest` javadoc and the two disables,
  leaving only the disables that describe a library rather than a service.
  Verified by: `grep -c "cycles.modules-are-free-of-cycles"` in that file returning 0
- [x] 5.2 Note the package moves in `CHANGELOG.md` **and** `CHANGELOG.ru.md` under `Changed`, marked
  **BREAKING** for a consumer's imports.
  Verified by: both locales carry the same `##` and `###` heading sets
- [x] 5.3 If any package named in `sources/odata-filter-spring-boot-starter/README.md` or `README.ru.md`
  moved, update both.
  Verified by: both files carry the same heading set, and no stale package name remains

## 6. The verification gate, in full

`scripts/gate.sh --list --change fix-odata-filter-package-cycles odata-filter-spring-boot-starter` prints
the commands and works out the dependents; module names are passed bare. Run `--list` first.

- [x] 6.1 `scripts/manifest.sh build` if any POM changed, then `scripts/manifest.sh stale` exits 0
- [x] 6.2 `scripts/check_image_pins.sh`
- [x] 6.3 `mvn -q validate`
- [x] 6.4 `mvn -pl :odata-filter-spring-boot-starter -am verify`
- [x] 6.5 Every in-repo dependent: `mvn -pl :crud-service-example -am verify`,
  `mvn -pl :export-spring-boot-starter -am verify`, `mvn -pl :notification-service -am verify`,
  `mvn -pl :user-settings-spring-boot-starter -am verify`
- [x] 6.6 `openspec validate fix-odata-filter-package-cycles`
- [x] 6.7 `mvn clean install` - required if `ludwig-bom` changed under task 4.1, and cheap insurance either
  way for a change that moves public types
- [x] 6.8 Write `receipt.json` per `docs/agent-state.md`. It must state whether BOTH rules are enabled at
  the end; if either is still disabled, this change did not do its job and the receipt says so.
