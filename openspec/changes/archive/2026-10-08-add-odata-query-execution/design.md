## Context

See `proposal.md` - Why. The design-relevant facts about the current tree:

- `ODataFilterService` has no Spring MVC and no JPA runtime dependency. That is what lets
  `export-spring-boot-starter` replay a saved filter and would let a batch job do the same, and it must
  survive this change.
- `PredicateBuilder.rootPath(Class)` derives the query alias as the uncapitalized simple class name,
  which is Spring Data's `SimpleEntityPathResolver` convention and therefore also the alias of QueryDSL's
  generated default instance (`QProductEntity.productEntity`). Predicate and ordering agreeing on that
  alias is a *silent* correctness condition: a root declared `new QProductEntity("p")` cross-joins
  instead of failing.
- `PredicateBuilder.typedPath` already performs the segment walk that both services duplicate, but it is
  private and takes a `PathBuilder` the caller never sees.
- `querydsl-jpa` and `jakarta.persistence-api` are already compile-scope in this module;
  `spring-boot-starter-data-jpa` is test-scope only. So `JPAQuery` types are compilable here, but no
  `EntityManager` is guaranteed at runtime.
- `odata.filter.web.dollar-prefixed-parameters-only` exists and is effectively dead configuration: the
  only code that honours it is inside the off-by-default deprecated resolver.
- `revapi` is armed in every module, but no `v*` tag is published, so every baseline is an empty archive
  and the compatibility gate currently passes any removal. This is from
  `add-api-compatibility-and-release-governance`'s own receipt.
- Both services carry an identical `QuerydslConfig` declaring the `JPAQueryFactory` bean. Noted, not
  fixed here - see Open Questions.

## Goals / Non-Goals

**Goals:**

- A repository that needs a filtered, ordered, counted page writes none of the four mechanical steps.
- A repository that needs a fetch join or a projection still writes none of them.
- The alias agreement between predicate and ordering becomes a loud failure rather than a cross join.
- `ODataFilterService` stays usable with no `EntityManager` and no Spring MVC on the classpath.

**Non-Goals (design level, beyond the proposal's):**

- Hiding `JPAQueryFactory` from the consumer. The executor takes the consumer's factory and the
  consumer's generated Q-type; it does not become a repository framework.
- Supporting a non-JPA QueryDSL backend (SQL, Mongo). The predicate is JPQL-bound through `querydsl-jpa`
  already.

## Decisions

### D1: The executor lives in `odata-filter-spring-boot-starter`, not `db-core`

`db-core` is the persistence-support library and is the instinctive home. Rejected: twelve modules depend
on `db-core`, and all twelve would acquire `odata-filter-spring-boot-starter` transitively, including
modules with no HTTP surface at all. The executor also needs the policy registry and the predicate
builder, which live here. New package `ru.ludwigandreas.odatafilter.querydsl` (existing) for the
executor and the ordering conversion.

### D2: The executor hands the consumer the root; the consumer never names the alias

The executor derives the root from the entity type exactly as `PredicateBuilder` does, and passes it to
the consumer's query-shaping lambda. A consumer that needs a typed association for a fetch join supplies
its generated Q-type, and **the executor asserts that the supplied root's metadata name equals the
derived alias**, failing fast otherwise.

Alternatives considered: (a) document the convention - that is the status quo, and the status quo is a
comment in two repositories about a condition neither can check; (b) require the consumer to build the
root from an exposed `ODataPaths.alias(Class)` - correct but it makes every consumer write
`new QProductEntity(ODataPaths.alias(ProductEntity.class))` where `QProductEntity.productEntity` already
has the right alias. The assertion gets the same guarantee with the natural call site.

**Check:** the assertion itself is the mechanical check, and it is the enforcement half of the
"encode every rule twice" rule for a condition neither Checkstyle (it is not source text) nor ArchUnit
(it is a runtime string value) can see. Its javadoc says exactly that.

### D3: Content query and count query are shaped separately

QueryDSL refuses `fetchJoin` on a count query, and a fetch join is the first thing a real consumer adds.
So the shaping hook is two hooks: one for the content query and an optional one for the count. When the
count hook is absent, the count runs against the bare root with the same predicate, which is correct and
is what both services do by hand today.

Alternative considered: one hook with the executor stripping fetch joins. Rejected - it means inspecting
and rewriting a consumer's query, and getting it wrong produces a wrong total rather than an error.

### D4: `$count=false` makes the total absent, not zero

`PageResponse`'s `totalElements` and `totalPages` become nullable (`Long`, `Integer`), null when the
caller declined the count, and the record is annotated so that a null member is omitted from the JSON
rather than serialized as `null`. Zero is not available as a sentinel: zero is a real total.

Alternative considered: a separate `SliceResponse` envelope. Rejected as a second paged envelope - see
the proposal's Non-goals.

### D5: `PageResponse` keeps `page` and gains `offset`

The bug is not that `page` exists; it is that `page` was the *only* position reported, and it is lossy.
With `offset` present, `page` is unambiguous - it is the page this offset falls in - and sixteen
dependent modules, most of whose endpoints are ordinary page-based Spring Data paging rather than OData,
keep the member that suits them.

Alternative considered: replacing `page` with `offset` outright. It is the cleaner record and this is a
major release, but it breaks the response shape of every paged endpoint in sixteen modules to fix a
defect that only OData's unaligned `$skip` exhibits. The added member is the proportionate change.

`OffsetPageRequest.getPageNumber()` keeps its truncating derivation, which `Pageable`'s own contract
requires it to answer, and gains javadoc saying it is lossy and must not be the only position published.

### D6: The deleted argument resolver's request-reading half is kept, and the parsing half is not

The new `ODataQueryOptionsArgumentResolver` resolves a controller parameter of type `ODataQueryOptions`
by reading `$filter`/`$orderby`/`$top`/`$skip`/`$count` (with the non-prefixed aliases, honouring
`dollar-prefixed-parameters-only`, which stops being dead configuration). It is the *same* request-reading
code the deleted resolver had.

None of the four objections in the module README applies to it, and the reason is that each of them was
an objection to what the old resolver *produced*: `ODataQueryOptions` names no entity, so
`web.controllers-do-not-expose-entities` has nothing to catch; it holds no `Predicate`, so the controller
need not hold a repository nor push an entity into the service API; it is describable, which is D7; and
it performs no policy evaluation, so there is no 403 to leak field names before `@PreAuthorize` runs - an
unparseable `$top` is a 400 about the request, not about the data model.

### D7: OpenAPI description needs a springdoc customizer and therefore one BOM entry

Spring's `@ModelAttribute` binding cannot alias `$filter` onto a member named `filter`, which is why D6
uses a resolver; and a resolver-bound parameter is invisible to springdoc, which is objection three
against the old resolver and would otherwise be inherited. So the module ships an `OperationCustomizer`
that adds the five query parameters to any operation declaring an `ODataQueryOptions` parameter, gated
`@ConditionalOnClass` on springdoc and `@ConditionalOnMissingBean`, with springdoc declared `optional`
exactly as `micrometer-core`, `spring-security-core` and `web-core-spring-boot-starter` already are here.

**This contradicts the proposal's "no POM changes are expected" and that line is wrong.** Two POM
changes, both in the correct tier:

- `build/ludwig-bom/pom.xml` - a managed `springdoc-openapi-starter-common` entry under the existing
  `${springdoc.version}` property (2.6.0), because third-party versions live only there.
- `sources/odata-filter-spring-boot-starter/pom.xml` - that dependency, `<optional>true</optional>`.

Neither the root POM nor `ludwig-service-parent` changes: no plugin, no compiler setting and no service
build decision is involved.

Because a POM changes, `scripts/manifest.sh build` runs before the gate.

### D8: `ODataQuery.predicate()` returns `Optional<Predicate>`

An always-true predicate is indistinguishable from a caller's real one, which is the defect behind
`export`'s `Optional.ofNullable(query.predicate())`. The executor ANDs the predicate when present and
omits it when absent. `export`'s parser becomes a straight pass-through of the `Optional`.

### Dependency-direction check

**Does `web-core-spring-boot-starter` already depend on `odata-filter-spring-boot-starter`, directly or
transitively?** `project-index.json`'s `inRepoDependencies` for `web-core-spring-boot-starter` is `[]` -
it has zero in-repo dependencies, deliberately. So editing `PageResponse` adds no edge in either
direction. The existing edge runs `odata-filter-spring-boot-starter` -> `web-core-spring-boot-starter`
(compile, optional), and this change does not reverse or duplicate it.

**Does `odata-filter-spring-boot-starter` already depend on `export-spring-boot-starter`?**
`inRepoDependents` for `odata-filter-spring-boot-starter` is `[crud-service-example,
export-spring-boot-starter, notification-service, user-settings-spring-boot-starter]`, so the edge runs
`export` -> `odata-filter`. This change edits `export`'s consumer of `odata-filter`, which follows the
existing direction and adds no edge.

**Does this change add any in-repo dependency at all?** No. The only new dependency is third-party
(`springdoc-openapi-starter-common`, optional).

### Which of the three POMs changes

`build/ludwig-bom/pom.xml` (one third-party version entry) and the module POM. Not the root `pom.xml`,
not `ludwig-service-parent`. See D7.

### New conventions and the check that enforces each

| Convention | Check | Owner |
|---|---|---|
| A service repository does not construct a `PathBuilder` for a caller-supplied path | new ArchUnit rule in `architecture-rules`, in the existing `layering` or `data` group, naming in its javadoc the modules that legitimately own a caller-facing path vocabulary | `architecture-rules` (structure) |
| Ordering root and predicate root share an alias | runtime assertion in the executor, with javadoc stating why neither Checkstyle nor ArchUnit can see it | the code itself (D2) |
| `$count=false` yields no total | unit test on the executor and on `PageResponse` serialization | tests |
| Both locale bundles carry the new `$count` rejection key | the existing bundle key-set parity check | existing |

The ArchUnit rule must not be written as a Checkstyle regex for `PathBuilder`: a type reference is
exactly what bytecode analysis sees, and the enforcement triad gives structure to ArchUnit.

### Nothing here is a second implementation of a centralised mechanism

The error path is `web-core`'s existing `ProblemDetail` pipeline through this module's existing
`ODataFilterProblemMapper` and bundle. The paged envelope is `web-core`'s existing `PageResponse`,
amended rather than duplicated. No audit sink, no operation envelope, no cache primitive and no
preference type is introduced; the audit question is a separate change.

## Risks / Trade-offs

- **`PageResponse` has sixteen dependent modules and this changes its canonical constructor and its JSON.**
  → The gate runs all sixteen, and that is the point of running them. Nullable totals are additive for
  every existing caller because nothing but the executor passes null; `offset` is an added member. The
  one unavoidable break is source-level, for direct canonical-constructor callers, which a major release
  permits.
- **A consumer passes a Q-type with a non-default alias and now gets an exception where it previously got
  silently wrong results.** → That is the intended trade: the previous behaviour was a cross join nobody
  noticed. The exception message names both aliases.
- **`$count=false` plus a client that assumes a total is always present.** → The member is omitted rather
  than null, so a client that never sends `$count=false` sees no change at all; one that does has asked
  for this.
- **If a `v1.x` tag is published before this ships, revapi refuses the deletions and the signature
  changes.** → Fallback: keep `ODataQueryArgumentResolver` for one release as
  `@Deprecated(since = "<that version>", forRemoval = true)` and keep the old `parse` overload
  delegating to the new one, likewise annotated. This is a release-sequencing decision, not a design
  change, and the tasks note it.
- **The executor becomes a place where query logic accretes.** → Its surface is deliberately two hooks
  and no query DSL of its own; a consumer that needs more drives its own query and takes only the
  ordering conversion, which the spec requires to remain separately available.

## Open Questions

- Both services declare an identical `QuerydslConfig` providing the `JPAQueryFactory` bean. That is the
  same duplication this change removes elsewhere, but it belongs to `db-core`, which is not in this
  change's module set. Deferred: it changes no spec here and no task below, and folding a thirteenth
  module's dependents into this gate for a six-line bean is not proportionate.
