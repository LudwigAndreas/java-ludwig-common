# Publish each service's OpenAPI document as a committed, build-verified artifact

## Why

The platform asserts things about its generated OpenAPI documents that nothing checks. The
`odata-query-contract` capability already requires that a filterable endpoint's "generated OpenAPI
document lists all five query parameters with their types", and `odata-filter-spring-boot-starter`
ships `ODataQueryOptionsOpenApiCustomizer` to make that true. But `crud-service-example` - the
reference service that every new service is copied from - has no springdoc dependency at all, so it
produces no document, that customizer never runs in it, and the requirement is unverifiable in the
one module whose job is to demonstrate the shape.

Separately, the two services' HTTP contracts exist only inside a running process. A reviewer cannot
see that a pull request widened a response, removed a field or changed a status code, because the
API surface is not in the tree. `check_api_baseline.sh` guards *Java* API compatibility for library
modules; nothing guards the *HTTP* surface of a service.

## What Changes

- `crud-service-example` gains `springdoc-openapi-starter-webmvc-ui`, matching `notification-service`,
  and an `OpenApiConfig` declaring its `info` block and the shared `ProblemDetail` schema. This
  activates the OData customizer it already depends on, so `ProductController`'s query options become
  described rather than invisible.
- Each of the two services publishes its OpenAPI document to `docs/api/<artifactId>.openapi.yaml`,
  generated from the real Spring context rather than hand-written.
- An integration test per service regenerates the document and **fails the build when the committed
  copy differs**, printing the regeneration command. The document therefore cannot drift from the
  code, and a change to the HTTP surface shows up as a reviewable diff.
- `docs/api/README.md` states what the documents are, that they are generated, and how to refresh
  them.

No endpoint, response body, status code or error shape changes. This change adds a description of the
existing surface and a check that the description stays true.

## Capabilities

### New Capabilities

- `published-api-document`: a service's HTTP surface is a generated, committed artifact that the
  build keeps in step with the code, including the error contract that no controller return type
  mentions.

### Modified Capabilities

None. `odata-query-contract` already requires that the generated document describe the five query
options; this change makes that requirement checkable in the reference service without altering it.

## Non-goals

- **No new gate script.** Drift can only be detected by something that can produce the document,
  which needs the Spring context and a database. That is the integration test. A
  `scripts/check_api_docs.sh` could compare only file presence, which is a check that cannot check
  the thing it is named after.
- **No OpenAPI document for library modules.** A starter contributes to a document; it does not have
  one. `web-core`, `odata-filter` and the rest stay as they are.
- **No published-contract promise.** These documents describe what the services currently serve. They
  are not a compatibility baseline, and nothing fails because a diff is backwards-incompatible -
  that would need a versioning policy this repository has not decided.
- **No Swagger UI change for `notification-service`.** It already serves one.
- **No Russian counterpart.** `docs/` is English-only today (six files, no `.ru.md`); the generated
  documents carry no prose of their own beyond the `info` block.

## Impact

**Modules touched** (names from `project-index.json`):

| Module | POM tier | Parent | In-repo dependents |
|---|---|---|---|
| `crud-service-example` | service | `ludwig-service-parent` | 0 |
| `notification-service` | service | `ludwig-service-parent` | 0 |

Both are services, so neither is on any other module's compile path and the gate stays narrow:
`mvn -q validate` plus `mvn -pl :<artifactId> -am verify` for each.

**Shared contracts in `openspec/specs/`**: none are modified. Two are *relied upon* and must keep
holding - `odata-query-contract` (the five query options appear in the document) and
`problem-detail-pipeline` (every error is an RFC 9457 document produced by `web-core`'s advice, which
is why the schema has to be declared by hand rather than inferred from a return type).

**Dependencies**: one new third-party dependency on `crud-service-example`,
`org.springdoc:springdoc-openapi-starter-webmvc-ui`. Its version is already managed in
`build/ludwig-bom/pom.xml` (`springdoc.version` 2.6.0), so no version is written and no POM outside
the service changes.

**New committed artifacts**: `docs/api/crud-service-example.openapi.yaml`,
`docs/api/notification-service.openapi.yaml`, `docs/api/README.md`.
