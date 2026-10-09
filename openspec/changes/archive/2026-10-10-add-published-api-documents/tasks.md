# Tasks

## 1. Establish the generator against the service that already has springdoc

`notification-service` needs no POM change, so the canonicalizer and the comparison can be proven
there before `crud-service-example` is touched. If the document turns out not to be byte-stable, it
is found here rather than tangled with a new dependency.

- [x] 1.1 Confirm `/v3/api-docs.yaml` is served and capture one document, to establish whether YAML is
  available in this springdoc version before the design's choice is relied on.
  Proves it: `mvn -pl :notification-service verify -Dit.test=ApiDocumentIT -DfailIfNoTests=false`
  after 1.2, or a throwaway assertion in `InboxOpenApiIT` if the path is missing.
- [x] 1.2 Add `ApiDocumentIT` to `notification-service`, extending `NotificationTestBase` so it joins
  the existing shared Testcontainers context rather than starting a second PostgreSQL. It fetches the
  document, canonicalizes it (every map sorted by key, `servers` stripped), writes it to
  `target/openapi/notification-service.openapi.yaml`, and compares against
  `docs/api/notification-service.openapi.yaml`, failing with the refresh command on any difference.
  Proves it: `mvn -pl :notification-service verify -Dit.test=ApiDocumentIT -DfailIfNoTests=false`
  fails with "committed copy is missing" on the first run.
- [x] 1.3 Generate `docs/api/notification-service.openapi.yaml` with
  `mvn -pl :notification-service verify -Dit.test=ApiDocumentIT -DfailIfNoTests=false -Dludwig.apidocs.write=true`,
  then read the output and confirm the `ProblemDetail` schema and the announcement and inbox paths are
  present.
  Proves it: re-running the same command without `-Dludwig.apidocs.write` now passes.
- [x] 1.4 Run the comparison twice in separate JVMs and confirm the bytes are identical, which is the
  determinism the design depends on. If a field is unstable, normalize that field in the
  canonicalizer with a comment naming it - do not weaken the comparison.
  Proves it: two consecutive runs of the 1.3 command, both passing, with `git status` clean after.
- [x] 1.5 Add assertions to `ApiDocumentIT` for the two properties the spec names as behaviour rather
  than incidental content: the `ProblemDetail` schema exists as a reusable component, and its `code`
  member is documented as the stable one.
  Proves it: `mvn -pl :notification-service verify -Dit.test=ApiDocumentIT -DfailIfNoTests=false`

## 2. Give `crud-service-example` a document

- [x] 2.1 Add `org.springdoc:springdoc-openapi-starter-webmvc-ui` to
  `services/crud-service-example/pom.xml` with no `<version>`, with a comment stating why a service
  takes `-webmvc-ui` where `odata-filter` takes `-common` optional.
  Proves it: `mvn -pl :crud-service-example dependency:tree | grep springdoc` shows 2.6.0 resolved
  from the BOM, and `scripts/manifest.sh build` then `scripts/manifest.sh stale` reports current.
- [x] 2.2 Add `springdoc` paths to `services/crud-service-example/src/main/resources/application.yml`
  matching `notification-service`'s (`/v3/api-docs`, `/swagger-ui.html`).
  Proves it: covered by 2.4 - the document cannot be fetched if the path is wrong.
- [x] 2.3 Add `OpenApiConfig` to `ru.ludwigandreas.example.catalog.config` declaring the `info` block
  and the shared `ProblemDetail` schema, following `notification-service`'s and stating in javadoc why
  the schema is declared by hand.
  Proves it: `mvn -pl :crud-service-example test`
- [x] 2.4 Add `ApiDocumentIT` to `crud-service-example`'s `integration` package, reusing the shape
  from 1.2 and importing `TestSecurityConfiguration` and `PostgresContainerConfiguration` with the
  same `@TestPropertySource` as `CatalogIntegrationTest`, so it joins that context rather than
  starting a second container.
  Proves it: `mvn -pl :crud-service-example verify -Dit.test=ApiDocumentIT -DfailIfNoTests=false`
- [x] 2.5 Generate `docs/api/crud-service-example.openapi.yaml` and read it to confirm
  `ProductController`'s collection lists `$filter`, `$orderby`, `$top`, `$skip` and `$count` - the
  property that was unverifiable before this change. If they are absent, stop: that is a finding about
  `ODataQueryOptionsOpenApiCustomizer`, to be reported rather than worked around.
  Proves it: `mvn -pl :crud-service-example verify -Dit.test=ApiDocumentIT -DfailIfNoTests=false -Dludwig.apidocs.write=true`
- [x] 2.6 Add the query-option assertion from the spec's fourth requirement to that `ApiDocumentIT`,
  and verify it is load-bearing by temporarily removing the customizer's effect.
  Proves it: the assertion fails when the parameters are absent and passes when they are present.

## 3. Document the artifacts

- [x] 3.1 Write `docs/api/README.md`: what the documents are, that they are generated and must not be
  hand-edited, the refresh command, and the explicit statement that they describe what the services
  currently serve and are not a compatibility baseline.
  Proves it: `openspec validate add-published-api-documents --strict` for the change's consistency,
  plus the spec's fifth requirement being satisfied by the file's own wording.
- [x] 3.2 Add a line to `services/crud-service-example/README.md` and `README.ru.md` recording that
  the service now serves `/v3/api-docs` and `/swagger-ui.html`, and that its document is published
  under `docs/api/`. Keep the two locales' section structure in parity.
  Proves it: `mvn -q validate` (Checkstyle's `NonAsciiSourceText` governs source, not READMEs); parity
  checked by reading both files' heading lists.
- [x] 3.3 Add the same line to `services/notification-service/README.md` and `README.ru.md`, where the
  springdoc endpoints already existed but the published document is new.
  Proves it: as 3.2.
- [x] 3.4 Note the published documents in `docs/runbook.md` where a reviewer would look for what to
  check in a pull request.
  Proves it: `openspec validate add-published-api-documents --strict`

## 4. The verification gate, in full

- [x] 4.1 `scripts/manifest.sh build` - a POM changed in 2.1.
- [x] 4.2 `mvn -q validate` - Checkstyle, every module.
- [x] 4.3 `mvn -pl :crud-service-example -am verify`
- [x] 4.4 `mvn -pl :notification-service -am verify`
- [x] 4.5 In-repo dependents: `project-index.json` `inRepoDependents` is `[]` for both services, so
  there is no dependents step. State this explicitly rather than omitting it.
- [x] 4.6 `scripts/check_migrations.sh` and `scripts/check_image_pins.sh` - no changeset or image
  reference changed, so both must still pass unchanged.
- [x] 4.7 `openspec validate add-published-api-documents --strict`
- [x] 4.8 Confirm `git status` is clean apart from the intended files, proving the comparison does not
  rewrite tracked files during an ordinary `verify`.
