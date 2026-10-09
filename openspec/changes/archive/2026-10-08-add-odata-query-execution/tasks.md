## 1. Release sequencing check (do this first)

- [x] 1.1 Confirm no `v*` tag is published yet: `git tag --list 'v*'` prints nothing. If it does, stop and
  apply the design's revapi fallback (keep the old resolver and the old `parse` overload for one release,
  both `@Deprecated(forRemoval = true)`) before continuing, and record the decision in `state.json`.
  Verified by: `git tag --list 'v*'` and, after the module changes land,
  `mvn -pl :odata-filter-spring-boot-starter -am verify` passing with revapi armed.

## 2. `web-core-spring-boot-starter` - the response envelope

- [x] 2.1 Add `long offset` to `PageResponse`, widen `totalElements` to `Long` and `totalPages` to
  `Integer`, annotate the record so null members are omitted from the JSON, and update every factory
  (`of(Page)`, `of(Page, Function)`, `ofAll`, `empty`, `map`) plus the javadoc, which must state that
  `page` is derived from the offset and lossy for an unaligned offset and therefore never the only
  position reported.
  Verified by: `mvn -pl :web-core-spring-boot-starter -am verify`
- [x] 2.2 Add unit tests: an unaligned offset reports the offset it was given; a null total is absent from
  the serialized JSON rather than present as `null`; `ofAll` and `empty` report offset 0.
  Verified by: `mvn -pl :web-core-spring-boot-starter test -Dtest=PageResponseTest`

## 3. `odata-filter-spring-boot-starter` - the query options

- [x] 3.1 Add `ODataQueryOptions` (`filter`, `orderBy`, `top`, `skip`, `count`) in
  `ru.ludwigandreas.odatafilter.web`, holding no entity type and no QueryDSL type, with a javadoc stating
  why it is a record rather than four parameters.
  Verified by: `mvn -pl :odata-filter-spring-boot-starter -am verify`
- [x] 3.2 Change `ODataFilterService.parse` to take `(Class<T>, ODataQueryOptions)`, parse and validate
  `$count` (rejecting a non-boolean value through `FilterSyntaxException`), and carry the resolved
  `countRequested` onto `ODataQuery`.
  Verified by: `mvn -pl :odata-filter-spring-boot-starter test -Dtest=ODataFilterService*Test`
- [x] 3.3 Change `ODataQuery.predicate()` to return `Optional<Predicate>`, absent when the caller supplied
  no `$filter`, and drop the `Expressions.TRUE` default.
  Verified by: `mvn -pl :odata-filter-spring-boot-starter test -Dtest=ODataFilterService*Test`
- [x] 3.4 Add the `ludwig.odata.error.*` key for the `$count` rejection to **both**
  `i18n/ludwig-odata-filter-messages.properties` and `_ru.properties`, and map it in
  `ODataFilterProblemMapper`.
  Verified by: `mvn -pl :odata-filter-spring-boot-starter test -Dtest=ODataFilterProblemMapperTest` plus
  the existing bundle key-set parity assertion
- [x] 3.5 Add `ODataQueryOptionsArgumentResolver`, reusing the deleted resolver's request-reading half
  (`$`-prefixed names with non-prefixed aliases, honouring `dollar-prefixed-parameters-only`) and
  stopping before any parsing. Register it in `ODataFilterWebAutoConfiguration` unconditionally for a
  servlet web application.
  Verified by: `mvn -pl :odata-filter-spring-boot-starter test -Dtest=ODataFilterWebAutoConfigurationTest`
- [x] 3.6 Delete `ODataQueryArgumentResolver`, `odata.filter.web.argument-resolver-enabled` (field,
  getter, setter) and the `WebMvcConfigurer` bean that installed it.
  Verified by: `grep -r ODataQueryArgumentResolver sources services` returns nothing, and
  `mvn -pl :odata-filter-spring-boot-starter -am verify`

## 4. `odata-filter-spring-boot-starter` - ordering and execution

- [x] 4.1 Expose the ordering conversion publicly (entity type + resolved `Sort` ->
  `OrderSpecifier<?>[]`), built on the same root derivation `PredicateBuilder` uses, replacing the
  private `typedPath` duplication rather than adding a second walk.
  Verified by: `mvn -pl :odata-filter-spring-boot-starter test -Dtest=*OrderSpecifier*Test`
- [x] 4.2 Add `ODataQueryExecutor` with a content-query hook and an optional count-query hook, applying
  predicate, ordering, offset, limit and the conditional count. Include the root-alias assertion from
  design D2, with javadoc stating why no static check can express it.
  Verified by: `mvn -pl :odata-filter-spring-boot-starter test -Dtest=ODataQueryExecutorTest`
- [x] 4.3 Add a unit test for the alias assertion: a root whose metadata name is not the derived alias
  fails with a message naming both aliases, rather than producing a query.
  Verified by: `mvn -pl :odata-filter-spring-boot-starter test -Dtest=ODataQueryExecutorTest`
- [x] 4.4 Register the executor in a new auto-configuration conditional on a `JPAQueryFactory` bean, so
  that a module using `ODataFilterService` with no `EntityManager` still starts.
  Verified by: `mvn -pl :odata-filter-spring-boot-starter test -Dtest=*AutoConfiguration*Test`
- [x] 4.5 Extend `ODataFilterIntegrationTest` for the full HTTP -> executor -> Postgres path, covering an
  unaligned `$skip`, `$count=false` issuing no count query, and ordering over a nested path.
  Verified by: `mvn -pl :odata-filter-spring-boot-starter verify` (needs Docker; `mvn test` alone will
  not run it)

## 5. POMs

- [x] 5.1 Add the managed `springdoc-openapi-starter-common` entry to `build/ludwig-bom/pom.xml` under the
  existing `${springdoc.version}`, and declare it `<optional>true</optional>` in the module POM.
  Verified by: `mvn -q validate` and `mvn -pl :odata-filter-spring-boot-starter -am verify`
- [x] 5.2 Add the springdoc `OperationCustomizer` documenting the five query parameters for any operation
  declaring an `ODataQueryOptions` parameter, `@ConditionalOnClass` on springdoc and
  `@ConditionalOnMissingBean`.
  Verified by: `mvn -pl :odata-filter-spring-boot-starter test -Dtest=*OpenApi*Test`
- [x] 5.3 Regenerate the manifest: `scripts/manifest.sh build`.
  Verified by: `scripts/manifest.sh stale` exits 0

## 6. `architecture-rules` - the new check

- [x] 6.1 Add the ArchUnit rule forbidding a `PathBuilder` field or local in a service's repository
  packages, with javadoc naming the modules that legitimately own a caller-facing path vocabulary and
  stating why this is ArchUnit's and not Checkstyle's.
  Verified by: `mvn -pl :architecture-rules -am verify`
- [x] 6.2 Confirm the rule actually fires: it fails against the pre-change
  `ProductQueryRepositoryImpl`/`DeliveryQueryRepositoryImpl` shape and passes after task 7.
  Verified by: `mvn -pl :crud-service-example test -Dtest=*ArchitectureTest` before and after task 7.1

## 7. `crud-service-example`

- [x] 7.1 Rewrite `ProductQueryRepositoryImpl.search` on the executor: delete `ROOT`,
  `orderSpecifiers`, `orderSpecifier`, `count` and the `@SuppressWarnings`, keep the category fetch join
  via the content-query hook, and contribute the `DataAccessGuard` predicate so the scope stays in the
  same `WHERE` clause.
  Verified by: `mvn -pl :crud-service-example verify`
- [x] 7.2 Replace `ProductSearchCriteria` and `ProductQuery` with `ODataQueryOptions` through controller,
  service and repository, leaving `ProductController.search` with one options parameter.
  Verified by: `mvn -pl :crud-service-example verify`
- [x] 7.3 Update the service's integration tests for the new envelope members (`offset`, absent total
  under `$count=false`) and assert the unaligned-`$skip` case end to end.
  Verified by: `mvn -pl :crud-service-example verify`

## 8. `notification-service`

- [x] 8.1 Rewrite `DeliveryQueryRepositoryImpl.search` on the executor: delete `ROOT`,
  `orderSpecifiers`, `orderSpecifier`, the search-only `count` and the `@SuppressWarnings`. Leave every
  other query in that class untouched - they are fixed-path QueryDSL and not in scope.
  Verified by: `mvn -pl :notification-service verify`
- [x] 8.2 Replace `DeliverySearchCriteria` with `ODataQueryOptions` through controller, service and
  repository.
  Verified by: `mvn -pl :notification-service verify`

## 9. `export-spring-boot-starter`

- [x] 9.1 Update `ODataReportFilterParser` for the new `parse` signature (an `ODataQueryOptions` carrying
  only the filter) and return `query.predicate()` directly, deleting the `Optional.ofNullable` wrapper.
  Keep and update the javadoc on why paging and ordering are not passed through.
  Verified by: `mvn -pl :export-spring-boot-starter verify`
- [x] 9.2 Add a unit test asserting a report request with no filter yields an empty `Optional`, so nothing
  is ANDed into the report query.
  Verified by: `mvn -pl :export-spring-boot-starter test -Dtest=ODataReportFilterParserTest`

## 10. Documentation

- [x] 10.1 Update `sources/odata-filter-spring-boot-starter/README.md` **and** `README.ru.md`: the quick
  start becomes the executor, the "Why not bind it straight into the controller parameter" section becomes
  the `ODataQueryOptions` story and why its resolver is not the deleted one, and the `$count` paragraph is
  rewritten - it currently argues the flag buys the caller nothing.
  Verified by: `openspec validate add-odata-query-execution`, and the two files' section headings match
- [x] 10.2 Update `sources/web-core-spring-boot-starter/README.md` and `README.ru.md` for the
  `PageResponse` members.
  Verified by: both files carry the same `##` heading set
- [x] 10.3 Add `## [Unreleased]` entries to `CHANGELOG.md` and `CHANGELOG.ru.md` under `Added`, `Changed`
  and `Removed`, written for a consumer of the platform. The four breaking items are named explicitly.
  Verified by: both locales carry the same `##` heading set

## 11. The verification gate, in full

`scripts/gate.sh --list --change add-odata-query-execution web-core-spring-boot-starter
odata-filter-spring-boot-starter export-spring-boot-starter crud-service-example notification-service
architecture-rules` prints the commands below and works out the dependents; module names are passed bare and
it prints the `:artifactId` form. Run `--list` first and confirm it agrees with this list.

- [x] 11.1 `scripts/manifest.sh build` (a POM changed), then `scripts/manifest.sh stale` exits 0
- [x] 11.2 `scripts/check_image_pins.sh` and `scripts/check_api_baseline.sh`
- [x] 11.3 `mvn -q validate`
- [x] 11.4 The six touched modules: `mvn -pl :web-core-spring-boot-starter -am verify`,
  `:odata-filter-spring-boot-starter`, `:export-spring-boot-starter`, `:crud-service-example`,
  `:notification-service`, `:architecture-rules`
- [x] 11.5 Every remaining in-repo dependent, which is the direction that catches a breaking change:
  `:audit-spring-boot-starter`, `:db-core`, `:file-action-spring-boot-starter`,
  `:file-ingest-spring-boot-starter`, `:idempotency-spring-boot-starter`, `:messaging-spring-boot-starter`,
  `:object-storage-spring-boot-starter`, `:observability-spring-boot-starter`, `:pat-spring-boot-starter`,
  `:rest-client-spring-boot-starter`, `:security-spring-boot-starter`, `:user-settings-spring-boot-starter`
- [x] 11.6 `openspec validate add-odata-query-execution`
- [x] 11.7 Because `build/ludwig-bom/pom.xml` changed, the narrow gate is not sufficient on its own:
  `mvn clean install`
- [x] 11.8 Write `receipt.json` per `docs/agent-state.md`, with the real gate results, test counts, retries
  and anything unresolved
