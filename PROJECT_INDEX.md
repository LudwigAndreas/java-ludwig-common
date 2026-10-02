# PROJECT_INDEX

> **Generated file — do not edit.** Regenerate with `scripts/manifest.sh build`. Hand edits are lost and, worse, believed in the meantime.

| | |
|---|---|
| revision | `1.1.0-SNAPSHOT` |
| modules | 31 |
| commit | `66808f4` (`feature/preference`) |
| generated | 2026-10-02T17:52:04.838083Z |
| **freshness** | POM-set SHA `6327960242b17b53` · newest POM `build/jacoco-aggregate/pom.xml` @ 2026-10-02T17:52:04.458819Z |

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
| **audit-core** | `common` | `ru.ludwigandreas.audit` | none | — | `audit-spring-boot-starter`, `export-spring-boot-starter`, `file-action-spring-boot-starter`, `file-ingest-spring-boot-starter`, `hot-reload-spring-boot-starter`, `idempotency-spring-boot-starter`, `messaging-spring-boot-starter`, `observability-spring-boot-starter`, `outbox-spring-boot-starter`, `reconciliation-spring-boot-starter`, `rest-client-spring-boot-starter`, `security-spring-boot-starter` |
| **db-core** | `common` | `ru.ludwigandreas.db.core` | none | `test-support` (test), `web-core-spring-boot-starter` | `audit-spring-boot-starter`, `crud-service-example`, `export-spring-boot-starter`, `file-action-spring-boot-starter`, `file-ingest-spring-boot-starter`, `idempotency-spring-boot-starter`, `identity-projection-spring-boot-starter`, `notification-service`, `outbox-spring-boot-starter`, `reconciliation-spring-boot-starter`, `user-settings-spring-boot-starter` |
| **job-core** | `common` | `ru.ludwigandreas.job.core` | none | `test-support` (test) | `audit-spring-boot-starter`, `export-spring-boot-starter`, `file-action-spring-boot-starter`, `file-ingest-spring-boot-starter`, `idempotency-spring-boot-starter`, `notification-service`, `outbox-spring-boot-starter`, `reconciliation-spring-boot-starter` |
| **jira-client** | `common` | `ru.ludwigandreas.jira` | none | — | — |

### starter (18)

| module | parent POM | package root | surfaces | in-repo deps | dependents |
|---|---|---|---|---|---|
| **odata-filter-spring-boot-starter** | `common` | `ru.ludwigandreas.odatafilter` | rest | `test-support` (test), `web-core-spring-boot-starter` | `crud-service-example`, `export-spring-boot-starter`, `notification-service`, `user-settings-spring-boot-starter` |
| **audit-spring-boot-starter** | `common` | `ru.ludwigandreas.audit.store` | rest | `audit-core`, `db-core`, `job-core`, `outbox-spring-boot-starter`, `test-support` (test), `web-core-spring-boot-starter` | `user-settings-spring-boot-starter` |
| **idempotency-spring-boot-starter** | `common` | `ru.ludwigandreas.idempotency` | rest | `audit-core`, `db-core`, `job-core`, `test-support` (test), `web-core-spring-boot-starter` | `file-action-spring-boot-starter`, `messaging-spring-boot-starter`, `notification-service` |
| **cache-spring-boot-starter** | `common` | `ru.ludwigandreas.cache` | none | `test-support` (test) | `security-spring-boot-starter`, `user-settings-spring-boot-starter` |
| **messaging-spring-boot-starter** | `common` | `ru.ludwigandreas.messaging` | rest | `architecture-rules` (test), `audit-core`, `idempotency-spring-boot-starter`, `test-support` (test), `web-core-spring-boot-starter` | `identity-projection-spring-boot-starter`, `notification-service`, `outbox-spring-boot-starter`, `user-settings-spring-boot-starter` |
| **export-spring-boot-starter** | `common` | `ru.ludwigandreas.export` | rest | `audit-core`, `db-core`, `hot-reload-spring-boot-starter`, `job-core`, `object-storage-spring-boot-starter`, `observability-spring-boot-starter`, `odata-filter-spring-boot-starter`, `outbox-spring-boot-starter`, `rest-client-spring-boot-starter`, `security-spring-boot-starter`, `test-support` (test), `web-core-spring-boot-starter` | `crud-service-example` |
| **object-storage-spring-boot-starter** | `common` | `ru.ludwigandreas.storage` | rest | `architecture-rules` (test), `test-support` (test), `web-core-spring-boot-starter` | `export-spring-boot-starter`, `file-action-spring-boot-starter`, `file-ingest-spring-boot-starter` |
| **file-ingest-spring-boot-starter** | `common` | `ru.ludwigandreas.ingest` | actuator | `architecture-rules` (test), `audit-core`, `db-core`, `job-core`, `object-storage-spring-boot-starter`, `outbox-spring-boot-starter`, `test-support` (test), `web-core-spring-boot-starter` | — |
| **file-action-spring-boot-starter** | `common` | `ru.ludwigandreas.fileaction` | rest | `architecture-rules` (test), `audit-core`, `db-core`, `idempotency-spring-boot-starter`, `job-core`, `object-storage-spring-boot-starter`, `observability-spring-boot-starter`, `outbox-spring-boot-starter`, `security-spring-boot-starter`, `test-support` (test), `test-support-security` (test), `web-core-spring-boot-starter` | `crud-service-example` |
| **outbox-spring-boot-starter** | `common` | `ru.ludwigandreas.outbox` | none | `audit-core`, `db-core`, `job-core`, `messaging-spring-boot-starter`, `test-support` (test) | `audit-spring-boot-starter`, `crud-service-example`, `export-spring-boot-starter`, `file-action-spring-boot-starter`, `file-ingest-spring-boot-starter`, `notification-service`, `user-settings-spring-boot-starter` |
| **reconciliation-spring-boot-starter** | `common` | `ru.ludwigandreas.reconciliation` | actuator | `audit-core`, `db-core`, `job-core`, `observability-spring-boot-starter`, `rest-client-spring-boot-starter`, `test-support` (test) | — |
| **security-spring-boot-starter** | `common` | `ru.ludwigandreas.security` | none | `audit-core`, `cache-spring-boot-starter`, `web-core-spring-boot-starter` | `crud-service-example`, `export-spring-boot-starter`, `file-action-spring-boot-starter`, `identity-projection-spring-boot-starter`, `notification-service`, `test-support-security`, `user-settings-spring-boot-starter` |
| **identity-projection-spring-boot-starter** | `common` | `ru.ludwigandreas.identity` | none | `db-core`, `messaging-spring-boot-starter`, `security-spring-boot-starter` | `crud-service-example`, `notification-service` |
| **hot-reload-spring-boot-starter** | `common` | `ru.ludwigandreas.hotreload` | none | `audit-core` | `export-spring-boot-starter`, `notification-service`, `observability-spring-boot-starter`, `user-settings-spring-boot-starter` |
| **web-core-spring-boot-starter** | `common` | `ru.ludwigandreas.webcore` | rest | — | `audit-spring-boot-starter`, `crud-service-example`, `db-core`, `export-spring-boot-starter`, `file-action-spring-boot-starter`, `file-ingest-spring-boot-starter`, `idempotency-spring-boot-starter`, `messaging-spring-boot-starter`, `notification-service`, `object-storage-spring-boot-starter`, `observability-spring-boot-starter`, `odata-filter-spring-boot-starter`, `rest-client-spring-boot-starter`, `security-spring-boot-starter`, `user-settings-spring-boot-starter` |
| **observability-spring-boot-starter** | `common` | `ru.ludwigandreas.observability` | none | `audit-core`, `hot-reload-spring-boot-starter`, `web-core-spring-boot-starter` | `export-spring-boot-starter`, `file-action-spring-boot-starter`, `notification-service`, `reconciliation-spring-boot-starter`, `rest-client-spring-boot-starter`, `user-settings-spring-boot-starter` |
| **rest-client-spring-boot-starter** | `common` | `ru.ludwigandreas.restclient` | rest | `audit-core`, `observability-spring-boot-starter`, `web-core-spring-boot-starter` | `crud-service-example`, `export-spring-boot-starter`, `reconciliation-spring-boot-starter` |
| **user-settings-spring-boot-starter** | `common` | `ru.ludwigandreas.usersettings` | rest | `architecture-rules` (test), `audit-spring-boot-starter`, `cache-spring-boot-starter`, `db-core`, `hot-reload-spring-boot-starter`, `messaging-spring-boot-starter`, `observability-spring-boot-starter`, `odata-filter-spring-boot-starter`, `outbox-spring-boot-starter`, `security-spring-boot-starter`, `test-support` (test), `web-core-spring-boot-starter` | `notification-service` |

### service (2)

| module | parent POM | package root | surfaces | in-repo deps | dependents |
|---|---|---|---|---|---|
| **crud-service-example** | `ludwig-service-parent` | `ru.ludwigandreas.example.catalog` | rest | `architecture-rules` (test), `db-core`, `export-spring-boot-starter`, `file-action-spring-boot-starter`, `identity-projection-spring-boot-starter`, `odata-filter-spring-boot-starter`, `outbox-spring-boot-starter`, `rest-client-spring-boot-starter`, `security-spring-boot-starter`, `test-support-security` (test), `web-core-spring-boot-starter` | — |
| **notification-service** | `ludwig-service-parent` | `ru.ludwigandreas.notification` | actuator, rest | `architecture-rules` (test), `db-core`, `hot-reload-spring-boot-starter`, `idempotency-spring-boot-starter`, `identity-projection-spring-boot-starter`, `job-core`, `messaging-spring-boot-starter`, `observability-spring-boot-starter`, `odata-filter-spring-boot-starter`, `outbox-spring-boot-starter`, `security-spring-boot-starter`, `test-support-security` (test), `user-settings-spring-boot-starter` (provided), `web-core-spring-boot-starter` | — |

### test-support (2)

| module | parent POM | package root | surfaces | in-repo deps | dependents |
|---|---|---|---|---|---|
| **test-support** | `common` | `ru.ludwigandreas.testsupport` | none | — | `audit-spring-boot-starter`, `cache-spring-boot-starter`, `db-core`, `export-spring-boot-starter`, `file-action-spring-boot-starter`, `file-ingest-spring-boot-starter`, `idempotency-spring-boot-starter`, `job-core`, `messaging-spring-boot-starter`, `object-storage-spring-boot-starter`, `odata-filter-spring-boot-starter`, `outbox-spring-boot-starter`, `reconciliation-spring-boot-starter`, `test-support-security`, `user-settings-spring-boot-starter` |
| **test-support-security** | `common` | `ru.ludwigandreas.testsupport.security` | none | `security-spring-boot-starter`, `test-support` | `crud-service-example`, `file-action-spring-boot-starter`, `notification-service` |

### rules (2)

| module | parent POM | package root | surfaces | in-repo deps | dependents |
|---|---|---|---|---|---|
| **architecture-rules** | `common` | `ru.ludwigandreas.archrules` | rest | — | `crud-service-example`, `file-action-spring-boot-starter`, `file-ingest-spring-boot-starter`, `messaging-spring-boot-starter`, `notification-service`, `object-storage-spring-boot-starter`, `user-settings-spring-boot-starter` |
| **checkstyle-rules** | `common` | `ru.ludwigandreas.checkstyle.unit` |  | — | — |

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
file-action-spring-boot-starter            -> architecture-rules  [test]
file-action-spring-boot-starter            -> audit-core
file-action-spring-boot-starter            -> db-core
file-action-spring-boot-starter            -> idempotency-spring-boot-starter
file-action-spring-boot-starter            -> job-core
file-action-spring-boot-starter            -> object-storage-spring-boot-starter
file-action-spring-boot-starter            -> observability-spring-boot-starter
file-action-spring-boot-starter            -> outbox-spring-boot-starter
file-action-spring-boot-starter            -> security-spring-boot-starter
file-action-spring-boot-starter            -> test-support  [test]
file-action-spring-boot-starter            -> test-support-security  [test]
file-action-spring-boot-starter            -> web-core-spring-boot-starter
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
crud-service-example                       -> file-action-spring-boot-starter
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
jacoco-aggregate                           -> architecture-rules
jacoco-aggregate                           -> audit-core
jacoco-aggregate                           -> audit-spring-boot-starter
jacoco-aggregate                           -> cache-spring-boot-starter
jacoco-aggregate                           -> checkstyle-rules
jacoco-aggregate                           -> crud-service-example
jacoco-aggregate                           -> db-core
jacoco-aggregate                           -> export-spring-boot-starter
jacoco-aggregate                           -> file-action-spring-boot-starter
jacoco-aggregate                           -> file-ingest-spring-boot-starter
jacoco-aggregate                           -> hot-reload-spring-boot-starter
jacoco-aggregate                           -> idempotency-spring-boot-starter
jacoco-aggregate                           -> identity-projection-spring-boot-starter
jacoco-aggregate                           -> jira-client
jacoco-aggregate                           -> job-core
jacoco-aggregate                           -> messaging-spring-boot-starter
jacoco-aggregate                           -> notification-service
jacoco-aggregate                           -> object-storage-spring-boot-starter
jacoco-aggregate                           -> observability-spring-boot-starter
jacoco-aggregate                           -> odata-filter-spring-boot-starter
jacoco-aggregate                           -> outbox-spring-boot-starter
jacoco-aggregate                           -> reconciliation-spring-boot-starter
jacoco-aggregate                           -> rest-client-spring-boot-starter
jacoco-aggregate                           -> security-spring-boot-starter
jacoco-aggregate                           -> test-support
jacoco-aggregate                           -> test-support-security
jacoco-aggregate                           -> user-settings-spring-boot-starter
jacoco-aggregate                           -> web-core-spring-boot-starter
```

**No in-repo dependencies at all** (8 modules) — several of them deliberately, because a module with no sibling edge can be depended on from anywhere without closing a cycle: `architecture-rules`, `audit-core`, `checkstyle-rules`, `jira-client`, `ludwig-bom`, `ludwig-service-parent`, `test-support`, `web-core-spring-boot-starter`.

## Per-module detail

### `architecture-rules`

- role **rules**, packaging `jar`, parent `common`, imports `ludwig-bom`: yes
- directory `build/architecture-rules/`
- package root `ru.ludwigandreas.archrules`
- README [`build/architecture-rules/README.md`](build/architecture-rules/README.md) · [`build/architecture-rules/README.ru.md`](build/architecture-rules/README.ru.md)
- surfaces: rest
- test classes: 9 surefire, 1 failsafe (`*IT` / `*IntegrationTest`)
- **gate**: `mvn -q validate`, then `mvn -pl :architecture-rules -am verify`, `mvn -pl :crud-service-example -am verify`, `mvn -pl :file-action-spring-boot-starter -am verify`, `mvn -pl :file-ingest-spring-boot-starter -am verify`, `mvn -pl :messaging-spring-boot-starter -am verify`, `mvn -pl :notification-service -am verify`, `mvn -pl :object-storage-spring-boot-starter -am verify`, `mvn -pl :user-settings-spring-boot-starter -am verify`

### `audit-core`

- role **library**, packaging `jar`, parent `common`, imports `ludwig-bom`: yes
- directory `sources/audit-core/`
- package root `ru.ludwigandreas.audit`
- README [`sources/audit-core/README.md`](sources/audit-core/README.md) · [`sources/audit-core/README.ru.md`](sources/audit-core/README.ru.md)
- surfaces: none
- auto-configuration: `sources/audit-core/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- test dirs: unit (`sources/audit-core/src/test/java/ru/ludwigandreas/audit/unit`)
- test classes: 5 surefire, 0 failsafe (`*IT` / `*IntegrationTest`)
- **gate**: `mvn -q validate`, then `mvn -pl :audit-core -am verify`, `mvn -pl :audit-spring-boot-starter -am verify`, `mvn -pl :export-spring-boot-starter -am verify`, `mvn -pl :file-action-spring-boot-starter -am verify`, `mvn -pl :file-ingest-spring-boot-starter -am verify`, `mvn -pl :hot-reload-spring-boot-starter -am verify`, `mvn -pl :idempotency-spring-boot-starter -am verify`, `mvn -pl :messaging-spring-boot-starter -am verify`, `mvn -pl :observability-spring-boot-starter -am verify`, `mvn -pl :outbox-spring-boot-starter -am verify`, `mvn -pl :reconciliation-spring-boot-starter -am verify`, `mvn -pl :rest-client-spring-boot-starter -am verify`, `mvn -pl :security-spring-boot-starter -am verify`

### `audit-spring-boot-starter`

- role **starter**, packaging `jar`, parent `common`, imports `ludwig-bom`: yes
- directory `sources/audit-spring-boot-starter/`
- package root `ru.ludwigandreas.audit.store`
- README [`sources/audit-spring-boot-starter/README.md`](sources/audit-spring-boot-starter/README.md) · [`sources/audit-spring-boot-starter/README.ru.md`](sources/audit-spring-boot-starter/README.ru.md)
- surfaces: rest
- auto-configuration: `sources/audit-spring-boot-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Liquibase: `sources/audit-spring-boot-starter/src/main/resources/db/changelog/audit/audit-changelog.xml`
- i18n: `sources/audit-spring-boot-starter/src/main/resources/i18n/ludwig-audit-messages.properties`, `sources/audit-spring-boot-starter/src/main/resources/i18n/ludwig-audit-messages_ru.properties`
- test dirs: integration (`sources/audit-spring-boot-starter/src/test/java/ru/ludwigandreas/audit/store/integration`)
- test classes: 0 surefire, 1 failsafe (`*IT` / `*IntegrationTest`)
- **gate**: `mvn -q validate`, then `mvn -pl :audit-spring-boot-starter -am verify`, `mvn -pl :user-settings-spring-boot-starter -am verify`

### `cache-spring-boot-starter`

- role **starter**, packaging `jar`, parent `common`, imports `ludwig-bom`: yes
- directory `sources/cache-spring-boot-starter/`
- package root `ru.ludwigandreas.cache`
- README [`sources/cache-spring-boot-starter/README.md`](sources/cache-spring-boot-starter/README.md) · [`sources/cache-spring-boot-starter/README.ru.md`](sources/cache-spring-boot-starter/README.ru.md)
- surfaces: none
- auto-configuration: `sources/cache-spring-boot-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- test dirs: architecture (`sources/cache-spring-boot-starter/src/test/java/ru/ludwigandreas/cache/architecture`), integration (`sources/cache-spring-boot-starter/src/test/java/ru/ludwigandreas/cache/integration`), unit (`sources/cache-spring-boot-starter/src/test/java/ru/ludwigandreas/cache/unit`)
- test classes: 8 surefire, 1 failsafe (`*IT` / `*IntegrationTest`)
- **gate**: `mvn -q validate`, then `mvn -pl :cache-spring-boot-starter -am verify`, `mvn -pl :security-spring-boot-starter -am verify`, `mvn -pl :user-settings-spring-boot-starter -am verify`

### `checkstyle-rules`

- role **rules**, packaging `jar`, parent `common`, imports `ludwig-bom`: yes
- directory `build/checkstyle-rules/`
- package root `ru.ludwigandreas.checkstyle.unit`
- README [`build/checkstyle-rules/README.md`](build/checkstyle-rules/README.md) · [`build/checkstyle-rules/README.ru.md`](build/checkstyle-rules/README.ru.md)
- surfaces: 
- test dirs: unit (`build/checkstyle-rules/src/test/java/ru/ludwigandreas/checkstyle/unit`)
- test classes: 1 surefire, 0 failsafe (`*IT` / `*IntegrationTest`)
- **gate**: `mvn -q validate`, then `mvn -pl :checkstyle-rules -am verify`

### `crud-service-example`

- role **service**, packaging `jar`, parent `ludwig-service-parent`, imports `ludwig-bom`: no
- directory `services/crud-service-example/`
- package root `ru.ludwigandreas.example.catalog`
- README [`services/crud-service-example/README.md`](services/crud-service-example/README.md) · [`services/crud-service-example/README.ru.md`](services/crud-service-example/README.ru.md)
- surfaces: rest
- Liquibase: `services/crud-service-example/src/main/resources/db/changelog/changes/0001-catalog-schema.xml`, `services/crud-service-example/src/main/resources/db/changelog/changes/0002-catalog-reference-data.xml`, `services/crud-service-example/src/main/resources/db/changelog/changes/0003-catalog-security.xml`, `services/crud-service-example/src/main/resources/db/changelog/changes/0004-catalog-watchers.xml`, `services/crud-service-example/src/main/resources/db/changelog/db.changelog-master.xml`
- i18n: `services/crud-service-example/src/main/resources/i18n/messages.properties`, `services/crud-service-example/src/main/resources/i18n/messages_ru.properties`
- test dirs: architecture (`services/crud-service-example/src/test/java/ru/ludwigandreas/example/catalog/architecture`), integration (`services/crud-service-example/src/test/java/ru/ludwigandreas/example/catalog/integration`), unit (`services/crud-service-example/src/test/java/ru/ludwigandreas/example/catalog/unit`)
- test classes: 4 surefire, 3 failsafe (`*IT` / `*IntegrationTest`)
- **gate**: `mvn -q validate`, then `mvn -pl :crud-service-example -am verify`

### `db-core`

- role **library**, packaging `jar`, parent `common`, imports `ludwig-bom`: yes
- directory `sources/db-core/`
- package root `ru.ludwigandreas.db.core`
- README [`sources/db-core/README.md`](sources/db-core/README.md) · [`sources/db-core/README.ru.md`](sources/db-core/README.ru.md)
- surfaces: none
- auto-configuration: `sources/db-core/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- i18n: `sources/db-core/src/main/resources/i18n/ludwig-db-messages.properties`, `sources/db-core/src/main/resources/i18n/ludwig-db-messages_ru.properties`
- test dirs: integration (`sources/db-core/src/test/java/ru/ludwigandreas/db/core/integration`)
- test classes: 7 surefire, 2 failsafe (`*IT` / `*IntegrationTest`)
- **gate**: `mvn -q validate`, then `mvn -pl :db-core -am verify`, `mvn -pl :audit-spring-boot-starter -am verify`, `mvn -pl :crud-service-example -am verify`, `mvn -pl :export-spring-boot-starter -am verify`, `mvn -pl :file-action-spring-boot-starter -am verify`, `mvn -pl :file-ingest-spring-boot-starter -am verify`, `mvn -pl :idempotency-spring-boot-starter -am verify`, `mvn -pl :identity-projection-spring-boot-starter -am verify`, `mvn -pl :notification-service -am verify`, `mvn -pl :outbox-spring-boot-starter -am verify`, `mvn -pl :reconciliation-spring-boot-starter -am verify`, `mvn -pl :user-settings-spring-boot-starter -am verify`

### `export-spring-boot-starter`

- role **starter**, packaging `jar`, parent `common`, imports `ludwig-bom`: yes
- directory `sources/export-spring-boot-starter/`
- package root `ru.ludwigandreas.export`
- README [`sources/export-spring-boot-starter/README.md`](sources/export-spring-boot-starter/README.md) · [`sources/export-spring-boot-starter/README.ru.md`](sources/export-spring-boot-starter/README.ru.md)
- surfaces: rest
- auto-configuration: `sources/export-spring-boot-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Liquibase: `sources/export-spring-boot-starter/src/main/resources/db/changelog/export/export-changelog.xml`
- i18n: `sources/export-spring-boot-starter/src/main/resources/i18n/ludwig-export-messages.properties`, `sources/export-spring-boot-starter/src/main/resources/i18n/ludwig-export-messages_ru.properties`
- test dirs: architecture (`sources/export-spring-boot-starter/src/test/java/ru/ludwigandreas/export/architecture`), integration (`sources/export-spring-boot-starter/src/test/java/ru/ludwigandreas/export/integration`), unit (`sources/export-spring-boot-starter/src/test/java/ru/ludwigandreas/export/unit`)
- test classes: 16 surefire, 2 failsafe (`*IT` / `*IntegrationTest`)
- **gate**: `mvn -q validate`, then `mvn -pl :export-spring-boot-starter -am verify`, `mvn -pl :crud-service-example -am verify`

### `file-action-spring-boot-starter`

- role **starter**, packaging `jar`, parent `common`, imports `ludwig-bom`: yes
- directory `sources/file-action-spring-boot-starter/`
- package root `ru.ludwigandreas.fileaction`
- README [`sources/file-action-spring-boot-starter/README.md`](sources/file-action-spring-boot-starter/README.md) · [`sources/file-action-spring-boot-starter/README.ru.md`](sources/file-action-spring-boot-starter/README.ru.md)
- surfaces: rest
- auto-configuration: `sources/file-action-spring-boot-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Liquibase: `sources/file-action-spring-boot-starter/src/main/resources/db/changelog/file-action/file-action-changelog.xml`
- i18n: `sources/file-action-spring-boot-starter/src/main/resources/i18n/ludwig-file-action-messages.properties`, `sources/file-action-spring-boot-starter/src/main/resources/i18n/ludwig-file-action-messages_ru.properties`
- test dirs: architecture (`sources/file-action-spring-boot-starter/src/test/java/ru/ludwigandreas/fileaction/architecture`), integration (`sources/file-action-spring-boot-starter/src/test/java/ru/ludwigandreas/fileaction/integration`), unit (`sources/file-action-spring-boot-starter/src/test/java/ru/ludwigandreas/fileaction/unit`)
- test classes: 26 surefire, 6 failsafe (`*IT` / `*IntegrationTest`)
- **gate**: `mvn -q validate`, then `mvn -pl :file-action-spring-boot-starter -am verify`, `mvn -pl :crud-service-example -am verify`

### `file-ingest-spring-boot-starter`

- role **starter**, packaging `jar`, parent `common`, imports `ludwig-bom`: yes
- directory `sources/file-ingest-spring-boot-starter/`
- package root `ru.ludwigandreas.ingest`
- README [`sources/file-ingest-spring-boot-starter/README.md`](sources/file-ingest-spring-boot-starter/README.md) · [`sources/file-ingest-spring-boot-starter/README.ru.md`](sources/file-ingest-spring-boot-starter/README.ru.md)
- surfaces: actuator
- auto-configuration: `sources/file-ingest-spring-boot-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Liquibase: `sources/file-ingest-spring-boot-starter/src/main/resources/db/changelog/file-ingest/file-ingest-changelog.xml`
- i18n: `sources/file-ingest-spring-boot-starter/src/main/resources/i18n/ludwig-ingest-messages.properties`, `sources/file-ingest-spring-boot-starter/src/main/resources/i18n/ludwig-ingest-messages_ru.properties`
- test dirs: architecture (`sources/file-ingest-spring-boot-starter/src/test/java/ru/ludwigandreas/ingest/architecture`), integration (`sources/file-ingest-spring-boot-starter/src/test/java/ru/ludwigandreas/ingest/integration`), unit (`sources/file-ingest-spring-boot-starter/src/test/java/ru/ludwigandreas/ingest/unit`)
- test classes: 8 surefire, 9 failsafe (`*IT` / `*IntegrationTest`)
- **gate**: `mvn -q validate`, then `mvn -pl :file-ingest-spring-boot-starter -am verify`

### `hot-reload-spring-boot-starter`

- role **starter**, packaging `jar`, parent `common`, imports `ludwig-bom`: yes
- directory `sources/hot-reload-spring-boot-starter/`
- package root `ru.ludwigandreas.hotreload`
- README [`sources/hot-reload-spring-boot-starter/README.md`](sources/hot-reload-spring-boot-starter/README.md) · [`sources/hot-reload-spring-boot-starter/README.ru.md`](sources/hot-reload-spring-boot-starter/README.ru.md)
- surfaces: none
- auto-configuration: `sources/hot-reload-spring-boot-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- test classes: 13 surefire, 0 failsafe (`*IT` / `*IntegrationTest`)
- **gate**: `mvn -q validate`, then `mvn -pl :hot-reload-spring-boot-starter -am verify`, `mvn -pl :export-spring-boot-starter -am verify`, `mvn -pl :notification-service -am verify`, `mvn -pl :observability-spring-boot-starter -am verify`, `mvn -pl :user-settings-spring-boot-starter -am verify`

### `idempotency-spring-boot-starter`

- role **starter**, packaging `jar`, parent `common`, imports `ludwig-bom`: yes
- directory `sources/idempotency-spring-boot-starter/`
- package root `ru.ludwigandreas.idempotency`
- README [`sources/idempotency-spring-boot-starter/README.md`](sources/idempotency-spring-boot-starter/README.md) · [`sources/idempotency-spring-boot-starter/README.ru.md`](sources/idempotency-spring-boot-starter/README.ru.md)
- surfaces: rest
- auto-configuration: `sources/idempotency-spring-boot-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Liquibase: `sources/idempotency-spring-boot-starter/src/main/resources/db/changelog/idempotency/idempotency-changelog.xml`
- i18n: `sources/idempotency-spring-boot-starter/src/main/resources/i18n/ludwig-idempotency-messages.properties`, `sources/idempotency-spring-boot-starter/src/main/resources/i18n/ludwig-idempotency-messages_ru.properties`
- test dirs: architecture (`sources/idempotency-spring-boot-starter/src/test/java/ru/ludwigandreas/idempotency/architecture`), integration (`sources/idempotency-spring-boot-starter/src/test/java/ru/ludwigandreas/idempotency/integration`), unit (`sources/idempotency-spring-boot-starter/src/test/java/ru/ludwigandreas/idempotency/unit`)
- test classes: 4 surefire, 5 failsafe (`*IT` / `*IntegrationTest`)
- **gate**: `mvn -q validate`, then `mvn -pl :idempotency-spring-boot-starter -am verify`, `mvn -pl :file-action-spring-boot-starter -am verify`, `mvn -pl :messaging-spring-boot-starter -am verify`, `mvn -pl :notification-service -am verify`

### `identity-projection-spring-boot-starter`

- role **starter**, packaging `jar`, parent `common`, imports `ludwig-bom`: yes
- directory `sources/identity-projection-spring-boot-starter/`
- package root `ru.ludwigandreas.identity`
- README [`sources/identity-projection-spring-boot-starter/README.md`](sources/identity-projection-spring-boot-starter/README.md) · [`sources/identity-projection-spring-boot-starter/README.ru.md`](sources/identity-projection-spring-boot-starter/README.ru.md)
- surfaces: none
- auto-configuration: `sources/identity-projection-spring-boot-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Liquibase: `sources/identity-projection-spring-boot-starter/src/main/resources/db/changelog/identity/identity-changelog.xml`
- test dirs: unit (`sources/identity-projection-spring-boot-starter/src/test/java/ru/ludwigandreas/identity/unit`)
- test classes: 1 surefire, 0 failsafe (`*IT` / `*IntegrationTest`)
- **gate**: `mvn -q validate`, then `mvn -pl :identity-projection-spring-boot-starter -am verify`, `mvn -pl :crud-service-example -am verify`, `mvn -pl :notification-service -am verify`

### `jacoco-aggregate`

- role **aggregate**, packaging `pom`, parent `common`, imports `ludwig-bom`: yes
- directory `build/jacoco-aggregate/`
- package root `—`
- README [`build/jacoco-aggregate/README.md`](build/jacoco-aggregate/README.md) · [`build/jacoco-aggregate/README.ru.md`](build/jacoco-aggregate/README.ru.md)
- surfaces: 
- test classes: 0 surefire, 0 failsafe (`*IT` / `*IntegrationTest`)
- **gate**: `mvn -q validate`, then `mvn -pl :jacoco-aggregate -am verify`

### `jira-client`

- role **library**, packaging `jar`, parent `common`, imports `ludwig-bom`: yes
- directory `sources/jira-client/`
- package root `ru.ludwigandreas.jira`
- README [`sources/jira-client/README.md`](sources/jira-client/README.md) · [`sources/jira-client/README.ru.md`](sources/jira-client/README.ru.md)
- surfaces: none
- test dirs: unit (`sources/jira-client/src/test/java/ru/ludwigandreas/jira/unit`)
- test classes: 10 surefire, 0 failsafe (`*IT` / `*IntegrationTest`)
- **gate**: `mvn -q validate`, then `mvn -pl :jira-client -am verify`

### `job-core`

- role **library**, packaging `jar`, parent `common`, imports `ludwig-bom`: yes
- directory `sources/job-core/`
- package root `ru.ludwigandreas.job.core`
- README [`sources/job-core/README.md`](sources/job-core/README.md) · [`sources/job-core/README.ru.md`](sources/job-core/README.ru.md)
- surfaces: none
- auto-configuration: `sources/job-core/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Liquibase: `sources/job-core/src/main/resources/db/changelog/job-core/job-core-changelog.xml`
- test dirs: integration (`sources/job-core/src/test/java/ru/ludwigandreas/job/core/integration`), unit (`sources/job-core/src/test/java/ru/ludwigandreas/job/core/unit`)
- test classes: 3 surefire, 1 failsafe (`*IT` / `*IntegrationTest`)
- **gate**: `mvn -q validate`, then `mvn -pl :job-core -am verify`, `mvn -pl :audit-spring-boot-starter -am verify`, `mvn -pl :export-spring-boot-starter -am verify`, `mvn -pl :file-action-spring-boot-starter -am verify`, `mvn -pl :file-ingest-spring-boot-starter -am verify`, `mvn -pl :idempotency-spring-boot-starter -am verify`, `mvn -pl :notification-service -am verify`, `mvn -pl :outbox-spring-boot-starter -am verify`, `mvn -pl :reconciliation-spring-boot-starter -am verify`

### `ludwig-bom`

- role **bom**, packaging `pom`, parent `common`, imports `ludwig-bom`: no
- directory `build/ludwig-bom/`
- package root `—`
- README [`build/ludwig-bom/README.md`](build/ludwig-bom/README.md) · [`build/ludwig-bom/README.ru.md`](build/ludwig-bom/README.ru.md)
- surfaces: 
- test classes: 0 surefire, 0 failsafe (`*IT` / `*IntegrationTest`)
- **gate**: `mvn clean install  (every module imports ludwig-bom)`

### `ludwig-service-parent`

- role **parent**, packaging `pom`, parent `spring-boot-starter-parent`, imports `ludwig-bom`: yes
- directory `build/ludwig-service-parent/`
- package root `—`
- README [`build/ludwig-service-parent/README.md`](build/ludwig-service-parent/README.md) · [`build/ludwig-service-parent/README.ru.md`](build/ludwig-service-parent/README.ru.md)
- surfaces: 
- test classes: 0 surefire, 0 failsafe (`*IT` / `*IntegrationTest`)
- **gate**: `mvn clean install  (every service inherits it)`

### `messaging-spring-boot-starter`

- role **starter**, packaging `jar`, parent `common`, imports `ludwig-bom`: yes
- directory `sources/messaging-spring-boot-starter/`
- package root `ru.ludwigandreas.messaging`
- README [`sources/messaging-spring-boot-starter/README.md`](sources/messaging-spring-boot-starter/README.md) · [`sources/messaging-spring-boot-starter/README.ru.md`](sources/messaging-spring-boot-starter/README.ru.md)
- surfaces: rest
- auto-configuration: `sources/messaging-spring-boot-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- i18n: `sources/messaging-spring-boot-starter/src/main/resources/i18n/ludwig-messaging-messages.properties`, `sources/messaging-spring-boot-starter/src/main/resources/i18n/ludwig-messaging-messages_ru.properties`
- test dirs: architecture (`sources/messaging-spring-boot-starter/src/test/java/ru/ludwigandreas/messaging/architecture`), integration (`sources/messaging-spring-boot-starter/src/test/java/ru/ludwigandreas/messaging/integration`), unit (`sources/messaging-spring-boot-starter/src/test/java/ru/ludwigandreas/messaging/unit`)
- test classes: 12 surefire, 1 failsafe (`*IT` / `*IntegrationTest`)
- **gate**: `mvn -q validate`, then `mvn -pl :messaging-spring-boot-starter -am verify`, `mvn -pl :identity-projection-spring-boot-starter -am verify`, `mvn -pl :notification-service -am verify`, `mvn -pl :outbox-spring-boot-starter -am verify`, `mvn -pl :user-settings-spring-boot-starter -am verify`

### `notification-service`

- role **service**, packaging `jar`, parent `ludwig-service-parent`, imports `ludwig-bom`: no
- directory `services/notification-service/`
- package root `ru.ludwigandreas.notification`
- README [`services/notification-service/README.md`](services/notification-service/README.md) · [`services/notification-service/README.ru.md`](services/notification-service/README.ru.md)
- surfaces: actuator, rest
- Liquibase: `services/notification-service/src/main/resources/db/changelog/changes/0001-notification-schema.xml`, `services/notification-service/src/main/resources/db/changelog/changes/0002-notification-recipients.xml`, `services/notification-service/src/main/resources/db/changelog/changes/0003-notification-platform-gaps.xml`, `services/notification-service/src/main/resources/db/changelog/changes/0004-preferences-move-out.xml`, `services/notification-service/src/main/resources/db/changelog/changes/0005-fold-lock-into-job-core.xml`, `services/notification-service/src/main/resources/db/changelog/changes/0006-fold-idempotency-into-starter.xml`, `services/notification-service/src/main/resources/db/changelog/db.changelog-master.xml`
- i18n: `services/notification-service/src/main/resources/i18n/notification-messages.properties`, `services/notification-service/src/main/resources/i18n/notification-messages_ru.properties`
- test dirs: architecture (`services/notification-service/src/test/java/ru/ludwigandreas/notification/architecture`), integration (`services/notification-service/src/test/java/ru/ludwigandreas/notification/integration`), unit (`services/notification-service/src/test/java/ru/ludwigandreas/notification/unit`)
- test classes: 16 surefire, 4 failsafe (`*IT` / `*IntegrationTest`)
- **gate**: `mvn -q validate`, then `mvn -pl :notification-service -am verify`

### `object-storage-spring-boot-starter`

- role **starter**, packaging `jar`, parent `common`, imports `ludwig-bom`: yes
- directory `sources/object-storage-spring-boot-starter/`
- package root `ru.ludwigandreas.storage`
- README [`sources/object-storage-spring-boot-starter/README.md`](sources/object-storage-spring-boot-starter/README.md) · [`sources/object-storage-spring-boot-starter/README.ru.md`](sources/object-storage-spring-boot-starter/README.ru.md)
- surfaces: rest
- auto-configuration: `sources/object-storage-spring-boot-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- i18n: `sources/object-storage-spring-boot-starter/src/main/resources/i18n/ludwig-storage-messages.properties`, `sources/object-storage-spring-boot-starter/src/main/resources/i18n/ludwig-storage-messages_ru.properties`
- test dirs: architecture (`sources/object-storage-spring-boot-starter/src/test/java/ru/ludwigandreas/storage/architecture`), integration (`sources/object-storage-spring-boot-starter/src/test/java/ru/ludwigandreas/storage/integration`), unit (`sources/object-storage-spring-boot-starter/src/test/java/ru/ludwigandreas/storage/unit`)
- test classes: 4 surefire, 2 failsafe (`*IT` / `*IntegrationTest`)
- **gate**: `mvn -q validate`, then `mvn -pl :object-storage-spring-boot-starter -am verify`, `mvn -pl :export-spring-boot-starter -am verify`, `mvn -pl :file-action-spring-boot-starter -am verify`, `mvn -pl :file-ingest-spring-boot-starter -am verify`

### `observability-spring-boot-starter`

- role **starter**, packaging `jar`, parent `common`, imports `ludwig-bom`: yes
- directory `sources/observability-spring-boot-starter/`
- package root `ru.ludwigandreas.observability`
- README [`sources/observability-spring-boot-starter/README.md`](sources/observability-spring-boot-starter/README.md) · [`sources/observability-spring-boot-starter/README.ru.md`](sources/observability-spring-boot-starter/README.ru.md)
- surfaces: none
- auto-configuration: `sources/observability-spring-boot-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- test dirs: integration (`sources/observability-spring-boot-starter/src/test/java/ru/ludwigandreas/observability/integration`), unit (`sources/observability-spring-boot-starter/src/test/java/ru/ludwigandreas/observability/unit`)
- test classes: 13 surefire, 0 failsafe (`*IT` / `*IntegrationTest`)
- **gate**: `mvn -q validate`, then `mvn -pl :observability-spring-boot-starter -am verify`, `mvn -pl :export-spring-boot-starter -am verify`, `mvn -pl :file-action-spring-boot-starter -am verify`, `mvn -pl :notification-service -am verify`, `mvn -pl :reconciliation-spring-boot-starter -am verify`, `mvn -pl :rest-client-spring-boot-starter -am verify`, `mvn -pl :user-settings-spring-boot-starter -am verify`

### `odata-filter-spring-boot-starter`

- role **starter**, packaging `jar`, parent `common`, imports `ludwig-bom`: yes
- directory `sources/odata-filter-spring-boot-starter/`
- package root `ru.ludwigandreas.odatafilter`
- README [`sources/odata-filter-spring-boot-starter/README.md`](sources/odata-filter-spring-boot-starter/README.md) · [`sources/odata-filter-spring-boot-starter/README.ru.md`](sources/odata-filter-spring-boot-starter/README.ru.md)
- surfaces: rest
- auto-configuration: `sources/odata-filter-spring-boot-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- i18n: `sources/odata-filter-spring-boot-starter/src/main/resources/i18n/ludwig-odata-filter-messages.properties`, `sources/odata-filter-spring-boot-starter/src/main/resources/i18n/ludwig-odata-filter-messages_ru.properties`
- test dirs: integration (`sources/odata-filter-spring-boot-starter/src/test/java/ru/ludwigandreas/odatafilter/integration`)
- test classes: 8 surefire, 1 failsafe (`*IT` / `*IntegrationTest`)
- **gate**: `mvn -q validate`, then `mvn -pl :odata-filter-spring-boot-starter -am verify`, `mvn -pl :crud-service-example -am verify`, `mvn -pl :export-spring-boot-starter -am verify`, `mvn -pl :notification-service -am verify`, `mvn -pl :user-settings-spring-boot-starter -am verify`

### `outbox-spring-boot-starter`

- role **starter**, packaging `jar`, parent `common`, imports `ludwig-bom`: yes
- directory `sources/outbox-spring-boot-starter/`
- package root `ru.ludwigandreas.outbox`
- README [`sources/outbox-spring-boot-starter/README.md`](sources/outbox-spring-boot-starter/README.md) · [`sources/outbox-spring-boot-starter/README.ru.md`](sources/outbox-spring-boot-starter/README.ru.md)
- surfaces: none
- auto-configuration: `sources/outbox-spring-boot-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Liquibase: `sources/outbox-spring-boot-starter/src/main/resources/db/changelog/outbox/outbox-changelog.xml`
- test dirs: integration (`sources/outbox-spring-boot-starter/src/test/java/ru/ludwigandreas/outbox/integration`), unit (`sources/outbox-spring-boot-starter/src/test/java/ru/ludwigandreas/outbox/unit`)
- test classes: 5 surefire, 1 failsafe (`*IT` / `*IntegrationTest`)
- **gate**: `mvn -q validate`, then `mvn -pl :outbox-spring-boot-starter -am verify`, `mvn -pl :audit-spring-boot-starter -am verify`, `mvn -pl :crud-service-example -am verify`, `mvn -pl :export-spring-boot-starter -am verify`, `mvn -pl :file-action-spring-boot-starter -am verify`, `mvn -pl :file-ingest-spring-boot-starter -am verify`, `mvn -pl :notification-service -am verify`, `mvn -pl :user-settings-spring-boot-starter -am verify`

### `reconciliation-spring-boot-starter`

- role **starter**, packaging `jar`, parent `common`, imports `ludwig-bom`: yes
- directory `sources/reconciliation-spring-boot-starter/`
- package root `ru.ludwigandreas.reconciliation`
- README [`sources/reconciliation-spring-boot-starter/README.md`](sources/reconciliation-spring-boot-starter/README.md) · [`sources/reconciliation-spring-boot-starter/README.ru.md`](sources/reconciliation-spring-boot-starter/README.ru.md)
- surfaces: actuator
- auto-configuration: `sources/reconciliation-spring-boot-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Liquibase: `sources/reconciliation-spring-boot-starter/src/main/resources/db/changelog/reconciliation/reconciliation-changelog.xml`
- test dirs: integration (`sources/reconciliation-spring-boot-starter/src/test/java/ru/ludwigandreas/reconciliation/integration`), unit (`sources/reconciliation-spring-boot-starter/src/test/java/ru/ludwigandreas/reconciliation/unit`)
- test classes: 7 surefire, 3 failsafe (`*IT` / `*IntegrationTest`)
- **gate**: `mvn -q validate`, then `mvn -pl :reconciliation-spring-boot-starter -am verify`

### `rest-client-spring-boot-starter`

- role **starter**, packaging `jar`, parent `common`, imports `ludwig-bom`: yes
- directory `sources/rest-client-spring-boot-starter/`
- package root `ru.ludwigandreas.restclient`
- README [`sources/rest-client-spring-boot-starter/README.md`](sources/rest-client-spring-boot-starter/README.md) · [`sources/rest-client-spring-boot-starter/README.ru.md`](sources/rest-client-spring-boot-starter/README.ru.md)
- surfaces: rest
- auto-configuration: `sources/rest-client-spring-boot-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- test dirs: integration (`sources/rest-client-spring-boot-starter/src/test/java/ru/ludwigandreas/restclient/integration`), unit (`sources/rest-client-spring-boot-starter/src/test/java/ru/ludwigandreas/restclient/unit`)
- test classes: 11 surefire, 8 failsafe (`*IT` / `*IntegrationTest`)
- **gate**: `mvn -q validate`, then `mvn -pl :rest-client-spring-boot-starter -am verify`, `mvn -pl :crud-service-example -am verify`, `mvn -pl :export-spring-boot-starter -am verify`, `mvn -pl :reconciliation-spring-boot-starter -am verify`

### `security-spring-boot-starter`

- role **starter**, packaging `jar`, parent `common`, imports `ludwig-bom`: yes
- directory `sources/security-spring-boot-starter/`
- package root `ru.ludwigandreas.security`
- README [`sources/security-spring-boot-starter/README.md`](sources/security-spring-boot-starter/README.md) · [`sources/security-spring-boot-starter/README.ru.md`](sources/security-spring-boot-starter/README.ru.md)
- surfaces: none
- auto-configuration: `sources/security-spring-boot-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- i18n: `sources/security-spring-boot-starter/src/main/resources/i18n/ludwig-security-messages.properties`, `sources/security-spring-boot-starter/src/main/resources/i18n/ludwig-security-messages_ru.properties`
- test dirs: unit (`sources/security-spring-boot-starter/src/test/java/ru/ludwigandreas/security/unit`)
- test classes: 15 surefire, 0 failsafe (`*IT` / `*IntegrationTest`)
- **gate**: `mvn -q validate`, then `mvn -pl :security-spring-boot-starter -am verify`, `mvn -pl :crud-service-example -am verify`, `mvn -pl :export-spring-boot-starter -am verify`, `mvn -pl :file-action-spring-boot-starter -am verify`, `mvn -pl :identity-projection-spring-boot-starter -am verify`, `mvn -pl :notification-service -am verify`, `mvn -pl :test-support-security -am verify`, `mvn -pl :user-settings-spring-boot-starter -am verify`

### `test-support`

- role **test-support**, packaging `jar`, parent `common`, imports `ludwig-bom`: yes
- directory `sources/test-support/`
- package root `ru.ludwigandreas.testsupport`
- README [`sources/test-support/README.md`](sources/test-support/README.md) · [`sources/test-support/README.ru.md`](sources/test-support/README.ru.md)
- surfaces: none
- test dirs: integration (`sources/test-support/src/test/java/ru/ludwigandreas/testsupport/integration`), unit (`sources/test-support/src/test/java/ru/ludwigandreas/testsupport/unit`)
- test classes: 1 surefire, 1 failsafe (`*IT` / `*IntegrationTest`)
- **gate**: `mvn -q validate`, then `mvn -pl :test-support -am verify`, `mvn -pl :audit-spring-boot-starter -am verify`, `mvn -pl :cache-spring-boot-starter -am verify`, `mvn -pl :db-core -am verify`, `mvn -pl :export-spring-boot-starter -am verify`, `mvn -pl :file-action-spring-boot-starter -am verify`, `mvn -pl :file-ingest-spring-boot-starter -am verify`, `mvn -pl :idempotency-spring-boot-starter -am verify`, `mvn -pl :job-core -am verify`, `mvn -pl :messaging-spring-boot-starter -am verify`, `mvn -pl :object-storage-spring-boot-starter -am verify`, `mvn -pl :odata-filter-spring-boot-starter -am verify`, `mvn -pl :outbox-spring-boot-starter -am verify`, `mvn -pl :reconciliation-spring-boot-starter -am verify`, `mvn -pl :test-support-security -am verify`, `mvn -pl :user-settings-spring-boot-starter -am verify`

### `test-support-security`

- role **test-support**, packaging `jar`, parent `common`, imports `ludwig-bom`: yes
- directory `sources/test-support-security/`
- package root `ru.ludwigandreas.testsupport.security`
- README [`sources/test-support-security/README.md`](sources/test-support-security/README.md) · [`sources/test-support-security/README.ru.md`](sources/test-support-security/README.ru.md)
- surfaces: none
- test classes: 0 surefire, 0 failsafe (`*IT` / `*IntegrationTest`)
- **gate**: `mvn -q validate`, then `mvn -pl :test-support-security -am verify`, `mvn -pl :crud-service-example -am verify`, `mvn -pl :file-action-spring-boot-starter -am verify`, `mvn -pl :notification-service -am verify`

### `user-settings-spring-boot-starter`

- role **starter**, packaging `jar`, parent `common`, imports `ludwig-bom`: yes
- directory `sources/user-settings-spring-boot-starter/`
- package root `ru.ludwigandreas.usersettings`
- README [`sources/user-settings-spring-boot-starter/README.md`](sources/user-settings-spring-boot-starter/README.md) · [`sources/user-settings-spring-boot-starter/README.ru.md`](sources/user-settings-spring-boot-starter/README.ru.md)
- surfaces: rest
- auto-configuration: `sources/user-settings-spring-boot-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Liquibase: `sources/user-settings-spring-boot-starter/src/main/resources/db/changelog/user-settings/user-settings-changelog.xml`
- i18n: `sources/user-settings-spring-boot-starter/src/main/resources/i18n/ludwig-user-settings-messages.properties`, `sources/user-settings-spring-boot-starter/src/main/resources/i18n/ludwig-user-settings-messages_ru.properties`
- test dirs: architecture (`sources/user-settings-spring-boot-starter/src/test/java/ru/ludwigandreas/usersettings/architecture`), integration (`sources/user-settings-spring-boot-starter/src/test/java/ru/ludwigandreas/usersettings/integration`), unit (`sources/user-settings-spring-boot-starter/src/test/java/ru/ludwigandreas/usersettings/unit`)
- test classes: 14 surefire, 3 failsafe (`*IT` / `*IntegrationTest`)
- **gate**: `mvn -q validate`, then `mvn -pl :user-settings-spring-boot-starter -am verify`, `mvn -pl :notification-service -am verify`

### `web-core-spring-boot-starter`

- role **starter**, packaging `jar`, parent `common`, imports `ludwig-bom`: yes
- directory `sources/web-core-spring-boot-starter/`
- package root `ru.ludwigandreas.webcore`
- README [`sources/web-core-spring-boot-starter/README.md`](sources/web-core-spring-boot-starter/README.md) · [`sources/web-core-spring-boot-starter/README.ru.md`](sources/web-core-spring-boot-starter/README.ru.md)
- surfaces: rest
- auto-configuration: `sources/web-core-spring-boot-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- i18n: `sources/web-core-spring-boot-starter/src/main/resources/i18n/ludwig-web-messages.properties`, `sources/web-core-spring-boot-starter/src/main/resources/i18n/ludwig-web-messages_ru.properties`
- test dirs: integration (`sources/web-core-spring-boot-starter/src/test/java/ru/ludwigandreas/webcore/integration`), unit (`sources/web-core-spring-boot-starter/src/test/java/ru/ludwigandreas/webcore/unit`)
- test classes: 17 surefire, 3 failsafe (`*IT` / `*IntegrationTest`)
- **gate**: `mvn -q validate`, then `mvn -pl :web-core-spring-boot-starter -am verify`, `mvn -pl :audit-spring-boot-starter -am verify`, `mvn -pl :crud-service-example -am verify`, `mvn -pl :db-core -am verify`, `mvn -pl :export-spring-boot-starter -am verify`, `mvn -pl :file-action-spring-boot-starter -am verify`, `mvn -pl :file-ingest-spring-boot-starter -am verify`, `mvn -pl :idempotency-spring-boot-starter -am verify`, `mvn -pl :messaging-spring-boot-starter -am verify`, `mvn -pl :notification-service -am verify`, `mvn -pl :object-storage-spring-boot-starter -am verify`, `mvn -pl :observability-spring-boot-starter -am verify`, `mvn -pl :odata-filter-spring-boot-starter -am verify`, `mvn -pl :rest-client-spring-boot-starter -am verify`, `mvn -pl :security-spring-boot-starter -am verify`, `mvn -pl :user-settings-spring-boot-starter -am verify`

