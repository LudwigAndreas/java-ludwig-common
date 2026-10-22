## 1. Bookkeeping

- [x] 1.1 Write `state.json` with its `contract` block before the first code edit (goal, output,
  constraints - including "no `.mvn/maven.config` edit", "every revapi exception names this change",
  "no test changed to accommodate the gRPC server" - and `done_when` from
  `scripts/gate.sh --list`). `rollback_point` is `git rev-parse HEAD`; note that `add-grpc-starters`
  is uncommitted beneath it.
  Proves it: `python3 -m json.tool openspec/changes/consolidate-grpc-contracts-and-tokens/state.json`
- [x] 1.2 In `add-grpc-starters`: remove the "A contract module is created" scenario from its
  `grpc-contract` delta and append to its `state.json` `decisions` that task 8.2 is superseded by
  this change.
  Proves it: `openspec validate add-grpc-starters && openspec validate consolidate-grpc-contracts-and-tokens`

## 2. `outbound-token-core`

- [x] 2.1 Create `sources/outbound-token-core` (library, parent `common` with `relativePath`, imports
  `ludwig-bom`, package `ru.ludwigandreas.outboundtoken`, `slf4j-api` required,
  `spring-security-oauth2-client` and `spring-security-oauth2-resource-server` optional). Register
  it in the root POM, `ludwig-bom` and `build/jacoco-aggregate`.
  Proves it: `scripts/manifest.sh build && scripts/manifest.sh layout && mvn -pl :outbound-token-core -am verify`
- [x] 2.2 Move `CachedToken` and `CachingTokenSource` (rest-client's, with `getAsync`) in, with
  rest-client's `CachingTokenSourceTest` moved and the single-flight-under-forced-refresh case from
  `grpc-client` added.
  Proves it: `mvn -pl :outbound-token-core -am verify -Dtest='CachingTokenSource*Test'`
- [x] 2.3 Add `ClientCredentialsTokenMinter` (`TokenMintingException` naming the registration and the
  error code) and `InboundBearerToken`, with unit tests: unknown registration, narrowed scopes, a
  token-endpoint error; an authenticated `JwtAuthenticationToken`, an unauthenticated one, none.
  Proves it: `mvn -pl :outbound-token-core -am verify`

## 3. `rest-client-spring-boot-starter` onto the library

- [x] 3.1 Depend on `outbound-token-core`; delete `auth.CachedToken` and `auth.CachingTokenSource`;
  use the library's types, minter and `InboundBearerToken` in
  `ClientCredentialsAuthenticationProvider` and `TokenRelayAuthenticationProvider`, keeping every
  existing exception message. Every existing test passes unchanged except imports.
  Proves it: `mvn -pl :rest-client-spring-boot-starter -am verify`
- [x] 3.2 Add the module-local ArchUnit test of design D5 (no own token cache, minter or token
  reader).
  Proves it: `mvn -pl :rest-client-spring-boot-starter -am verify -Dtest=TokenOwnershipTest`
- [x] 3.3 Add the `revapi.differences` entries of design D6 to the root POM, one per removed class and
  one per any further public-signature difference the move causes, each justified.
  Proves it: `mvn -q validate` (and, on CI where the baseline resolves,
  `scripts/check_api_baseline.sh`)
- [x] 3.4 Run the dependents' gates: `export-spring-boot-starter`, `reconciliation-spring-boot-starter`,
  `test-support-local`, `crud-service-example`.
  Proves it: `mvn -pl :export-spring-boot-starter,:reconciliation-spring-boot-starter,:test-support-local,:crud-service-example -am verify`

## 4. `grpc-client-spring-boot-starter` onto the library

- [x] 4.1 Depend on `outbound-token-core`; delete `auth.CachedToken`, `auth.CachingTokenSource`,
  `auth.ClientCredentialsMinter` and `auth.InboundToken`; relay reads
  `GrpcCallContext.INBOUND_BEARER_TOKEN` then `InboundBearerToken.current()`. `GrpcClientAuthIT` and
  `GrpcServerToClientIT` pass unchanged.
  Proves it: `mvn -pl :grpc-client-spring-boot-starter -am verify`
- [x] 4.2 Add the module-local ArchUnit test of design D5.
  Proves it: `mvn -pl :grpc-client-spring-boot-starter -am verify -Dtest=TokenOwnershipTest`

## 5. The contract parent and its checks

- [x] 5.1 Create `build/ludwig-grpc-api-parent` (pom packaging, parent `common`, design D1) and
  register its `<module>` in the root POM.
  Proves it: `mvn -q -pl :ludwig-grpc-api-parent validate`
- [x] 5.2 In `scripts/project_index.py`: the `contract-parent` and `contract` roles, their layout
  directories and gates, and the five contract checks of design D2, each exiting non-zero with the
  module and the missing thing named. Self-test fixtures under `scripts/testdata/` make each check
  both fire and not fire.
  Proves it: `scripts/manifest.sh build && scripts/manifest.sh layout` and the fixtures' test command
- [x] 5.3 Delete `scripts/new_grpc_api.sh` and `scripts/new_grpc_api.py`; rewrite `grpc-core`'s
  README "Publishing a contract" (both locales) around the parent and the check.
  Proves it: `test ! -e scripts/new_grpc_api.sh && openspec validate consolidate-grpc-contracts-and-tokens`

## 6. Protobuf-aware MapStruct for services

- [x] 6.1 Before any change, build both services and copy their generated mapper sources
  (`target/generated-sources/annotations/**/*MapperImpl.java`) to the scratchpad.
  Proves it: `mvn -pl :crud-service-example,:notification-service -am compile` and the copied file count
- [x] 6.2 Add `protobuf-spi.version` (1.47) to `ludwig-bom` and `protobuf-spi-impl` to
  `ludwig-service-parent`'s `annotationProcessorPaths` after `mapstruct-processor`; rebuild and
  compare every copied mapper byte for byte. Any difference stops this task (design D3).
  Proves it: `mvn -pl :crud-service-example,:notification-service -am compile` and `diff -r`

## 7. The worked example

- [x] 7.1 Create `sources/catalog-grpc-api` - its POM only the parent, `relativePath`, `artifactId`,
  name and description - with `ludwig/catalog/v1/catalog.proto` (`CatalogService.GetProduct`,
  `NO_SIDE_EFFECTS`, a product message covering an optional field and a repeated field), registered
  in the root POM, `ludwig-bom` and `build/jacoco-aggregate`.
  Proves it: `scripts/manifest.sh build && scripts/manifest.sh layout && scripts/check_protos.sh && mvn -pl :catalog-grpc-api -am verify`
- [x] 7.2 In `crud-service-example`: move the role checks of `get`, `search`, `update` and `delete`
  from `ProductController` to `ProductServiceImpl` (design D4; `create` stays, with the reason at the
  annotation); depend on `grpc-server-spring-boot-starter` and `catalog-grpc-api`; add
  `web.grpc.ProductMessageMapper` (MapStruct) and `web.grpc.CatalogGrpcService` delegating to the
  existing product service. Unit-test the mapper for the three `grpc-message-mapping` mapping
  scenarios; every existing REST authorization test passes unchanged.
  Proves it: `mvn -pl :crud-service-example -am verify -Dtest=ProductMessageMapperTest`
- [ ] 7.3 Configuration of design D4: TLS bundle in `application.yml`, loopback plaintext in
  `application-local.yml`, jib ports 8080/8081/9090 in the service's own POM.
  Proves it: `mvn -pl :crud-service-example -am verify` and the local profile starting
  (`mvn -pl :crud-service-example spring-boot:run -Dspring-boot.run.profiles=local`, gRPC on 127.0.0.1:9090)
- [x] 7.4 Run the gRPC server in-process for the module's tests without changing any test, and add
  `CatalogGrpcIT`: an authenticated `GetProduct` returns what the REST endpoint returns; an unknown
  id is `NOT_FOUND` with the product-not-found code; a caller with no catalog role is
  `PERMISSION_DENIED`. Report the suite's duration before and after.
  Proves it: `mvn -pl :crud-service-example -am verify`

## 8. Documentation

- [x] 8.1 Remove the owed-work sections about the duplicated token cache from both client READMEs
  (both locales) and from `CLAUDE.md`; write `outbound-token-core`'s README (both locales); add it,
  `ludwig-grpc-api-parent` and `catalog-grpc-api` to the root README's module table; update the
  `CLAUDE.md` gRPC bullet and three-POM section, and `openspec/config.yaml`, for the fourth POM.
  Proves it: `openspec validate consolidate-grpc-contracts-and-tokens`
- [x] 8.2 `crud-service-example` README (both locales): the gRPC endpoint, the mapper, the TLS and
  local configuration, port 9090.
  Proves it: `openspec validate consolidate-grpc-contracts-and-tokens`
- [x] 8.3 A `3.0.0` heading in `CHANGELOG.md` and `CHANGELOG.ru.md` listing the breaking removal with
  its replacement import, the contract parent and the protobuf mapping.
  Proves it: `scripts/check_build_metadata.sh` (and `-Prelease`'s changelog check on release)

## 9. Verification gate, in full

- [x] 9.1 Manifest current and layout clean.
  Proves it: `scripts/manifest.sh build && scripts/manifest.sh stale && scripts/manifest.sh layout`
- [ ] 9.2 File-only checks.
  Proves it: `scripts/check_protos.sh && scripts/check_migrations.sh && scripts/check_image_pins.sh && scripts/check_api_baseline.sh && scripts/check_build_metadata.sh`
- [x] 9.3 Full build - the only gate, because `ludwig-bom`, `ludwig-service-parent` and the root POM
  change - from an empty `~/.m2/repository/ru/ludwigandreas`, and the aggregate report.
  Proves it: `mvn -q validate && mvn clean install && python3 scripts/check_aggregate_report.py`
- [x] 9.4 Validate the change.
  Proves it: `openspec validate consolidate-grpc-contracts-and-tokens --strict`
