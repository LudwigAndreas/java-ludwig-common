## Context

See `proposal.md` - Why. The design-relevant facts:

- `FilterPolicyRegistry.policyFor(Class)` already resolves and memoizes an `EntityFilterPolicy` holding
  every exposed path as a `Map<String, FilterFieldPolicy>`, where each `FilterFieldPolicy` carries
  `javaType`, `allowedOperators`, `sortable` and `roleRequirements`, plus a `permitsRoles(Set<String>)`
  that already implements the "every traversed `@Filterable` must be satisfied" rule. The document is a
  projection of that; nothing needs to be re-resolved or re-derived.
- The registry is keyed by `Class` and is populated lazily, on the first query against an entity. An
  endpoint addressed by a URL needs a name-to-class index that exists before the first query.
- `FilterPrincipalResolver` already resolves the caller's roles, from Spring Security when present and
  from a consumer-supplied bean otherwise.
- `ODataFilterMetrics` has a no-op implementation and a Micrometer one chosen by an existing
  auto-configuration, so a new meter costs no new conditionality.
- `spring-webmvc` and `micrometer-core` are already optional dependencies of this module, and
  `web-core-spring-boot-starter` is already on the compile path for the problem pipeline.

## Goals / Non-Goals

**Goals:**

- A service gets discovery for every entity that opts in, without writing an endpoint, a DTO or a
  registration list.
- The document and the query enforcement cannot drift, because they read the same resolved policy.
- A caller cannot learn a path or a privilege it does not hold.

**Non-Goals (design level):**

- Serving the document from anywhere but the resolved policy. No second description of the filterable
  surface is introduced, in any form - not a generated file, not an annotation attribute listing fields.
- Making the policy mutable at runtime.

## Decisions

### D1: Entities are indexed by an explicit published name, not a derived one

`@FilterPolicy` gains a member holding the name the entity is published under
(`@FilterPolicy(metadataName = "product")`). An entity that does not set it is not published, which is how
opt-in is expressed: one member carries both the decision and the identifier.

Alternative considered: deriving the name from the entity's simple class name, stripping a conventional
`Entity` suffix. Rejected - `ProductEntity` and `NotificationDeliveryEntity` would publish as `product`
and `notificationDelivery`, making a class rename a breaking API change in a URL, and making the public
identifier a property of a Java identifier nobody reviewed as an API decision. The same reasoning already
governs `@Filterable(name = ...)`.

### D2: The name-to-class index comes from the JPA metamodel, not from a per-service list

A `FilterMetadataRegistry` bean built at startup walks `EntityManagerFactory.getMetamodel().getEntities()`,
keeps those whose Java type carries a `@FilterPolicy` with a published name, and fails startup on a
duplicate name. A consumer registers nothing.

Alternative considered: a `FilterMetadataSource` bean in which each service lists its published entity
classes. Rejected for the golden-starter goal - it is exactly the per-service registration list this work
exists to delete, and it can silently disagree with the annotations.

Consequence: the metadata auto-configuration is conditional on an `EntityManagerFactory` bean, which is
correct - an entity with no persistence unit has no endpoint to describe. `ODataFilterService` keeps
working with no `EntityManager`, as it must for `export`.

### D3: No cache, deliberately

The obvious implementation caches the rendered document. It would have to be keyed on the caller's
resolved role set, not on the entity, or an administrator's field list gets served to an unprivileged
caller - which is why the spec states that condition.

This design caches nothing. The policy is already memoized by `FilterPolicyRegistry`; what remains per
request is a stream over a `Map` of a few dozen entries calling an existing `permitsRoles`. Caching that
would mean taking an in-repo dependency on `cache-spring-boot-starter`, declaring a `CacheDefinition` and
choosing a `CachePurpose` - and the honest purpose would be `security`, because the TTL would be how long a
revoked role keeps seeing a field. Paying a security-purpose cache's correctness cost to save a map
traversal is the wrong trade, and the platform's caching rule is satisfied by not needing it.

**Recorded here because "why is there no cache" is the first question a reviewer asks**, and because if a
later change adds one it must go through `LudwigCacheRegistry` with `CachePurpose.SECURITY` and a role-set
key, not a `Caffeine` builder.

### D4: Role filtering reuses `permitsRoles`, and a test asserts the two projections agree

The document builder calls `FilterFieldPolicy.permitsRoles` and `sortable` directly. It does not
re-implement the "any of these roles, for every traversed annotation" rule.

**Check:** a unit test over the module's existing test entities (`Employee`, `Department`) asserting, for
each of several role sets, that (a) every path in the document is accepted by `FieldAccessValidator` for
that caller, (b) every operator the document advertises on a path is accepted on that path, and (c) no path
absent from the document is accepted. That is the mechanical half of the "cannot drift" requirement.
Neither ArchUnit nor Checkstyle can express it - it is an agreement between two computations, not a
structural or textual fact - and the test's javadoc says so.

### D5: An unpublished entity is a 404 through the existing problem pipeline

A new `ludwig.odata.error.*` code mapped in the existing `ODataFilterProblemMapper`, with keys in both
locale bundles. The response is a 404 and not an empty document, because an empty document confirms the
entity exists. The property paths and operator names inside a successful document are the API's vocabulary
and are deliberately not translated; only the error text is.

### D6: The base path is configuration, and absent configuration means no endpoint

`odata.filter.metadata.base-path` (unset by default) mounts the controller. Unset, the controller bean is
not created at all, so the surface does not exist rather than existing and refusing. The new counter is
`odata.filter.metadata.served`, tagged by entity, alongside the three existing `odata.filter.*` meters.

### Dependency-direction check

**Does `odata-filter-spring-boot-starter` already depend on `crud-service-example`, directly or
transitively?** `project-index.json`'s `inRepoDependents` for `odata-filter-spring-boot-starter` is
`[crud-service-example, export-spring-boot-starter, notification-service,
user-settings-spring-boot-starter]`, so the edge runs `crud-service-example` ->
`odata-filter-spring-boot-starter`. This change adds nothing in the reverse direction: the service only
sets an annotation member and asserts a response.

**Does this change add any in-repo dependency at all?** No. Everything it needs -
`FilterPrincipalResolver`, `FilterPolicyRegistry`, `ODataFilterMetrics`, the problem pipeline,
`spring-webmvc` - is already on this module's compile path. D3 is what keeps
`cache-spring-boot-starter` out of it.

### Which of the three POMs changes

None. No third-party dependency is added, so `ludwig-bom` does not change; no plugin or compiler setting
changes, so the root `pom.xml` does not; no service build decision changes, so `ludwig-service-parent` does
not. `scripts/manifest.sh build` is therefore not required, and `scripts/manifest.sh stale` must still exit
0 at the end.

### New conventions and the check that enforces each

| Convention | Check | Owner |
|---|---|---|
| The document never contains a path the caller cannot use | the agreement test in D4 | tests |
| A published name is unique across the reactor's entities in one application | startup failure in `FilterMetadataRegistry`, naming both classes | the code itself |
| Both locale bundles carry the new 404 keys | the existing bundle key-set parity check | existing |
| No second description of the filterable surface is introduced | none possible; stated as a comment on `FilterMetadataRegistry` saying the document is a projection of `EntityFilterPolicy` and that any field list declared beside the annotations is the defect this comment exists to prevent | comment, per "encode every rule twice" |

### This introduces no second implementation of a centralised mechanism

Errors go through `web-core`'s `ProblemDetail` pipeline via this module's existing mapper and bundle;
metrics go through the module's existing `ODataFilterMetrics`; roles go through the existing
`FilterPrincipalResolver`; caching is not introduced at all (D3). No audit sink, operation envelope or
preference type is involved.

## Risks / Trade-offs

- **The document is a map of the queryable surface and an attacker would like it.** → Opt-in per entity,
  off until a base path is configured, role-filtered per caller, and never carrying a role name. A
  deployment that wants it behind authentication puts it there as it would any other endpoint; the
  starter does not decide a service's authentication.
- **Publishing a name makes it an API commitment, and `@Filterable(name = ...)` changes become breaking.**
  → True, and it is the point: the names were already a commitment the moment a client sent a filter
  naming them. The document makes the commitment visible to whoever edits the annotation.
- **A duplicate published name fails startup rather than the build.** → Two entities in one application
  can come from two modules that never see each other, so no single compilation can check it. The failure
  is at startup, which is the earliest point the pair exists, and the message names both classes.
- **The JPA metamodel walk runs at startup over every entity.** → It is one pass over an already-built
  metamodel reading one annotation, on an application that is already paying for Hibernate's own
  bootstrap.
- **A client may assume the document is CSDL because the endpoint is OData-adjacent.** → The path, the
  media type and the README all name it the filter policy. This is a naming discipline with no mechanical
  check, and the README says so.
