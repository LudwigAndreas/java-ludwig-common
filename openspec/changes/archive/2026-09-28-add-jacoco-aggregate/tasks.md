## 1. Enable JaCoCo once, at the root

- [x] 1.1 Move `jacoco-maven-plugin` from the root POM's `<pluginManagement>` activation into its
      active `<build><plugins>`. Verified by `mvn -pl :checkstyle-rules help:effective-pom` showing
      the three JaCoCo executions on a module that never declared it.
- [x] 1.2 Delete the 20 bare `<plugin>` re-declarations from the library module POMs, after
      asserting each is bare. `ludwig-service-parent`'s configured declaration is not touched.
      Verified by a scan reporting 0 bare declarations remaining and 1 configured.

## 2. The aggregator module

- [x] 2.1 Create `build/jacoco-aggregate/pom.xml`: packaging `pom`, parented by `common` with
      `<relativePath>../../pom.xml</relativePath>`, importing `ludwig-bom`, depending on all 27 jar
      modules, binding `report-aggregate` to `verify`, and skipping deploy. Verified by
      `mvn -q validate` succeeding.
- [x] 2.2 Write `build/jacoco-aggregate/README.md` and `README.ru.md`, per the repository's
      every-module-has-both convention. Verified by `scripts/manifest.sh build` recording both.
- [x] 2.3 Add `<module>build/jacoco-aggregate</module>` to the root POM, last. Verified by
      `grep -c '<module>' pom.xml` = 30.

## 3. Tooling

- [x] 3.1 `scripts/project_index.py`: add an `aggregate` role and map it to `build/` in `LAYOUT`;
      exclude the aggregator from every module's `inRepoDependents`. Verified by
      `scripts/manifest.sh layout` passing and `scripts/manifest.sh module sources/job-core` not
      listing `jacoco-aggregate` among its dependents.

## 4. The verification gate

- [x] 4.1 `scripts/manifest.sh stale` and `scripts/manifest.sh layout` both exit 0.
- [x] 4.2 `mvn clean install` over the full reactor succeeds, with the aggregator built last.
- [x] 4.3 **Assert the report has content, not merely that it exists.** The aggregate XML names all
      27 jar modules' packages, and the five previously-uninstrumented modules
      (`object-storage-spring-boot-starter`, `file-ingest-spring-boot-starter`, `test-support`,
      `test-support-security`, `checkstyle-rules`) appear with non-zero instruction counts. A build
      that generated an empty report would otherwise pass this gate.
- [x] 4.4 `openspec validate add-jacoco-aggregate` passes.
