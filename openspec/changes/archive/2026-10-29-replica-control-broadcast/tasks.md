## 1. Cache registry (`cache-spring-boot-starter`)

- [x] 1.1 `LudwigCacheRegistry.evictAll(String name)` (local and shared invalidation, `UnknownCacheException` for an
      unknown name); verify `mvn -pl :cache-spring-boot-starter -am verify`, which includes `ModuleIndependenceTest`

## 2. Channel (`job-core`)

- [x] 2.1 Changesets `0002` and `0003` plus their includes; verify `scripts/check_migrations.sh`
- [x] 2.2 `ReplicaControlPoller` (heartbeat, select, apply, ack, applied-id set, purge under lock) and
      `ludwig.job-core.control.*` properties; verify `mvn -pl :job-core verify -Dit.test=ReplicaControlIT`, a
      two-context test that simulates two replicas against one Postgres
- [x] 2.3 `ReplicaControl` SPI, startup validator, controller (`/admin/v1/replicas`, `/controls/{control}`,
      `/commands/{id}`), `ReplicaCommandViews`, `replicas` operations and their bundle keys; verify the
      `replica-control` scenarios in `ReplicaControlIT` and `mvn -pl :job-core test`

## 3. Caches area (`job-core`)

- [x] 3.1 POM: optional `cache-spring-boot-starter`; `CacheAdminAutoConfiguration`, `caches` operations, `GET
      /admin/v1/caches`, the `cacheevict` control with purpose-dependent tier; a fail-closed auto-configuration test;
      verify `mvn -pl :job-core verify -Dit.test=CacheAdminIT` and `scripts/manifest.sh build && scripts/manifest.sh
      stale`

## 4. Services

- [x] 4.1 `notification-service` grants: `replicas.*` and `caches.*` to `NOTIFICATION_ADMIN`, and the read tiers to
      `NOTIFICATION_SUPPORT`; verify `mvn -pl :notification-service -am verify`
- [x] 4.2 `crud-service-example` grants `replicas.*` and `caches.*` to `CATALOG_ADMIN`; verify `mvn -pl
      :crud-service-example -am verify`
- [x] 4.3 Regenerate both OpenAPI documents; verify `scripts/api-docs.sh missing`

## 5. Documentation

- [x] 5.1 READMEs (EN and RU) of `job-core` and the cache starter: the Administration section, the polling
      arithmetic, the idempotency rule for controls; admin guide §8 areas table; remove the replica item's broadcast
      half from the follow-ups list; verify heading-count parity and `openspec validate replica-control-broadcast
      --strict`

## 6. Verification gate

- [x] 6.1 `scripts/gate.sh --change replica-control-broadcast job-core cache-spring-boot-starter`
