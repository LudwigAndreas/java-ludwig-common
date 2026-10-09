## Why

A client of a filterable endpoint has no way to learn what it may filter on. `@Filterable` and
`@FilterPolicy` are compile-time declarations on a JPA entity; `FilterPolicyRegistry` resolves them into
an `EntityFilterPolicy` carrying every exposed property path, its Java type, its allowed operators, its
`sortable` flag and its role requirements - and that resolved policy never leaves the JVM. So the only way
to discover the filterable surface is to send filters and read the rejections.

The module's own README recommends alerting on exactly that behaviour: `odata.filter.rejected` is
documented as "either a client bug or someone probing for what's filterable - worth alerting on either
way". The starter is currently the cause of the probing it tells operators to alarm on, and a client
author's alternative is reading the service's entity source, which is not something an API consumer
should need.

This is also the piece that makes a generated or hand-written client possible: a filter UI, a saved-search
feature, or a report builder all need the field list, the per-field operator list and the page-size
ceiling, and all three currently hard-code them against a copy of the entity.

## What Changes

- **A filter-metadata document per entity**, served from `FilterPolicyRegistry`'s already-resolved
  `EntityFilterPolicy`: the exposed property paths, each path's type, its permitted operators and whether
  it is sortable, plus the entity's effective `maxDepth`, `maxPageSize`, `defaultPageSize`,
  `maxNestedPropertyDepth` and `defaultOrderBy`. No parsing, no new policy resolution - this is a
  projection of what the registry already computes on first use.
- **Discovery is opt-in per entity**, via a new `@FilterPolicy` member. An entity that does not declare it
  has no metadata document and the endpoint answers 404 for it.
- **The document is filtered by the caller's roles.** A path the caller may not filter on is absent, not
  present-and-marked-forbidden, resolved through the same `FilterPrincipalResolver` the query path uses.
- **A controller is contributed by the starter**, enabled by configuration and mounted at a configurable
  base path, so a service gets discovery without writing an endpoint per entity.
- **Metadata requests are counted**, as a new Micrometer counter alongside the existing
  `odata.filter.*` meters, so the "probing" signal the README describes can be told apart from clients
  that asked properly.

## Capabilities

### New Capabilities
- `odata-filter-metadata`: what a client may learn about an endpoint's filterable surface without
  probing it - what the document states, which entities have one, and what a caller's roles remove from
  it.

### Modified Capabilities

None. `odata-query-contract` (added by `add-odata-query-execution`) governs how options are *executed*;
this capability governs what is *published about* the policy, and the two share no requirement. Keeping
them separate is also what lets this change land independently of that one.

## Impact

### Modules touched

| Module | POM tier | In-repo dependents the gate must run |
|---|---|---|
| `odata-filter-spring-boot-starter` | library (starter), parented by the reactor root `common` | `crud-service-example`, `export-spring-boot-starter`, `notification-service`, `user-settings-spring-boot-starter` |
| `crud-service-example` | service, parented by `ludwig-service-parent` | none |

`crud-service-example` is touched only to enable discovery on `ProductEntity` and
`CategoryEntity` and to assert the document end to end - it is the module that exists to be the reference
arrangement. `notification-service` is deliberately **not** touched: a delivery's filterable surface
includes fields chosen for an operator console, and publishing it is a decision for whoever owns that
service, which is precisely why this is opt-in.

### Shared contracts in `openspec/specs/`

- **`problem-detail-pipeline`** - not modified. A request for an entity with no published metadata is a
  404 through this module's existing `ODataFilterProblemMapper` and bundle, which is an instance of that
  pipeline.
- **`i18n-bundles`** - not modified. The new rejection's keys go in both existing locale bundles and the
  existing key-set parity check covers them. The document's own content is not user-facing prose: property
  paths and operator names are the API's vocabulary and are deliberately not translated.
- **`data-access`**, **`audit-envelope`**, **`cache-purpose`**, **`long-running-operations`**,
  **`repository-layout`**, **`pom-topology`**, **`test-layout`**, **`enforcement-triad`**,
  **`api-evolution`** - not touched. No module is added or moved, nothing is removed, no POM tier changes,
  and every addition is a new public type, which `api-evolution` puts at a patch increment.

### Dependencies

No new in-repo dependency and no new third-party dependency. The controller uses `spring-webmvc`, already
an optional dependency here; the counter uses `micrometer-core`, likewise already optional; roles come
from the existing `FilterPrincipalResolver`. No POM changes.

## Non-goals

- **An OData CSDL `$metadata` document.** Real CSDL means an Olingo `EdmProvider`, an entity container, and
  a declaration of entity sets, navigation properties and actions - a description of a service this module
  is not implementing. A document that called itself `$metadata` while describing only the filterable
  subset would be a worse lie than not having one. The document describes *this module's policy* and is
  named so.
- **Publishing the whole entity.** Only `@Filterable` paths appear. An unannotated field is invisible to
  the filter and must stay invisible to discovery; the document is a projection of the allow-list, never of
  the schema.
- **On-by-default.** A metadata document is a map of the queryable surface, which is useful to a client and
  equally useful to someone enumerating it. Opt-in per entity, and off until a base path is configured.
- **Describing anything the caller cannot use.** No "present but forbidden" entries and no role names in
  the document: a caller learning that `supplierCost` exists and needs `ROLE_ADMIN` has learned both a
  column name and a privilege name from an endpoint that was supposed to tell it less.
- **Generating client code.** The document is the input a generator would need; the generator is not this.
- **A write surface.** Policy comes from annotations resolved at startup. Nothing here makes it editable at
  runtime, which would make the filter surface a deployment-time secret rather than a reviewed source
  change.
