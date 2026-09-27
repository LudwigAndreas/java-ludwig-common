# Cache purpose and the meaning of a TTL

## Purpose
`cache-spring-boot-starter` is the platform's one caching primitive. `security`'s
`AuthorityCache` and `user-settings`' `SettingsCache` were the same three classes written twice,
down to the `(Duration ttl, long maximumSize)` constructor and the thundering-herd paragraph —
and neither called `recordStats()`, so hit ratio was unobservable platform-wide.

## Requirements

### Requirement: One caching primitive, and no module-local Caffeine builder or cache SPI
A module SHALL declare a `CacheDefinition` bean and resolve it from `LudwigCacheRegistry`. It
SHALL NOT build a `Caffeine` builder of its own and SHALL NOT declare a cache SPI.

#### Scenario: A module needs a cache
- **WHEN** a module needs to cache something
- **THEN** it declares a `CacheDefinition` — `AuthorityCaches`, `SettingsCaches` — and the
  deployment configures it under `ludwig.cache.caches.<name>`

#### Scenario: A module builds its own Caffeine cache
- **WHEN** a module constructs a `Caffeine` builder or introduces a second cache SPI
- **THEN** `architecture-rules`' `RuleGroup.CACHING` fails the build. One exemption is named in
  the rule itself: `export`'s `EnrichmentCache` is scoped to one report run, so sharing it would
  be a correctness change rather than a consolidation

### Requirement: Every cache declares a purpose, and the purpose is what the TTL means
A `CacheDefinition` SHALL declare a `CachePurpose`. `security` means the TTL is how long a
revoked grant keeps working; `performance` means the TTL is a throughput knob.

#### Scenario: A cache holds authorization grants
- **WHEN** a cache's contents decide what a caller is allowed to do
- **THEN** its purpose is `security`: the TTL defaults to 30s, is subject to the
  `ludwig.cache.security-ttl-ceiling` (2m) startup ceiling, and stale reads and the cluster-wide
  load lease are refused

#### Scenario: A cache holds user preferences
- **WHEN** a stale value means a user briefly sees the old timezone — a correctness wrinkle with
  no privilege attached
- **THEN** its purpose is `performance`: the TTL defaults to 5m, has no ceiling, and
  stale-while-revalidate and the load lease are available

#### Scenario: A module declares the wrong purpose
- **WHEN** a security-relevant cache is declared `performance`
- **THEN** **no tool detects it.** This is the one thing the module must get right and the only
  mistake it cannot detect for itself: the declaration is not inferable from the code. A shared
  default would make one of the two wrong whatever the number is — seconds wastes the settings
  cache, minutes silently lengthens a revocation window

### Requirement: A security cache refuses the mechanisms that work by serving stale
A `security` cache SHALL fail startup on a non-zero `stale-grace`, and SHALL refuse the
cluster-wide load lease. Jittered early refresh SHALL remain available to both purposes.

#### Scenario: A security cache is configured with a stale grace
- **WHEN** `stale-grace` is non-zero on a `security` cache
- **THEN** startup fails

#### Scenario: Stampede protection is needed on a security cache
- **WHEN** a `security` cache needs stampede protection
- **THEN** jittered early refresh is used and the load lease is not. Early refresh re-reads the
  store and replaces the entry **within** its TTL, so the oldest value any caller can see is
  still bounded by the TTL; the lease works by having the losing replica serve a value **past**
  its expiry, which is exactly what a revocation window forbids

### Requirement: The TTL is the backstop and eviction is the consistency mechanism
A TTL SHALL be required whatever the purpose, and eviction on commit SHALL be the primary means
of keeping a cache correct.

#### Scenario: An eviction event is dropped, or its consumer was down
- **WHEN** an eviction never arrives
- **THEN** the wrong value heals on its own at the TTL rather than persisting until the next
  restart. A cache with eviction and no TTL never heals from a missed event, which is why the
  TTL is required rather than optional

### Requirement: Expiry is expireAfterWrite and is not configurable
Entries SHALL expire a fixed interval after they are written, never after they are last
accessed.

#### Scenario: A busy caller reads a security cache entry continuously
- **WHEN** a key is accessed constantly
- **THEN** its entry still expires on schedule. Access-based expiry would let a busy caller hold
  a grant open indefinitely simply by calling often, and a window that resets on use is not a
  window

#### Scenario: A hot key in a performance cache misses its eviction
- **WHEN** an actively used key's eviction event is dropped
- **THEN** it still heals at the TTL. Under access-based expiry it never would — so the TTL
  would stop being a backstop for exactly the keys most likely to need one

### Requirement: The cache module stays free of in-repo dependencies
`cache-spring-boot-starter` SHALL have zero in-repo dependencies, as the
`module-dependency-direction` capability requires. It is restated here because it is what makes
this consolidation possible at all.

#### Scenario: The cache module is asked to audit an eviction
- **WHEN** a change would add a dependency on `audit-core`, `web-core` or `security`
- **THEN** `ModuleIndependenceTest` fails, and the fix is to drop the dependency rather than to
  move the cache module lower
