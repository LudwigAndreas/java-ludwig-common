# PROJECT_INDEX

> **Generated file — do not edit.** Regenerate with `scripts/manifest.sh build`. Hand edits are lost and, worse, believed in the meantime.

| | |
|---|---|
| revision | `1.1.0-SNAPSHOT` |
| modules | 29 |
| commit | `61879ba` (`master`) |
| generated | 2026-09-27T16:08:30.606712Z |
| **freshness** | POM-set SHA `0665e28ffa69a979` · newest POM `user-settings-spring-boot-starter/pom.xml` @ 2026-09-27T09:19:08.805574Z |

The freshness row is how you tell this index is stale: `scripts/manifest.sh stale` recomputes the POM-set SHA and exits non-zero if it differs. It is content-based, not timestamp-based, so a checkout or a branch switch does not report a false stale.

## How to use this instead of searching

This file and `project-index.json` beside it are generated from the POMs and the filesystem by
`scripts/manifest.sh build`. They are the answer to every *structural* question about this
repository, and consulting them is cheaper by an order of magnitude than a search across 29
modules.

Read down this ladder and **stop at the first rung that answers your question**:

| # | Question | Where |
|---|---|---|
| 1 | Which module owns this? What depends on it? Which POM tier is it? | this file / `project-index.json` / `scripts/manifest.sh module <path>` |
| 2 | *Why* is this module shaped this way? | that module's `README.md`, linked per module below |
| 3 | Which files exist under this path shape? | `code-index` MCP: `find_files` |
| 4 | What is in this file? Where is this symbol defined? | `code-index` MCP: `get_file_summary`, then `get_symbol_body` |
| 5 | Who calls this? Which code contains this text? | `code-index` MCP: `search_code_advanced` — **not** `called_by`, which is intra-file only |
| 6 | Anything the above could not answer | `rg` — last resort |

Reaching rung 6 means saying, in your response, why rungs 1-5 could not answer it. That
sentence is the maintenance mechanism: a question the index cannot answer is a bug to file
against the index, not a reason to stop using it.

**Two fields decide whether a change builds**, so read them before adding any dependency:

- `inRepoDependencies` lists **every scope**, because Maven builds the reactor DAG from all
  declared dependencies and `test`, `provided` and `optional` do not exempt an edge. This is
  what the dependency-direction check in `docs/agent-operations.md` §5.1 is run against.
- `inRepoDependents` is the reverse edge, and it is the list the verification gate walks: every
  dependent of a module you touched gets its own `mvn -pl <dependent> -am verify`.

`ludwig-bom` is imported by every module (`importsLudwigBom`) rather than depended on, so it
carries no dependent edges. Changing it, or `ludwig-service-parent`, means a full
`mvn clean install` — there is no narrower gate.


## Modules by role

### bom (1)

| module | parent POM | package root | surfaces | in-repo deps | dependents |
|---|---|---|---|---|---|
| **ludwig-bom** | `common` | `—` |  | — | — |

### parent (1)

| module | parent POM | package root | surfaces | in-repo deps | dependents |
|---|---|---|---|---|---|
| **ludwig-service-parent** | `spring-boot-starter-parent` | `—` |  | — | — |

### library (4)

| module | parent POM | package root | surfaces | in-repo deps | dependents |
|---|---|---|---|---|---|
| **audit-core** | `common` | `ru.ludwigandreas.audit` | none | — | `audit-spring-boot-starter`, `export-spring-boot-starter`, `file-ingest-spring-boot-starter`, `hot-reload-spring-boot-starter`, `idempotency-spring-boot-starter`, `messaging-spring-boot-starter`, `observability-spring-boot-starter`, `outbox-spring-boot-starter`, `reconciliation-spring-boot-starter`, `rest-client-spring-boot-starter`, `security-spring-boot-starter` |
| **db-core** | `common` | `ru.ludwigandreas.db.core` | none | `test-support` (test), `web-core-spring-boot-starter` | `audit-spring-boot-starter`, `crud-service-example`, `export-spring-boot-starter`, `file-ingest-spring-boot-starter`, `idempotency-spring-boot-starter`, `identity-projection-spring-boot-starter`, `notification-service`, `outbox-spring-boot-starter`, `reconciliation-spring-boot-starter`, `user-settings-spring-boot-starter` |
| **job-core** | `common` | `ru.ludwigandreas.job.core` | none | `test-support` (test) | `audit-spring-boot-starter`, `export-spring-boot-starter`, `file-ingest-spring-boot-starter`, `idempotency-spring-boot-starter`, `notification-service`, `outbox-spring-boot-starter`, `reconciliation-spring-boot-starter` |
| **jira-client** | `common` | `ru.ludwigandreas.jira` | none | — | — |

### starter (17)

| module | parent POM | package root | surfaces | in-repo deps | dependents |
|---|---|---|---|---|---|
| **odata-filter-spring-boot-starter** | `common` | `ru.ludwigandreas.odatafilter` | rest | `test-support` (test), `web-core-spring-boot-starter` | `crud-service-example`, `export-spring-boot-starter`, `notification-service`, `user-settings-spring-boot-starter` |
| **audit-spring-boot-starter** | `common` | `ru.ludwigandreas.audit.store` | rest | `audit-core`, `db-core`, `job-core`, `outbox-spring-boot-starter`, `test-support` (test), `web-core-spring-boot-starter` | `user-settings-spring-boot-starter` |
| **idempotency-spring-boot-starter** | `common` | `ru.ludwigandreas.idempotency` | rest | `audit-core`, `db-core`, `job-core`, `test-support` (test), `web-core-spring-boot-starter` | `messaging-spring-boot-starter`, `notification-service` |
| **cache-spring-boot-starter** | `common` | `ru.ludwigandreas.cache` | none | `test-support` (test) | `security-spring-boot-starter`, `user-settings-spring-boot-starter` |
| **messaging-spring-boot-starter** | `common` | `ru.ludwigandreas.messaging` | rest | `architecture-rules` (test), `audit-core`, `idempotency-spring-boot-starter`, `test-support` (test), `web-core-spring-boot-starter` | `identity-projection-spring-boot-starter`, `notification-service`, `outbox-spring-boot-starter`, `user-settings-spring-boot-starter` |
| **export-spring-boot-starter** | `common` | `ru.ludwigandreas.export` | rest | `audit-core`, `db-core`, `hot-reload-spring-boot-starter`, `job-core`, `object-storage-spring-boot-starter`, `observability-spring-boot-starter`, `odata-filter-spring-boot-starter`, `outbox-spring-boot-starter`, `rest-client-spring-boot-starter`, `security-spring-boot-starter`, `test-support` (test), `web-core-spring-boot-starter` | `crud-service-example` |
| **object-storage-spring-boot-starter** | `common` | `ru.ludwigandreas.storage` | rest | `architecture-rules` (test), `test-support` (test), `web-core-spring-boot-starter` | `export-spring-boot-starter`, `file-ingest-spring-boot-starter` |
| **file-ingest-spring-boot-starter** | `common` | `ru.ludwigandreas.ingest` | actuator | `architecture-rules` (test), `audit-core`, `db-core`, `job-core`, `object-storage-spring-boot-starter`, `outbox-spring-boot-starter`, `test-support` (test), `web-core-spring-boot-starter` | — |
| **outbox-spring-boot-starter** | `common` | `ru.ludwigandreas.outbox` | none | `audit-core`, `db-core`, `job-core`, `messaging-spring-boot-starter`, `test-support` (test) | `audit-spring-boot-starter`, `crud-service-example`, `export-spring-boot-starter`, `file-ingest-spring-boot-starter`, `notification-service`, `user-settings-spring-boot-starter` |
| **reconciliation-spring-boot-starter** | `common` | `ru.ludwigandreas.reconciliation` | actuator | `audit-core`, `db-core`, `job-core`, `observability-spring-boot-starter`, `rest-client-spring-boot-starter`, `test-support` (test) | — |
| **security-spring-boot-starter** | `common` | `ru.ludwigandreas.security` | none | `audit-core`, `cache-spring-boot-starter`, `web-core-spring-boot-starter` | `crud-service-example`, `export-spring-boot-starter`, `identity-projection-spring-boot-starter`, `notification-service`, `test-support-security`, `user-settings-spring-boot-starter` |
| **identity-projection-spring-boot-starter** | `common` | `ru.ludwigandreas.identity` | none | `db-core`, `messaging-spring-boot-starter`, `security-spring-boot-starter` | `crud-service-example`, `notification-service` |
| **hot-reload-spring-boot-starter** | `common` | `ru.ludwigandreas.hotreload` | none | `audit-core` | `export-spring-boot-starter`, `notification-service`, `observability-spring-boot-starter`, `user-settings-spring-boot-starter` |
| **web-core-spring-boot-starter** | `common` | `ru.ludwigandreas.webcore` | rest | — | `audit-spring-boot-starter`, `crud-service-example`, `db-core`, `export-spring-boot-starter`, `file-ingest-spring-boot-starter`, `idempotency-spring-boot-starter`, `messaging-spring-boot-starter`, `notification-service`, `object-storage-spring-boot-starter`, `observability-spring-boot-starter`, `odata-filter-spring-boot-starter`, `rest-client-spring-boot-starter`, `security-spring-boot-starter`, `user-settings-spring-boot-starter` |
| **observability-spring-boot-starter** | `common` | `ru.ludwigandreas.observability` | none | `audit-core`, `hot-reload-spring-boot-starter`, `web-core-spring-boot-starter` | `export-spring-boot-starter`, `notification-service`, `reconciliation-spring-boot-starter`, `rest-client-spring-boot-starter`, `user-settings-spring-boot-starter` |
| **rest-client-spring-boot-starter** | `common` | `ru.ludwigandreas.restclient` | rest | `audit-core`, `observability-spring-boot-starter`, `web-core-spring-boot-starter` | `crud-service-example`, `export-spring-boot-starter`, `reconciliation-spring-boot-starter` |
| **user-settings-spring-boot-starter** | `common` | `ru.ludwigandreas.usersettings` | rest | `architecture-rules` (test), `audit-spring-boot-starter`, `cache-spring-boot-starter`, `db-core`, `hot-reload-spring-boot-starter`, `messaging-spring-boot-starter`, `observability-spring-boot-starter`, `odata-filter-spring-boot-starter`, `outbox-spring-boot-starter`, `security-spring-boot-starter`, `test-support` (test), `web-core-spring-boot-starter` | `notification-service` |

### service (2)

| module | parent POM | package root | surfaces | in-repo deps | dependents |
|---|---|---|---|---|---|
| **crud-service-example** | `ludwig-service-parent` | `ru.ludwigandreas.example.catalog` | rest | `architecture-rules` (test), `db-core`, `export-spring-boot-starter`, `identity-projection-spring-boot-starter`, `odata-filter-spring-boot-starter`, `outbox-spring-boot-starter`, `rest-client-spring-boot-starter`, `security-spring-boot-starter`, `test-support-security` (test), `web-core-spring-boot-starter` | — |
| **notification-service** | `ludwig-service-parent` | `ru.ludwigandreas.notification` | actuator, rest | `architecture-rules` (test), `db-core`, `hot-reload-spring-boot-starter`, `idempotency-spring-boot-starter`, `identity-projection-spring-boot-starter`, `job-core`, `messaging-spring-boot-starter`, `observability-spring-boot-starter`, `odata-filter-spring-boot-starter`, `outbox-spring-boot-starter`, `security-spring-boot-starter`, `test-support-security` (test), `user-settings-spring-boot-starter` (provided), `web-core-spring-boot-starter` | — |

### test-support (2)

| module | parent POM | package root | surfaces | in-repo deps | dependents |
|---|---|---|---|---|---|
| **test-support** | `common` | `ru.ludwigandreas.testsupport` | none | — | `audit-spring-boot-starter`, `cache-spring-boot-starter`, `db-core`, `export-spring-boot-starter`, `file-ingest-spring-boot-starter`, `idempotency-spring-boot-starter`, `job-core`, `messaging-spring-boot-starter`, `object-storage-spring-boot-starter`, `odata-filter-spring-boot-starter`, `outbox-spring-boot-starter`, `reconciliation-spring-boot-starter`, `test-support-security`, `user-settings-spring-boot-starter` |
| **test-support-security** | `common` | `ru.ludwigandreas.testsupport.security` | none | `security-spring-boot-starter`, `test-support` | `crud-service-example`, `notification-service` |

### rules (2)

| module | parent POM | package root | surfaces | in-repo deps | dependents |
|---|---|---|---|---|---|
| **architecture-rules** | `common` | `ru.ludwigandreas.archrules` | rest | — | `crud-service-example`, `file-ingest-spring-boot-starter`, `messaging-spring-boot-starter`, `notification-service`, `object-storage-spring-boot-starter`, `user-settings-spring-boot-starter` |
| **checkstyle-rules** | `common` | `—` |  | — | — |

## Dependency graph

Every in-repo edge, at every scope, `A -> B` meaning *A declares a dependency on B*. This is the graph Maven orders the reactor by. Before adding an edge A -> B, check that B has no path back to A here.

```
odata-filter-spring-boot-starter           -> test-support  [test]
odata-filter-spring-boot-starter           -> web-core-spring-boot-starter
db-core                                    -> test-support  [test]
db-core                                    -> web-core-spring-boot-starter
audit-spring-boot-starter                  -> audit-core
audit-spring-boot-starter                  -> db-core
audit-spring-boot-starter                  -> job-core
audit-spring-boot-starter                  -> outbox-spring-boot-starter
audit-spring-boot-starter                  -> test-support  [test]
audit-spring-boot-starter                  -> web-core-spring-boot-starter
job-core                                   -> test-support  [test]
idempotency-spring-boot-starter            -> audit-core
idempotency-spring-boot-starter            -> db-core
idempotency-spring-boot-starter            -> job-core
idempotency-spring-boot-starter            -> test-support  [test]
idempotency-spring-boot-starter            -> web-core-spring-boot-starter
cache-spring-boot-starter                  -> test-support  [test]
messaging-spring-boot-starter              -> architecture-rules  [test]
messaging-spring-boot-starter              -> audit-core
messaging-spring-boot-starter              -> idempotency-spring-boot-starter
messaging-spring-boot-starter              -> test-support  [test]
messaging-spring-boot-starter              -> web-core-spring-boot-starter
export-spring-boot-starter                 -> audit-core
export-spring-boot-starter                 -> db-core
export-spring-boot-starter                 -> hot-reload-spring-boot-starter
export-spring-boot-starter                 -> job-core
export-spring-boot-starter                 -> object-storage-spring-boot-starter
export-spring-boot-starter                 -> observability-spring-boot-starter
export-spring-boot-starter                 -> odata-filter-spring-boot-starter
export-spring-boot-starter                 -> outbox-spring-boot-starter
export-spring-boot-starter                 -> rest-client-spring-boot-starter
export-spring-boot-starter                 -> security-spring-boot-starter
export-spring-boot-starter                 -> test-support  [test]
export-spring-boot-starter                 -> web-core-spring-boot-starter
object-storage-spring-boot-starter         -> architecture-rules  [test]
object-storage-spring-boot-starter         -> test-support  [test]
object-storage-spring-boot-starter         -> web-core-spring-boot-starter
file-ingest-spring-boot-starter            -> architecture-rules  [test]
file-ingest-spring-boot-starter            -> audit-core
file-ingest-spring-boot-starter            -> db-core
file-ingest-spring-boot-starter            -> job-core
file-ingest-spring-boot-starter            -> object-storage-spring-boot-starter
file-ingest-spring-boot-starter            -> outbox-spring-boot-starter
file-ingest-spring-boot-starter            -> test-support  [test]
file-ingest-spring-boot-starter            -> web-core-spring-boot-starter
outbox-spring-boot-starter                 -> audit-core
outbox-spring-boot-starter                 -> db-core
outbox-spring-boot-starter                 -> job-core
outbox-spring-boot-starter                 -> messaging-spring-boot-starter
outbox-spring-boot-starter                 -> test-support  [test]
reconciliation-spring-boot-starter         -> audit-core
reconciliation-spring-boot-starter         -> db-core
reconciliation-spring-boot-starter         -> job-core
reconciliation-spring-boot-starter         -> observability-spring-boot-starter
reconciliation-spring-boot-starter         -> rest-client-spring-boot-starter
reconciliation-spring-boot-starter         -> test-support  [test]
security-spring-boot-starter               -> audit-core
security-spring-boot-starter               -> cache-spring-boot-starter
security-spring-boot-starter               -> web-core-spring-boot-starter
identity-projection-spring-boot-starter    -> db-core
identity-projection-spring-boot-starter    -> messaging-spring-boot-starter
identity-projection-spring-boot-starter    -> security-spring-boot-starter
hot-reload-spring-boot-starter             -> audit-core
observability-spring-boot-starter          -> audit-core
observability-spring-boot-starter          -> hot-reload-spring-boot-starter
observability-spring-boot-starter          -> web-core-spring-boot-starter
rest-client-spring-boot-starter            -> audit-core
rest-client-spring-boot-starter            -> observability-spring-boot-starter
rest-client-spring-boot-starter            -> web-core-spring-boot-starter
user-settings-spring-boot-starter          -> architecture-rules  [test]
user-settings-spring-boot-starter          -> audit-spring-boot-starter
user-settings-spring-boot-starter          -> cache-spring-boot-starter
user-settings-spring-boot-starter          -> db-core
user-settings-spring-boot-starter          -> hot-reload-spring-boot-starter
user-settings-spring-boot-starter          -> messaging-spring-boot-starter
user-settings-spring-boot-starter          -> observability-spring-boot-starter
user-settings-spring-boot-starter          -> odata-filter-spring-boot-starter
user-settings-spring-boot-starter          -> outbox-spring-boot-starter
user-settings-spring-boot-starter          -> security-spring-boot-starter
user-settings-spring-boot-starter          -> test-support  [test]
user-settings-spring-boot-starter          -> web-core-spring-boot-starter
test-support-security                      -> security-spring-boot-starter
test-support-security                      -> test-support
crud-service-example                       -> architecture-rules  [test]
crud-service-example                       -> db-core
crud-service-example                       -> export-spring-boot-starter
crud-service-example                       -> identity-projection-spring-boot-starter
crud-service-example                       -> odata-filter-spring-boot-starter
crud-service-example                       -> outbox-spring-boot-starter
crud-service-example                       -> rest-client-spring-boot-starter
crud-service-example                       -> security-spring-boot-starter
crud-service-example                       -> test-support-security  [test]
crud-service-example                       -> web-core-spring-boot-starter
notification-service                       -> architecture-rules  [test]
notification-service                       -> db-core
notification-service                       -> hot-reload-spring-boot-starter
notification-service                       -> idempotency-spring-boot-starter
notification-service                       -> identity-projection-spring-boot-starter
notification-service                       -> job-core
notification-service                       -> messaging-spring-boot-starter
notification-service                       -> observability-spring-boot-starter
notification-service                       -> odata-filter-spring-boot-starter
notification-service                       -> outbox-spring-boot-starter
notification-service                       -> security-spring-boot-starter
notification-service                       -> test-support-security  [test]
notification-service                       -> user-settings-spring-boot-starter  [provided]
notification-service                       -> web-core-spring-boot-starter
```

**No in-repo dependencies at all** (8 modules) — several of them deliberately, because a module with no sibling edge can be depended on from anywhere without closing a cycle: `architecture-rules`, `audit-core`, `checkstyle-rules`, `jira-client`, `ludwig-bom`, `ludwig-service-parent`, `test-support`, `web-core-spring-boot-starter`.

## Per-module detail

### `architecture-rules`

- role **rules**, packaging `jar`, parent `common`, imports `ludwig-bom`: yes
- package root `ru.ludwigandreas.archrules`
- README [`architecture-rules/README.md`](architecture-rules/README.md) · [`architecture-rules/README.ru.md`](architecture-rules/README.ru.md)
- surfaces: rest
- test classes: 7 surefire, 1 failsafe (`*IT` / `*IntegrationTest`)
- **gate**: `mvn -q validate`, then `mvn -pl architecture-rules -am verify`, `mvn -pl crud-service-example -am verify`, `mvn -pl file-ingest-spring-boot-starter -am verify`, `mvn -pl messaging-spring-boot-starter -am verify`, `mvn -pl notification-service -am verify`, `mvn -pl object-storage-spring-boot-starter -am verify`, `mvn -pl user-settings-spring-boot-starter -am verify`

### `audit-core`

- role **library**, packaging `jar`, parent `common`, imports `ludwig-bom`: yes
- package root `ru.ludwigandreas.audit`
- README [`audit-core/README.md`](audit-core/README.md) · [`audit-core/README.ru.md`](audit-core/README.ru.md)
- surfaces: none
- auto-configuration: `audit-core/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- test dirs: unit (`audit-core/src/test/java/ru/ludwigandreas/audit/unit`)
- test classes: 5 surefire, 0 failsafe (`*IT` / `*IntegrationTest`)
- **gate**: `mvn -q validate`, then `mvn -pl audit-core -am verify`, `mvn -pl audit-spring-boot-starter -am verify`, `mvn -pl export-spring-boot-starter -am verify`, `mvn -pl file-ingest-spring-boot-starter -am verify`, `mvn -pl hot-reload-spring-boot-starter -am verify`, `mvn -pl idempotency-spring-boot-starter -am verify`, `mvn -pl messaging-spring-boot-starter -am verify`, `mvn -pl observability-spring-boot-starter -am verify`, `mvn -pl outbox-spring-boot-starter -am verify`, `mvn -pl reconciliation-spring-boot-starter -am verify`, `mvn -pl rest-client-spring-boot-starter -am verify`, `mvn -pl security-spring-boot-starter -am verify`

### `audit-spring-boot-starter`

- role **starter**, packaging `jar`, parent `common`, imports `ludwig-bom`: yes
- package root `ru.ludwigandreas.audit.store`
- README [`audit-spring-boot-starter/README.md`](audit-spring-boot-starter/README.md) · [`audit-spring-boot-starter/README.ru.md`](audit-spring-boot-starter/README.ru.md)
- surfaces: rest
- auto-configuration: `audit-spring-boot-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Liquibase: `audit-spring-boot-starter/src/main/resources/db/changelog/audit/audit-changelog.xml`
- i18n: `audit-spring-boot-starter/src/main/resources/i18n/ludwig-audit-messages.properties`, `audit-spring-boot-starter/src/main/resources/i18n/ludwig-audit-messages_ru.properties`
- test dirs: integration (`audit-spring-boot-starter/src/test/java/ru/ludwigandreas/audit/store/integration`)
- test classes: 0 surefire, 1 failsafe (`*IT` / `*IntegrationTest`)
- **gate**: `mvn -q validate`, then `mvn -pl audit-spring-boot-starter -am verify`, `mvn -pl user-settings-spring-boot-starter -am verify`

### `cache-spring-boot-starter`

- role **starter**, packaging `jar`, parent `common`, imports `ludwig-bom`: yes
- package root `ru.ludwigandreas.cache`
- README [`cache-spring-boot-starter/README.md`](cache-spring-boot-starter/README.md) · [`cache-spring-boot-starter/README.ru.md`](cache-spring-boot-starter/README.ru.md)
- surfaces: none
- auto-configuration: `cache-spring-boot-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- test dirs: architecture (`cache-spring-boot-starter/src/test/java/ru/ludwigandreas/cache/architecture`), integration (`cache-spring-boot-starter/src/test/java/ru/ludwigandreas/cache/integration`), unit (`cache-spring-boot-starter/src/test/java/ru/ludwigandreas/cache/unit`)
- test classes: 8 surefire, 1 failsafe (`*IT` / `*IntegrationTest`)
- **gate**: `mvn -q validate`, then `mvn -pl cache-spring-boot-starter -am verify`, `mvn -pl security-spring-boot-starter -am verify`, `mvn -pl user-settings-spring-boot-starter -am verify`

### `checkstyle-rules`

- role **rules**, packaging `jar`, parent `common`, imports `ludwig-bom`: no
- package root `—`
- README [`checkstyle-rules/README.md`](checkstyle-rules/README.md) · [`checkstyle-rules/README.ru.md`](checkstyle-rules/README.ru.md)
- surfaces: 
- test classes: 0 surefire, 0 failsafe (`*IT` / `*IntegrationTest`)
- **gate**: `mvn -q validate`, then `mvn -pl checkstyle-rules -am verify`

### `crud-service-example`

- role **service**, packaging `jar`, parent `ludwig-service-parent`, imports `ludwig-bom`: no
- package root `ru.ludwigandreas.example.catalog`
- README [`crud-service-example/README.md`](crud-service-example/README.md) · [`crud-service-example/README.ru.md`](crud-service-example/README.ru.md)
- surfaces: rest
- Liquibase: `crud-service-example/src/main/resources/db/changelog/changes/0001-catalog-schema.xml`, `crud-service-example/src/main/resources/db/changelog/changes/0002-catalog-reference-data.xml`, `crud-service-example/src/main/resources/db/changelog/changes/0003-catalog-security.xml`, `crud-service-example/src/main/resources/db/changelog/changes/0004-catalog-watchers.xml`, `crud-service-example/src/main/resources/db/changelog/db.changelog-master.xml`
- i18n: `crud-service-example/src/main/resources/i18n/messages.properties`, `crud-service-example/src/main/resources/i18n/messages_ru.properties`
- test dirs: architecture (`crud-service-example/src/test/java/ru/ludwigandreas/example/catalog/architecture`), integration (`crud-service-example/src/test/java/ru/ludwigandreas/example/catalog/integration`), unit (`crud-service-example/src/test/java/ru/ludwigandreas/example/catalog/unit`)
- test classes: 3 surefire, 2 failsafe (`*IT` / `*IntegrationTest`)
- **gate**: `mvn -q validate`, then `mvn -pl crud-service-example -am verify`

### `db-core`

- role **library**, packaging `jar`, parent `common`, imports `ludwig-bom`: yes
- package root `ru.ludwigandreas.db.core`
- README [`db-core/README.md`](db-core/README.md) · [`db-core/README.ru.md`](db-core/README.ru.md)
- surfaces: none
- auto-configuration: `db-core/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- i18n: `db-core/src/main/resources/i18n/ludwig-db-messages.properties`, `db-core/src/main/resources/i18n/ludwig-db-messages_ru.properties`
- test dirs: integration (`db-core/src/test/java/ru/ludwigandreas/db/core/integration`)
- test classes: 7 surefire, 2 failsafe (`*IT` / `*IntegrationTest`)
- **gate**: `mvn -q validate`, then `mvn -pl db-core -am verify`, `mvn -pl audit-spring-boot-starter -am verify`, `mvn -pl crud-service-example -am verify`, `mvn -pl export-spring-boot-starter -am verify`, `mvn -pl file-ingest-spring-boot-starter -am verify`, `mvn -pl idempotency-spring-boot-starter -am verify`, `mvn -pl identity-projection-spring-boot-starter -am verify`, `mvn -pl notification-service -am verify`, `mvn -pl outbox-spring-boot-starter -am verify`, `mvn -pl reconciliation-spring-boot-starter -am verify`, `mvn -pl user-settings-spring-boot-starter -am verify`

### `export-spring-boot-starter`

- role **starter**, packaging `jar`, parent `common`, imports `ludwig-bom`: yes
- package root `ru.ludwigandreas.export`
- README [`export-spring-boot-starter/README.md`](export-spring-boot-starter/README.md) · [`export-spring-boot-starter/README.ru.md`](export-spring-boot-starter/README.ru.md)
- surfaces: rest
- auto-configuration: `export-spring-boot-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Liquibase: `export-spring-boot-starter/src/main/resources/db/changelog/export/export-changelog.xml`
- i18n: `export-spring-boot-starter/src/main/resources/i18n/ludwig-export-messages.properties`, `export-spring-boot-starter/src/main/resources/i18n/ludwig-export-messages_ru.properties`
- test dirs: architecture (`export-spring-boot-starter/src/test/java/ru/ludwigandreas/export/architecture`), integration (`export-spring-boot-starter/src/test/java/ru/ludwigandreas/export/integration`), unit (`export-spring-boot-starter/src/test/java/ru/ludwigandreas/export/unit`)
- test classes: 16 surefire, 2 failsafe (`*IT` / `*IntegrationTest`)
- **gate**: `mvn -q validate`, then `mvn -pl export-spring-boot-starter -am verify`, `mvn -pl crud-service-example -am verify`

### `file-ingest-spring-boot-starter`

- role **starter**, packaging `jar`, parent `common`, imports `ludwig-bom`: yes
- package root `ru.ludwigandreas.ingest`
- README [`file-ingest-spring-boot-starter/README.md`](file-ingest-spring-boot-starter/README.md) · [`file-ingest-spring-boot-starter/README.ru.md`](file-ingest-spring-boot-starter/README.ru.md)
- surfaces: actuator
- auto-configuration: `file-ingest-spring-boot-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Liquibase: `file-ingest-spring-boot-starter/src/main/resources/db/changelog/file-ingest/file-ingest-changelog.xml`
- i18n: `file-ingest-spring-boot-starter/src/main/resources/i18n/ludwig-ingest-messages.properties`, `file-ingest-spring-boot-starter/src/main/resources/i18n/ludwig-ingest-messages_ru.properties`
- test dirs: architecture (`file-ingest-spring-boot-starter/src/test/java/ru/ludwigandreas/ingest/architecture`), integration (`file-ingest-spring-boot-starter/src/test/java/ru/ludwigandreas/ingest/integration`), unit (`file-ingest-spring-boot-starter/src/test/java/ru/ludwigandreas/ingest/unit`)
- test classes: 8 surefire, 9 failsafe (`*IT` / `*IntegrationTest`)
- **gate**: `mvn -q validate`, then `mvn -pl file-ingest-spring-boot-starter -am verify`

### `hot-reload-spring-boot-starter`

- role **starter**, packaging `jar`, parent `common`, imports `ludwig-bom`: yes
- package root `ru.ludwigandreas.hotreload`
- README [`hot-reload-spring-boot-starter/README.md`](hot-reload-spring-boot-starter/README.md) · [`hot-reload-spring-boot-starter/README.ru.md`](hot-reload-spring-boot-starter/README.ru.md)
- surfaces: none
- auto-configuration: `hot-reload-spring-boot-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- test classes: 13 surefire, 0 failsafe (`*IT` / `*IntegrationTest`)
- **gate**: `mvn -q validate`, then `mvn -pl hot-reload-spring-boot-starter -am verify`, `mvn -pl export-spring-boot-starter -am verify`, `mvn -pl notification-service -am verify`, `mvn -pl observability-spring-boot-starter -am verify`, `mvn -pl user-settings-spring-boot-starter -am verify`

### `idempotency-spring-boot-starter`

- role **starter**, packaging `jar`, parent `common`, imports `ludwig-bom`: yes
- package root `ru.ludwigandreas.idempotency`
- README [`idempotency-spring-boot-starter/README.md`](idempotency-spring-boot-starter/README.md) · [`idempotency-spring-boot-starter/README.ru.md`](idempotency-spring-boot-starter/README.ru.md)
- surfaces: rest
- auto-configuration: `idempotency-spring-boot-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Liquibase: `idempotency-spring-boot-starter/src/main/resources/db/changelog/idempotency/idempotency-changelog.xml`
- i18n: `idempotency-spring-boot-starter/src/main/resources/i18n/ludwig-idempotency-messages.properties`, `idempotency-spring-boot-starter/src/main/resources/i18n/ludwig-idempotency-messages_ru.properties`
- test dirs: architecture (`idempotency-spring-boot-starter/src/test/java/ru/ludwigandreas/idempotency/architecture`), integration (`idempotency-spring-boot-starter/src/test/java/ru/ludwigandreas/idempotency/integration`), unit (`idempotency-spring-boot-starter/src/test/java/ru/ludwigandreas/idempotency/unit`)
- test classes: 4 surefire, 5 failsafe (`*IT` / `*IntegrationTest`)
- **gate**: `mvn -q validate`, then `mvn -pl idempotency-spring-boot-starter -am verify`, `mvn -pl messaging-spring-boot-starter -am verify`, `mvn -pl notification-service -am verify`

### `identity-projection-spring-boot-starter`

- role **starter**, packaging `jar`, parent `common`, imports `ludwig-bom`: yes
- package root `ru.ludwigandreas.identity`
- README [`identity-projection-spring-boot-starter/README.md`](identity-projection-spring-boot-starter/README.md) · [`identity-projection-spring-boot-starter/README.ru.md`](identity-projection-spring-boot-starter/README.ru.md)
- surfaces: none
- auto-configuration: `identity-projection-spring-boot-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Liquibase: `identity-projection-spring-boot-starter/src/main/resources/db/changelog/identity/identity-changelog.xml`
- test dirs: unit (`identity-projection-spring-boot-starter/src/test/java/ru/ludwigandreas/identity/unit`)
- test classes: 1 surefire, 0 failsafe (`*IT` / `*IntegrationTest`)
- **gate**: `mvn -q validate`, then `mvn -pl identity-projection-spring-boot-starter -am verify`, `mvn -pl crud-service-example -am verify`, `mvn -pl notification-service -am verify`

### `jira-client`

- role **library**, packaging `jar`, parent `common`, imports `ludwig-bom`: yes
- package root `ru.ludwigandreas.jira`
- README [`jira-client/README.md`](jira-client/README.md) · [`jira-client/README.ru.md`](jira-client/README.ru.md)
- surfaces: none
- test dirs: unit (`jira-client/src/test/java/ru/ludwigandreas/jira/unit`)
- test classes: 10 surefire, 0 failsafe (`*IT` / `*IntegrationTest`)
- **gate**: `mvn -q validate`, then `mvn -pl jira-client -am verify`

### `job-core`

- role **library**, packaging `jar`, parent `common`, imports `ludwig-bom`: yes
- package root `ru.ludwigandreas.job.core`
- README [`job-core/README.md`](job-core/README.md) · [`job-core/README.ru.md`](job-core/README.ru.md)
- surfaces: none
- auto-configuration: `job-core/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Liquibase: `job-core/src/main/resources/db/changelog/job-core/job-core-changelog.xml`
- test dirs: integration (`job-core/src/test/java/ru/ludwigandreas/job/core/integration`), unit (`job-core/src/test/java/ru/ludwigandreas/job/core/unit`)
- test classes: 3 surefire, 1 failsafe (`*IT` / `*IntegrationTest`)
- **gate**: `mvn -q validate`, then `mvn -pl job-core -am verify`, `mvn -pl audit-spring-boot-starter -am verify`, `mvn -pl export-spring-boot-starter -am verify`, `mvn -pl file-ingest-spring-boot-starter -am verify`, `mvn -pl idempotency-spring-boot-starter -am verify`, `mvn -pl notification-service -am verify`, `mvn -pl outbox-spring-boot-starter -am verify`, `mvn -pl reconciliation-spring-boot-starter -am verify`

### `ludwig-bom`

- role **bom**, packaging `pom`, parent `common`, imports `ludwig-bom`: no
- package root `—`
- README [`ludwig-bom/README.md`](ludwig-bom/README.md) · [`ludwig-bom/README.ru.md`](ludwig-bom/README.ru.md)
- surfaces: 
- test classes: 0 surefire, 0 failsafe (`*IT` / `*IntegrationTest`)
- **gate**: `mvn clean install  (every module imports ludwig-bom)`

### `ludwig-service-parent`

- role **parent**, packaging `pom`, parent `spring-boot-starter-parent`, imports `ludwig-bom`: yes
- package root `—`
- README [`ludwig-service-parent/README.md`](ludwig-service-parent/README.md) · [`ludwig-service-parent/README.ru.md`](ludwig-service-parent/README.ru.md)
- surfaces: 
- test classes: 0 surefire, 0 failsafe (`*IT` / `*IntegrationTest`)
- **gate**: `mvn clean install  (every service inherits it)`

### `messaging-spring-boot-starter`

- role **starter**, packaging `jar`, parent `common`, imports `ludwig-bom`: yes
- package root `ru.ludwigandreas.messaging`
- README [`messaging-spring-boot-starter/README.md`](messaging-spring-boot-starter/README.md) · [`messaging-spring-boot-starter/README.ru.md`](messaging-spring-boot-starter/README.ru.md)
- surfaces: rest
- auto-configuration: `messaging-spring-boot-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- i18n: `messaging-spring-boot-starter/src/main/resources/i18n/ludwig-messaging-messages.properties`, `messaging-spring-boot-starter/src/main/resources/i18n/ludwig-messaging-messages_ru.properties`
- test dirs: architecture (`messaging-spring-boot-starter/src/test/java/ru/ludwigandreas/messaging/architecture`), integration (`messaging-spring-boot-starter/src/test/java/ru/ludwigandreas/messaging/integration`), unit (`messaging-spring-boot-starter/src/test/java/ru/ludwigandreas/messaging/unit`)
- test classes: 12 surefire, 1 failsafe (`*IT` / `*IntegrationTest`)
- **gate**: `mvn -q validate`, then `mvn -pl messaging-spring-boot-starter -am verify`, `mvn -pl identity-projection-spring-boot-starter -am verify`, `mvn -pl notification-service -am verify`, `mvn -pl outbox-spring-boot-starter -am verify`, `mvn -pl user-settings-spring-boot-starter -am verify`

### `notification-service`

- role **service**, packaging `jar`, parent `ludwig-service-parent`, imports `ludwig-bom`: no
- package root `ru.ludwigandreas.notification`
- README [`notification-service/README.md`](notification-service/README.md) · [`notification-service/README.ru.md`](notification-service/README.ru.md)
- surfaces: actuator, rest
- Liquibase: `notification-service/src/main/resources/db/changelog/changes/0001-notification-schema.xml`, `notification-service/src/main/resources/db/changelog/changes/0002-notification-recipients.xml`, `notification-service/src/main/resources/db/changelog/changes/0003-notification-platform-gaps.xml`, `notification-service/src/main/resources/db/changelog/changes/0004-preferences-move-out.xml`, `notification-service/src/main/resources/db/changelog/changes/0005-fold-lock-into-job-core.xml`, `notification-service/src/main/resources/db/changelog/changes/0006-fold-idempotency-into-starter.xml`, `notification-service/src/main/resources/db/changelog/db.changelog-master.xml`
- i18n: `notification-service/src/main/resources/i18n/notification-messages.properties`, `notification-service/src/main/resources/i18n/notification-messages_ru.properties`
- test dirs: architecture (`notification-service/src/test/java/ru/ludwigandreas/notification/architecture`), integration (`notification-service/src/test/java/ru/ludwigandreas/notification/integration`), unit (`notification-service/src/test/java/ru/ludwigandreas/notification/unit`)
- test classes: 16 surefire, 4 failsafe (`*IT` / `*IntegrationTest`)
- **gate**: `mvn -q validate`, then `mvn -pl notification-service -am verify`

### `object-storage-spring-boot-starter`

- role **starter**, packaging `jar`, parent `common`, imports `ludwig-bom`: yes
- package root `ru.ludwigandreas.storage`
- README [`object-storage-spring-boot-starter/README.md`](object-storage-spring-boot-starter/README.md) · [`object-storage-spring-boot-starter/README.ru.md`](object-storage-spring-boot-starter/README.ru.md)
- surfaces: rest
- auto-configuration: `object-storage-spring-boot-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- i18n: `object-storage-spring-boot-starter/src/main/resources/i18n/ludwig-storage-messages.properties`, `object-storage-spring-boot-starter/src/main/resources/i18n/ludwig-storage-messages_ru.properties`
- test dirs: architecture (`object-storage-spring-boot-starter/src/test/java/ru/ludwigandreas/storage/architecture`), integration (`object-storage-spring-boot-starter/src/test/java/ru/ludwigandreas/storage/integration`), unit (`object-storage-spring-boot-starter/src/test/java/ru/ludwigandreas/storage/unit`)
- test classes: 4 surefire, 2 failsafe (`*IT` / `*IntegrationTest`)
- **gate**: `mvn -q validate`, then `mvn -pl object-storage-spring-boot-starter -am verify`, `mvn -pl export-spring-boot-starter -am verify`, `mvn -pl file-ingest-spring-boot-starter -am verify`

### `observability-spring-boot-starter`

- role **starter**, packaging `jar`, parent `common`, imports `ludwig-bom`: yes
- package root `ru.ludwigandreas.observability`
- README [`observability-spring-boot-starter/README.md`](observability-spring-boot-starter/README.md) · [`observability-spring-boot-starter/README.ru.md`](observability-spring-boot-starter/README.ru.md)
- surfaces: none
- auto-configuration: `observability-spring-boot-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- test dirs: integration (`observability-spring-boot-starter/src/test/java/ru/ludwigandreas/observability/integration`), unit (`observability-spring-boot-starter/src/test/java/ru/ludwigandreas/observability/unit`)
- test classes: 13 surefire, 0 failsafe (`*IT` / `*IntegrationTest`)
- **gate**: `mvn -q validate`, then `mvn -pl observability-spring-boot-starter -am verify`, `mvn -pl export-spring-boot-starter -am verify`, `mvn -pl notification-service -am verify`, `mvn -pl reconciliation-spring-boot-starter -am verify`, `mvn -pl rest-client-spring-boot-starter -am verify`, `mvn -pl user-settings-spring-boot-starter -am verify`

### `odata-filter-spring-boot-starter`

- role **starter**, packaging `jar`, parent `common`, imports `ludwig-bom`: yes
- package root `ru.ludwigandreas.odatafilter`
- README [`odata-filter-spring-boot-starter/README.md`](odata-filter-spring-boot-starter/README.md) · [`odata-filter-spring-boot-starter/README.ru.md`](odata-filter-spring-boot-starter/README.ru.md)
- surfaces: rest
- auto-configuration: `odata-filter-spring-boot-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- i18n: `odata-filter-spring-boot-starter/src/main/resources/i18n/ludwig-odata-filter-messages.properties`, `odata-filter-spring-boot-starter/src/main/resources/i18n/ludwig-odata-filter-messages_ru.properties`
- test dirs: integration (`odata-filter-spring-boot-starter/src/test/java/ru/ludwigandreas/odatafilter/integration`)
- test classes: 8 surefire, 1 failsafe (`*IT` / `*IntegrationTest`)
- **gate**: `mvn -q validate`, then `mvn -pl odata-filter-spring-boot-starter -am verify`, `mvn -pl crud-service-example -am verify`, `mvn -pl export-spring-boot-starter -am verify`, `mvn -pl notification-service -am verify`, `mvn -pl user-settings-spring-boot-starter -am verify`

### `outbox-spring-boot-starter`

- role **starter**, packaging `jar`, parent `common`, imports `ludwig-bom`: yes
- package root `ru.ludwigandreas.outbox`
- README [`outbox-spring-boot-starter/README.md`](outbox-spring-boot-starter/README.md) · [`outbox-spring-boot-starter/README.ru.md`](outbox-spring-boot-starter/README.ru.md)
- surfaces: none
- auto-configuration: `outbox-spring-boot-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Liquibase: `outbox-spring-boot-starter/src/main/resources/db/changelog/outbox/outbox-changelog.xml`
- test dirs: integration (`outbox-spring-boot-starter/src/test/java/ru/ludwigandreas/outbox/integration`), unit (`outbox-spring-boot-starter/src/test/java/ru/ludwigandreas/outbox/unit`)
- test classes: 5 surefire, 1 failsafe (`*IT` / `*IntegrationTest`)
- **gate**: `mvn -q validate`, then `mvn -pl outbox-spring-boot-starter -am verify`, `mvn -pl audit-spring-boot-starter -am verify`, `mvn -pl crud-service-example -am verify`, `mvn -pl export-spring-boot-starter -am verify`, `mvn -pl file-ingest-spring-boot-starter -am verify`, `mvn -pl notification-service -am verify`, `mvn -pl user-settings-spring-boot-starter -am verify`

### `reconciliation-spring-boot-starter`

- role **starter**, packaging `jar`, parent `common`, imports `ludwig-bom`: yes
- package root `ru.ludwigandreas.reconciliation`
- README [`reconciliation-spring-boot-starter/README.md`](reconciliation-spring-boot-starter/README.md) · [`reconciliation-spring-boot-starter/README.ru.md`](reconciliation-spring-boot-starter/README.ru.md)
- surfaces: actuator
- auto-configuration: `reconciliation-spring-boot-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Liquibase: `reconciliation-spring-boot-starter/src/main/resources/db/changelog/reconciliation/reconciliation-changelog.xml`
- test dirs: integration (`reconciliation-spring-boot-starter/src/test/java/ru/ludwigandreas/reconciliation/integration`), unit (`reconciliation-spring-boot-starter/src/test/java/ru/ludwigandreas/reconciliation/unit`)
- test classes: 7 surefire, 3 failsafe (`*IT` / `*IntegrationTest`)
- **gate**: `mvn -q validate`, then `mvn -pl reconciliation-spring-boot-starter -am verify`

### `rest-client-spring-boot-starter`

- role **starter**, packaging `jar`, parent `common`, imports `ludwig-bom`: yes
- package root `ru.ludwigandreas.restclient`
- README [`rest-client-spring-boot-starter/README.md`](rest-client-spring-boot-starter/README.md) · [`rest-client-spring-boot-starter/README.ru.md`](rest-client-spring-boot-starter/README.ru.md)
- surfaces: rest
- auto-configuration: `rest-client-spring-boot-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- test dirs: integration (`rest-client-spring-boot-starter/src/test/java/ru/ludwigandreas/restclient/integration`), unit (`rest-client-spring-boot-starter/src/test/java/ru/ludwigandreas/restclient/unit`)
- test classes: 11 surefire, 8 failsafe (`*IT` / `*IntegrationTest`)
- **gate**: `mvn -q validate`, then `mvn -pl rest-client-spring-boot-starter -am verify`, `mvn -pl crud-service-example -am verify`, `mvn -pl export-spring-boot-starter -am verify`, `mvn -pl reconciliation-spring-boot-starter -am verify`

### `security-spring-boot-starter`

- role **starter**, packaging `jar`, parent `common`, imports `ludwig-bom`: yes
- package root `ru.ludwigandreas.security`
- README [`security-spring-boot-starter/README.md`](security-spring-boot-starter/README.md) · [`security-spring-boot-starter/README.ru.md`](security-spring-boot-starter/README.ru.md)
- surfaces: none
- auto-configuration: `security-spring-boot-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- i18n: `security-spring-boot-starter/src/main/resources/i18n/ludwig-security-messages.properties`, `security-spring-boot-starter/src/main/resources/i18n/ludwig-security-messages_ru.properties`
- test dirs: unit (`security-spring-boot-starter/src/test/java/ru/ludwigandreas/security/unit`)
- test classes: 15 surefire, 0 failsafe (`*IT` / `*IntegrationTest`)
- **gate**: `mvn -q validate`, then `mvn -pl security-spring-boot-starter -am verify`, `mvn -pl crud-service-example -am verify`, `mvn -pl export-spring-boot-starter -am verify`, `mvn -pl identity-projection-spring-boot-starter -am verify`, `mvn -pl notification-service -am verify`, `mvn -pl test-support-security -am verify`, `mvn -pl user-settings-spring-boot-starter -am verify`

### `test-support`

- role **test-support**, packaging `jar`, parent `common`, imports `ludwig-bom`: yes
- package root `ru.ludwigandreas.testsupport`
- README [`test-support/README.md`](test-support/README.md) · [`test-support/README.ru.md`](test-support/README.ru.md)
- surfaces: none
- test dirs: integration (`test-support/src/test/java/ru/ludwigandreas/testsupport/integration`), unit (`test-support/src/test/java/ru/ludwigandreas/testsupport/unit`)
- test classes: 1 surefire, 1 failsafe (`*IT` / `*IntegrationTest`)
- **gate**: `mvn -q validate`, then `mvn -pl test-support -am verify`, `mvn -pl audit-spring-boot-starter -am verify`, `mvn -pl cache-spring-boot-starter -am verify`, `mvn -pl db-core -am verify`, `mvn -pl export-spring-boot-starter -am verify`, `mvn -pl file-ingest-spring-boot-starter -am verify`, `mvn -pl idempotency-spring-boot-starter -am verify`, `mvn -pl job-core -am verify`, `mvn -pl messaging-spring-boot-starter -am verify`, `mvn -pl object-storage-spring-boot-starter -am verify`, `mvn -pl odata-filter-spring-boot-starter -am verify`, `mvn -pl outbox-spring-boot-starter -am verify`, `mvn -pl reconciliation-spring-boot-starter -am verify`, `mvn -pl test-support-security -am verify`, `mvn -pl user-settings-spring-boot-starter -am verify`

### `test-support-security`

- role **test-support**, packaging `jar`, parent `common`, imports `ludwig-bom`: yes
- package root `ru.ludwigandreas.testsupport.security`
- README [`test-support-security/README.md`](test-support-security/README.md) · [`test-support-security/README.ru.md`](test-support-security/README.ru.md)
- surfaces: none
- test classes: 0 surefire, 0 failsafe (`*IT` / `*IntegrationTest`)
- **gate**: `mvn -q validate`, then `mvn -pl test-support-security -am verify`, `mvn -pl crud-service-example -am verify`, `mvn -pl notification-service -am verify`

### `user-settings-spring-boot-starter`

- role **starter**, packaging `jar`, parent `common`, imports `ludwig-bom`: yes
- package root `ru.ludwigandreas.usersettings`
- README [`user-settings-spring-boot-starter/README.md`](user-settings-spring-boot-starter/README.md) · [`user-settings-spring-boot-starter/README.ru.md`](user-settings-spring-boot-starter/README.ru.md)
- surfaces: rest
- auto-configuration: `user-settings-spring-boot-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Liquibase: `user-settings-spring-boot-starter/src/main/resources/db/changelog/user-settings/user-settings-changelog.xml`
- i18n: `user-settings-spring-boot-starter/src/main/resources/i18n/ludwig-user-settings-messages.properties`, `user-settings-spring-boot-starter/src/main/resources/i18n/ludwig-user-settings-messages_ru.properties`
- test dirs: architecture (`user-settings-spring-boot-starter/src/test/java/ru/ludwigandreas/usersettings/architecture`), integration (`user-settings-spring-boot-starter/src/test/java/ru/ludwigandreas/usersettings/integration`), unit (`user-settings-spring-boot-starter/src/test/java/ru/ludwigandreas/usersettings/unit`)
- test classes: 12 surefire, 2 failsafe (`*IT` / `*IntegrationTest`)
- **gate**: `mvn -q validate`, then `mvn -pl user-settings-spring-boot-starter -am verify`, `mvn -pl notification-service -am verify`

### `web-core-spring-boot-starter`

- role **starter**, packaging `jar`, parent `common`, imports `ludwig-bom`: yes
- package root `ru.ludwigandreas.webcore`
- README [`web-core-spring-boot-starter/README.md`](web-core-spring-boot-starter/README.md) · [`web-core-spring-boot-starter/README.ru.md`](web-core-spring-boot-starter/README.ru.md)
- surfaces: rest
- auto-configuration: `web-core-spring-boot-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- i18n: `web-core-spring-boot-starter/src/main/resources/i18n/ludwig-web-messages.properties`, `web-core-spring-boot-starter/src/main/resources/i18n/ludwig-web-messages_ru.properties`
- test dirs: integration (`web-core-spring-boot-starter/src/test/java/ru/ludwigandreas/webcore/integration`), unit (`web-core-spring-boot-starter/src/test/java/ru/ludwigandreas/webcore/unit`)
- test classes: 11 surefire, 2 failsafe (`*IT` / `*IntegrationTest`)
- **gate**: `mvn -q validate`, then `mvn -pl web-core-spring-boot-starter -am verify`, `mvn -pl audit-spring-boot-starter -am verify`, `mvn -pl crud-service-example -am verify`, `mvn -pl db-core -am verify`, `mvn -pl export-spring-boot-starter -am verify`, `mvn -pl file-ingest-spring-boot-starter -am verify`, `mvn -pl idempotency-spring-boot-starter -am verify`, `mvn -pl messaging-spring-boot-starter -am verify`, `mvn -pl notification-service -am verify`, `mvn -pl object-storage-spring-boot-starter -am verify`, `mvn -pl observability-spring-boot-starter -am verify`, `mvn -pl odata-filter-spring-boot-starter -am verify`, `mvn -pl rest-client-spring-boot-starter -am verify`, `mvn -pl security-spring-boot-starter -am verify`, `mvn -pl user-settings-spring-boot-starter -am verify`

