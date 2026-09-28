# Module dependency direction

## Purpose
The reactor DAG must stay acyclic, and some modules must stay low in it so that the modules
below them can depend on them. A cycle is not a subtle failure — the reactor refuses to build —
but the *cheapest-looking* fix for one is usually to move the module that was deliberately placed,
which is how a considered constraint becomes an accident.

## Requirements

### Requirement: A change that adds an in-repo dependency states the direction check
Before a change adds a dependency from one in-repo module to another, its design artifact SHALL
answer explicitly: *does the target module already depend on this module, directly or
transitively?* The answer SHALL cite `project-index.json`'s `inRepoDependents` for the target,
not an assertion from memory.

#### Scenario: A design adds an in-repo dependency
- **WHEN** a design proposes that module A depend on module B
- **THEN** it contains the question and its answer, naming B's `inRepoDependents` from
  `project-index.json` and showing that A is not among them

#### Scenario: A change is proposed with no design artifact
- **WHEN** a change crosses a module boundary or adds a module
- **THEN** a design artifact is required, because the dependency-direction check has nowhere else
  to live

### Requirement: cache-spring-boot-starter has zero in-repo dependencies
`cache-spring-boot-starter`'s main sources SHALL depend on no other module of this platform. This
is what lets `security-spring-boot-starter` — which sits near the bottom of the reactor, with
identity-projection, user-settings, web-core and every service above it — depend on the cache
without a cycle.

#### Scenario: The cache module imports another platform module
- **WHEN** a main class under `ru.ludwigandreas.cache..` depends on a class outside its own
  packages and outside the permitted third-party set (`java..`, `jakarta..`,
  `org.springframework..`, Caffeine, Jackson, Micrometer, SLF4J, Lombok) — for example to audit an
  eviction or to render a cache error as a `ProblemDetail`, both of which are one import away and
  both of which look reasonable
- **THEN** `ModuleIndependenceTest.noInRepoDependencies` fails the build, and the fix is to remove
  the dependency rather than to move the cache module

#### Scenario: A cache test uses test-support
- **WHEN** a test in `cache-spring-boot-starter` uses `test-support` for a digest-pinned container
- **THEN** that is permitted, because the rule imports main classes only and a test-scope
  dependency closes no cycle

### Requirement: Optional third-party dependencies stay out of the public API
Where a module declares a third-party dependency `optional`, the types a consumer must touch to
use the module SHALL resolve without it, so that a deployment without it fails at startup rather
than at first use.

#### Scenario: The cache public API reaches an optional Redis type
- **WHEN** a class under `ru.ludwigandreas.cache.api..`, `…core..`, `…tx..` or `…metrics..`
  depends on `org.springframework.data.redis..`
- **THEN** `ModuleIndependenceTest.apiIsFreeOfRedis` fails, because a local-only service would
  otherwise get a `NoClassDefFoundError` at its first cache lookup rather than at startup

### Requirement: The manifest is the source for dependency direction
`project-index.json` SHALL carry `inRepoDependencies` and `inRepoDependents` for every module, in
both directions, generated from the POMs by `scripts/manifest.sh build` and never hand-written.

#### Scenario: A POM changes and the manifest is not regenerated
- **WHEN** a POM's dependencies change and `scripts/manifest.sh build` has not been re-run
- **THEN** `scripts/manifest.sh stale` exits non-zero, comparing a SHA over the full POM set
  rather than mtimes, which change on every checkout
