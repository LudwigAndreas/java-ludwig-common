## 1. The projection core (`identity-projection-spring-boot-starter`)

- [x] 1.1 Feed SPI in `ru.ludwigandreas.identity.feed`: `IdentityChange` (`Upsert`, `Disable`, `Remove`), `UserProfile`,
      `Contact`, `ApplyOutcome`, `IdentityProjection` (`apply`, `applyAll`), and `IdentityProjectionService` rewritten
      onto it: complete-state upsert, staleness, discard of a blank subject, contact policy, after-commit eviction,
      `PrincipalDisabledEvent` for disable and remove. Verify
      `mvn -pl :identity-projection-spring-boot-starter test -Dtest='IdentityProjection*Test'`.
- [x] 1.2 Sources: `ludwig.identity.sources.<name>` (`role-mapping`, `unmapped-roles`), the name grammar, role
      mapping and normalisation inside `apply`, ownership by `source_system` (`FOREIGN_SOURCE`), the `IdentityFeed`
      SPI, and startup validation (an undeclared feed source fails; a declared source with no feed is logged). Verify
      `mvn -pl :identity-projection-spring-boot-starter test -Dtest='IdentitySource*Test,RoleMapping*Test'`.
- [x] 1.3 Sweeps: changeset for `identity_sweep_listed`, `beginSweep` / `listed` / `complete` / `abandon`, the
      `ludwig.identity.sweep.max-disable-ratio` abort, set-wise disable with events and eviction after commit, and
      cleanup of stale sweep rows. Verify `mvn -pl :identity-projection-spring-boot-starter verify -Dit.test='Sweep*IT'`
      (against Postgres: 99 of 100 listed disables one; an empty listing aborts; another source is untouched).
- [x] 1.4 Metrics `ludwig_identity_changes_total`, `ludwig_identity_sweeps_total`,
      `ludwig_identity_sweep_disabled_total`; the startup failure on `ludwig.identity.kafka.topic`. Verify
      `mvn -pl :identity-projection-spring-boot-starter test -Dtest='IdentityMetrics*Test,RemovedIdentityKeys*Test'`.
- [x] 1.5 Remove the Kafka package, `OidcUserEvent*` and the `messaging-spring-boot-starter` dependency;
      `IdentityKafkaAutoConfiguration` goes with them. Verify `mvn -pl :identity-projection-spring-boot-starter verify`
      and that `scripts/manifest.sh module sources/identity-projection-spring-boot-starter` no longer lists messaging.
- [x] 1.6 The `identity` area: `identity.source.read` with its description (EN and RU) and
      `GET /admin/v1/identity/sources` composed from projection counts and `IdentityFeed.status()`. Verify
      `mvn -pl :identity-projection-spring-boot-starter verify -Dit.test='IdentityAdmin*IT'` and the bundle key-set
      parity check (`mvn -pl :identity-projection-spring-boot-starter checkstyle:check`).
- [x] 1.7 README (EN and RU): the projection, the feed SPI, sources and role mapping, sweeps, a "write your own feed"
      example, removed keys. Verify the heading-count parity of the two READMEs.

## 2. The Kafka source (`identity-source-kafka-spring-boot-starter`)

- [x] 2.1 New module (POM parented by `common`, package `ru.ludwigandreas.identity.kafka`, auto-configuration
      imports, `ArchitectureTest`); register it in the root `pom.xml`, `ludwig-bom` and `jacoco-aggregate`, then
      `scripts/manifest.sh build`. Verify `mvn -q validate` and `scripts/manifest.sh stale`.
- [x] 2.2 `IdentityUserEvent` v1, its mapping to `IdentityChange`, one listener over every
      `ludwig.identity.kafka.subscriptions.<source>.topic` with the topic selecting the source, the mandatory
      `group-id`, `ludwig.identity.kafka.enabled`, dead-lettering of a malformed payload, and the `IdentityFeed` bean
      per subscription. Verify `mvn -pl :identity-source-kafka-spring-boot-starter verify` (the existing listener ITs
      moved and extended: two topics to two sources, missing group fails startup, the container factory exists).
- [x] 2.3 README (EN and RU): the event contract, subscriptions, consumer settings. Verify heading-count parity.

## 3. The directory source (`identity-source-directory-spring-boot-starter`)

- [x] 3.1 New module as in 2.1, package `ru.ludwigandreas.identity.directory`, with its changelog for
      `identity_directory_state` and `ludwig.identity.directory.liquibase.enabled`. Verify `mvn -q validate`,
      `scripts/manifest.sh stale` and `scripts/check_migrations.sh`.
- [x] 3.2 `IdentityDirectory`, `DirectoryQuery`, `DirectoryPage`; startup validation (undeclared source, two
      directories for one source). Verify `mvn -pl :identity-source-directory-spring-boot-starter test
      -Dtest='DirectoryValidation*Test'`.
- [x] 3.3 Incremental sync: page loop from the stored watermark, `applyAll` and the watermark in one transaction,
      fallback to a sweep without a watermark or a change filter, failure recorded and isolated per source. Verify
      `mvn -pl :identity-source-directory-spring-boot-starter verify -Dit.test='DirectorySync*IT'` (resume after an
      interrupted page; a failing source leaves its watermark and another source syncs).
- [x] 3.4 Sweep through the projection's sweep API, abandoned on failure; the jobs
      `ludwig-identity-<source>-sync` / `-sweep` on job-core with run locks and
      `ludwig.identity.directory.sources.<source>.{sync-interval,sweep-interval,page-size}`; the metric
      `ludwig_identity_directory_run_seconds`; the `IdentityFeed` status (watermark, last sync, last sweep). Verify
      `mvn -pl :identity-source-directory-spring-boot-starter verify -Dit.test='DirectorySweep*IT,DirectoryJobs*IT'`
      (a sweep failing on its last page changes nothing; the jobs are listed and `runSoon` is `409` under lease).
- [x] 3.5 README (EN and RU): implementing a directory against a REST provider with `@LudwigRestClient`, ordering
      contract, intervals, the threshold, the runbook. Verify heading-count parity.

## 4. Enforcement (`architecture-rules`)

- [x] 4.1 Rule `identity.writes-go-through-the-projection` with a passing and a failing fixture, README rows (EN and
      RU). Verify `mvn -pl :architecture-rules verify`.

## 5. Services

- [x] 5.1 `notification-service`: add the Kafka source, declare `ludwig.identity.sources.oidc`, move the topic to
      `subscriptions.oidc.topic`, set the group; the local seed declares a `local` source and writes recipients
      through `IdentityProjection`. Verify `mvn -pl :notification-service -am verify` (the local-run ITs pass unchanged
      in what they assert).
- [x] 5.2 `crud-service-example`: the same migration. Verify `mvn -pl :crud-service-example -am verify`.
- [x] 5.3 Regenerate both OpenAPI documents (the sources listing). Verify `scripts/api-docs.sh check`.

## 6. Documentation

- [x] 6.1 The admin guide (EN and RU): identity sources, the sources listing, the sweep threshold runbook entry, the
      migration. `CHANGELOG` (EN and RU) under `Unreleased`. Add `identity-source-scim` to
      `openspec/intents/admin-surface-follow-ups.md`. Verify heading-count parity and
      `openspec validate identity-sources --strict`.

## 7. Verification gate

- [x] 7.1 `scripts/gate.sh --change identity-sources identity-projection-spring-boot-starter
      identity-source-kafka-spring-boot-starter identity-source-directory-spring-boot-starter architecture-rules`,
      which runs every in-repo dependent, including both services.
