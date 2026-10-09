## 1. `odata-filter-spring-boot-starter` - the policy projection

- [x] 1.1 Add a `metadataName` member to `@FilterPolicy`, defaulting to empty (not published), with javadoc
  stating that it is a published API identifier and therefore not derived from the class name.
  Verified by: `mvn -pl :odata-filter-spring-boot-starter -am verify`
- [x] 1.2 Add the document types in a new `ru.ludwigandreas.odatafilter.metadata` package: the entity-level
  document (published name, `maxDepth`, `maxPageSize`, `defaultPageSize`, `maxNestedPropertyDepth`,
  `defaultOrderBy`) and the per-property entry (path, type name, permitted operators, sortable). Records,
  holding no `Class` and no JPA type.
  Verified by: `mvn -pl :odata-filter-spring-boot-starter -am verify`
- [x] 1.3 Add the document builder, projecting an `EntityFilterPolicy` plus a caller role set into the
  document by calling `FilterFieldPolicy.permitsRoles` and `sortable` - not by re-implementing either.
  Omit every path the caller may not use; carry no role names.
  Verified by: `mvn -pl :odata-filter-spring-boot-starter test -Dtest=FilterMetadataDocumentTest`
- [x] 1.4 Add the agreement test from design D4 over the existing `Employee`/`Department` test entities: for
  several role sets, every path in the document is accepted by `FieldAccessValidator`, every advertised
  operator is accepted on its path, and no omitted path is accepted. Javadoc states why neither ArchUnit nor
  Checkstyle can express this.
  Verified by: `mvn -pl :odata-filter-spring-boot-starter test -Dtest=FilterMetadataAgreementTest`

## 2. `odata-filter-spring-boot-starter` - the registry

- [x] 2.1 Add `FilterMetadataRegistry`, built at startup from
  `EntityManagerFactory.getMetamodel().getEntities()`, keeping Java types whose `@FilterPolicy` declares a
  `metadataName`, failing startup on a duplicate name with a message naming both classes. Carry the comment
  from design D4's table: the document is a projection of `EntityFilterPolicy`, and any field list declared
  beside the annotations is the defect the comment exists to prevent.
  Verified by: `mvn -pl :odata-filter-spring-boot-starter test -Dtest=FilterMetadataRegistryTest`
- [x] 2.2 Add a test asserting the duplicate-name startup failure names both classes, and one asserting an
  entity with filterable fields but no `metadataName` is absent from the registry.
  Verified by: `mvn -pl :odata-filter-spring-boot-starter test -Dtest=FilterMetadataRegistryTest`

## 3. `odata-filter-spring-boot-starter` - the endpoint

- [x] 3.1 Add `odata.filter.metadata.base-path` (unset by default) and
  `odata.filter.metadata.enabled` to `ODataFilterProperties`, with javadoc stating that unset means no
  endpoint rather than an endpoint that refuses.
  Verified by: `mvn -pl :odata-filter-spring-boot-starter -am verify`
- [x] 3.2 Add the controller, resolving roles through `FilterPrincipalResolver` and the entity through
  `FilterMetadataRegistry`, mounted at the configured base path.
  Verified by: `mvn -pl :odata-filter-spring-boot-starter test -Dtest=FilterMetadataControllerTest`
- [x] 3.3 Add the new `ludwig.odata.error.*` code for an unpublished or unknown entity to
  `ODataFilterProblemMapper` as a 404, with keys in **both**
  `i18n/ludwig-odata-filter-messages.properties` and `_ru.properties`.
  Verified by: `mvn -pl :odata-filter-spring-boot-starter test -Dtest=ODataFilterProblemMapperTest` plus the
  existing bundle key-set parity assertion
- [x] 3.4 Register the controller and the registry in a new auto-configuration, conditional on a servlet web
  application, an `EntityManagerFactory` bean and the configured base path; add it to
  `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.
  Verified by: `mvn -pl :odata-filter-spring-boot-starter test -Dtest=*AutoConfiguration*Test`
- [x] 3.5 Add a context test asserting that with no base path configured, no controller bean and no registry
  bean exist - the surface is absent, not refusing.
  Verified by: `mvn -pl :odata-filter-spring-boot-starter test -Dtest=*AutoConfiguration*Test`
- [x] 3.6 Add `odata.filter.metadata.served` to `ODataFilterMetrics`, its Micrometer implementation and its
  no-op implementation, tagged by entity.
  Verified by: `mvn -pl :odata-filter-spring-boot-starter test -Dtest=ODataFilterServiceMetricsTest`

## 4. `crud-service-example` - the reference arrangement

- [x] 4.1 Declare `metadataName` on `ProductEntity` and `CategoryEntity` and configure the base path in the
  service's `application.yml`.
  Verified by: `mvn -pl :crud-service-example verify`
- [x] 4.2 Add an integration test (named `...IT` or `...IntegrationTest`, or failsafe will not run it)
  asserting: an admin caller sees the role-restricted path, a non-admin caller does not, an unpublished
  entity is a 404 in the shared problem shape, and every operator the document advertises is accepted by the
  product search endpoint.
  Verified by: `mvn -pl :crud-service-example verify`

## 5. Documentation

- [x] 5.1 Add the discovery section to `sources/odata-filter-spring-boot-starter/README.md` **and**
  `README.ru.md`, including why the document is not called `$metadata`, why it is opt-in, why it carries no
  role names, and why there is no cache (design D3).
  Verified by: `openspec validate add-odata-filter-metadata`, and both files carry the same `##` heading set
- [x] 5.2 Add `## [Unreleased]` entries under `Added` to `CHANGELOG.md` and `CHANGELOG.ru.md`.
  Verified by: both locales carry the same `##` heading set

## 6. The verification gate, in full

`scripts/gate.sh --list --change add-odata-filter-metadata odata-filter-spring-boot-starter
crud-service-example` prints the commands below and works out the dependents; module names are passed bare
and it prints the `:artifactId` form. Run `--list` first and confirm it agrees with this list.

- [x] 6.1 `scripts/check_image_pins.sh` and `scripts/check_api_baseline.sh`
- [x] 6.2 `mvn -q validate`
- [x] 6.3 The two touched modules: `mvn -pl :odata-filter-spring-boot-starter -am verify` and
  `mvn -pl :crud-service-example -am verify`
- [x] 6.4 The remaining in-repo dependents: `mvn -pl :export-spring-boot-starter -am verify`,
  `mvn -pl :notification-service -am verify`, `mvn -pl :user-settings-spring-boot-starter -am verify`
- [x] 6.5 `openspec validate add-odata-filter-metadata`
- [x] 6.6 `scripts/manifest.sh stale` exits 0 - no POM changed, so this must pass with no rebuild
- [x] 6.7 Write `receipt.json` per `docs/agent-state.md`, with the real gate results, test counts, retries
  and anything unresolved
