# cache-spring-boot-starter

***English** · [Русский](README.ru.md)*

The platform's caching primitive: named, typed caches declared by the module that owns them and
configured under one prefix by the deployment that runs them. A local Caffeine tier with per-key
coalescing and jittered early refresh; an opt-in shared Redis tier with a three-part eviction and
versioned, shape-checked key namespaces; negative caching that separates *absent* from *failed*;
after-commit eviction; and per-cache metrics including hit ratio.

> **Every named cache declares what its TTL means.** `purpose: security` - the TTL is how long a revoked
> grant keeps working: a ceiling, no stale reads. `purpose: performance` - the TTL is how long a user sees
> an old value: minutes, stale-while-revalidate allowed. Choosing the purpose is the one decision this
> module cannot check for you.

| | |
|---|---|
| Role | starter |
| Depends on (in-repo) | nothing (test: `test-support`) |
| Used by | `pat`, `security`, `user-settings` starters |
| Surfaces | none (optional Redis) |

```
ru.ludwigandreas.cache
├── api/      LudwigCache, LudwigCacheRegistry, CacheDefinition, CacheLoad, CacheLoader, CachePurpose, CacheTier, CacheSettings
├── core/     DefaultLudwigCache, CacheEntry, NoopLudwigCache, CacheRefreshExecutor
├── shared/   SharedCacheTier, ValueShape, RedisSharedCacheTier, ...
├── config/   CacheProperties, CacheSettingsResolver, CacheConfigurationValidator, auto-configurations
├── metrics/  CacheMetrics, MicrometerCacheMetrics
├── tx/       AfterCommitEviction
├── error/    CacheConfigurationException, CacheLoadException, UnknownCacheException
└── test/     LudwigCacheTestSupport - a real cache without a Spring context (ships in the main jar)
```

## Usage

```xml
<dependency>
    <groupId>ru.ludwigandreas</groupId>
    <artifactId>cache-spring-boot-starter</artifactId>
</dependency>
```

### Declare a cache (module author)

```java
public final class AuthorityCaches {

    public static final String NAME = "authorities";

    public static CacheDefinition<PrincipalRef, Authorities> definition() {
        return CacheDefinition.<PrincipalRef, Authorities>named(NAME, CachePurpose.SECURITY)
                .owner("security-spring-boot-starter")
                .defaultTtl(Duration.ofSeconds(60))
                .defaultMaximumSize(10_000)
                .valueType(Authorities.class)
                .keyRenderer(ref -> ref.type().name() + ':' + ref.subject())
                .build();
    }
}

@Bean
CacheDefinition<PrincipalRef, Authorities> ludwigAuthorityCacheDefinition() {
    return AuthorityCaches.definition();
}

@Bean
LudwigCache<PrincipalRef, Authorities> ludwigAuthorityCache(
        LudwigCacheRegistry registry, CacheDefinition<PrincipalRef, Authorities> definition) {
    return registry.cache(definition);
}
```

- **Value type and key renderer are required even for a local-only cache**, so moving a cache to the shared
  tier is a YAML change, not a code change.
- The key renderer must be **total and injective**: two keys rendering the same string would serve one
  caller's value to another. There is no default (`toString` is not stable across releases).
- A definition with no `ludwig.cache.caches.<name>` block works on its defaults; a block that matches no
  declaration **fails startup** (the typo case).

### Read

```java
Optional<Authorities> authorities = cache.get(ref, key ->
        CacheLoad.present(resolver.resolve(key)));
```

The loader states what happened:

```java
public sealed interface CacheLoad<V> {
    record Present<V>(V value) implements CacheLoad<V> { }
    record Absent<V>()        implements CacheLoad<V> { }   // cacheable briefly, if negative caching is on
    record Failed<V>(Throwable cause) implements CacheLoad<V> { }   // never cached
}
```

A thrown exception is `Failed`. `CacheLoad.ofOptional(...)` adapts a repository returning `Optional`.
Caching a failure would turn a two-second blip into an outage for the unlucky keys and suppress the probe
that would notice recovery. Negative caching is **off by default**: the caller who notices a wrong
"absent" is usually the one who just created the row.

### Evict after commit

```java
cache.evictAfterCommit(subject);    // one key
cache.evictAllAfterCommit();        // a role-, tenant- or platform-scoped write
```

Evicting inside the transaction lets another thread re-read the pre-commit row and repopulate a stale
entry; on rollback it discards a correct value for nothing.

**The TTL is the backstop, eviction is the consistency mechanism.** A TTL is required for every cache so
that a missed eviction (dropped event, consumer down) heals on its own. Expiry is always
`expireAfterWrite`: access-based expiry would let a busy caller keep a revoked grant alive forever, and keep
hot keys from ever expiring.

### Purposes

| | `purpose: security` | `purpose: performance` |
|---|---|---|
| default TTL | 30s | 5m |
| TTL ceiling | `ludwig.cache.security-ttl-ceiling` (2m); above it **startup fails** | none |
| stale reads past TTL (`stale-grace`) | refused at startup | allowed |
| cluster-wide load lease | refused (it serves stale) | allowed |
| jittered early refresh | allowed (reloads within the TTL) | allowed |

A deployment may override a module's `security` purpose to `performance`; it is allowed and logged at
`WARN`, because it removes the ceiling on a cache whose author called its TTL a revocation window.

### Tiers

```yaml
ludwig.cache.caches.<name>.tiers: [local]            # default
ludwig.cache.caches.<name>.tiers: [local, shared]    # opt-in; also needs ludwig.cache.tiers.shared.enabled
```

`local` is always present. The shared tier is worth it for genuinely expensive loads (aggregates, remote
partner lookups, cold-start warm-up), not for a primary-key read from a database that is already shared.
Two switches are required so that a cache block copied from a laptop into a Helm chart does not silently
start using Redis.

**Shared reads and writes are best-effort**: an unreachable Redis is counted, logged once per cooldown and
bypassed. Only eviction reports failure.

**Eviction with a shared tier** has three parts:

1. evict local on this replica - always, regardless of 2;
2. delete the shared entry, with immediate retries (no backoff - it runs on the request thread) and a metric
   on failure. **A failed shared eviction leaves every replica serving the stale value**;
3. publish an invalidation on Redis pub/sub so other replicas drop their local copies. On by default; with
   `shared.invalidation: false` the cache TTL must be ≤ `tiers.shared.local-ttl-cap-without-invalidation`.

**Key namespaces:** `ludwig:{app}:{namespace}:v{version}:{key}`, plus `:__shape` (value-type fingerprint)
and `:__lease:{key}`. `{app}` is `spring.application.name` (required for the shared tier). On first use a
cache records `ValueShape.of(valueType)` with `SET NX`; on later boots a different shape under the same
version **fails startup**, naming the cache and the property to bump. Bump
`key-namespace.version` whenever the cached value's shape changes. An unreachable Redis at startup leaves
the tier *unverified*: it neither reads nor writes until verification succeeds on use.

### Stampede protection

- **Per-key coalescing, always**: concurrent misses for one key run the loader once.
- **Jittered early refresh** (on by default): past `threshold` × TTL an entry becomes a refresh candidate
  with probability rising to 1 at expiry, so replicas reload at different moments. Runs on a small bounded
  pool that discards when full; a failed refresh keeps the existing entry.
- **Cluster-wide load lease** (opt-in, shared tier, `performance` only, needs `stale-grace`): `SET NX PX`;
  the loser serves the stale value instead of blocking. Not `job-core`'s `RunLock` - a database round trip
  per miss would cost more than the load.

### Eviction by predicate

`evictByScan(Predicate<K>)` is O(n) and named so. A cache must opt in (`requiresScanEviction(true)` in the
definition or `scan-eviction: true`), and it is **refused with the shared tier** (Redis would need a
blocking `KEYS` sweep or a secondary index).

### Testing a collaborator

`LudwigCacheTestSupport` builds a real cache without Spring, so unit tests can tell a hit from a miss
without mocking the cache or booting a context.

## Configuration

```yaml
ludwig:
  cache:
    enabled: true                       # off registers NoopLudwigCache for every cache
    security-ttl-ceiling: 2m
    refresh:
      pool-size: 2
      queue-capacity: 1000              # bounded, discarding
    tiers:
      shared:
        enabled: false                  # Redis; the first of two switches
        local-ttl-cap-without-invalidation: 60s
    caches:
      authorities:
        enabled: true
        purpose: security
        ttl: 30s
        maximum-size: 10000
        tiers: [local, shared]
        key-namespace: { name: authorities, version: 3 }
        negative: { enabled: true, ttl: 5s }
        shared: { invalidation: true, eviction-retries: 2 }
      settings:
        purpose: performance
        ttl: 5m
        maximum-size: 50000
        tiers: [local]
        scan-eviction: true
        key-namespace: { name: settings, version: 7 }
        stampede:
          early-refresh: { enabled: true, threshold: 0.75 }
          stale-grace: 30s
          lease: { enabled: false, ttl: 5s }
```

Unset fields fall back to the owning module's declaration, then to the purpose's default. Caches declared
in the platform: `authorities` and `pat-introspection` (security starter, `security`), `pat-verification`
(pat starter, `security`), `settings` (user-settings, `performance`).

**Refused at startup** (all problems reported at once):

| Refused | Because |
|---|---|
| `security` TTL above the ceiling | a revoked role would keep working with nothing reporting it |
| `security` with non-zero `stale-grace` | stale reads past the TTL break the revocation window |
| `scan-eviction` with the shared tier | needs `KEYS` or a secondary index |
| `shared` in `tiers` without `tiers.shared.enabled` | the second switch |
| shared tier without `spring.application.name` | two services would share a key space |
| `shared.invalidation: false` with TTL above the cap | that TTL becomes the only bound on other replicas' stale copies |
| lease without shared tier / with `security` / without `stale-grace` / with TTL ≥ cache TTL | the lease would be meaningless or harmful |
| negative TTL longer than the value TTL | an absence is the answer most likely to stop being true |
| early-refresh threshold outside `(0, 1)` | degenerate window |
| a `caches.<name>` block no module declares | it would bind and be ignored |

## Deployment

Local-only caches need nothing. For the shared tier: a Redis reachable through `spring.data.redis.*`,
pub/sub allowed (invalidation channel), `spring.application.name` set, and both switches on. Size Redis
memory for `maximum-size` × entry size × number of shared caches. Redis credentials come from the secret
store.

**Rolling deploys** that change a cached value's type must bump `key-namespace.version`, or the new pods
fail startup with a shape mismatch (old pods keep running; nothing serves a wrong shape).

## Observability

Caffeine statistics (`recordStats()`) are on for every cache and bound per cache through
`CaffeineCacheMetrics` (`cache.gets`, `cache.puts`, `cache.evictions`, `cache.size`, load timings). Every
meter below carries `cache` and `purpose` tags, so dashboards can separate security from performance caches.

| Metric | Type | Extra tags | Meaning |
|---|---|---|---|
| `ludwig.cache.hits` | counter | `tier` | Hits by tier (a shared hit costs a round trip) |
| `ludwig.cache.misses` | counter | | Misses |
| `ludwig.cache.loads` | timer | `outcome` (`present`, `absent`, `failed`) | Loader results and load duration |
| `ludwig.cache.negative.hits` | counter | | Served "absent" from the negative cache |
| `ludwig.cache.early.refreshes` | counter | | Early refreshes triggered |
| `ludwig.cache.lease.lost` | counter | `served` | Lease lost; whether stale was served |
| `ludwig.cache.shared.eviction.failures` | counter | | **Every replica now serves a stale value. Alert on any** |
| `ludwig.cache.shared.errors` | counter | `operation` | Shared tier degraded to local |
| `ludwig.cache.shared.shape.mismatches` | counter | | Key version not bumped after a shape change |
| `ludwig.cache.shared.available` | gauge | | `0` while the tier is unreachable or unverified |
| `ludwig.cache.maximum.size` | gauge | | Configured ceiling; compare with `cache.size` |

**Logs.** `INFO` per cache at startup (TTL, size, tiers) and the validation summary. `WARN` for a shared tier
falling back to local, an unreadable shared entry, an unverified namespace at startup, and a security cache
overridden to performance. `ERROR` for a failed shared eviction and for a shape mismatch.

**Recommended alerts:**

| Condition | Why |
|---|---|
| `increase(ludwig_cache_shared_eviction_failures_total[5m]) > 0` | Fleet-wide stale values until TTL |
| `ludwig_cache_shared_available == 0` for > 5 min | Running local-only; load on the backing store rises |
| `cache_size / ludwig_cache_maximum_size ≈ 1` sustained | Undersized cache |
| Hit ratio of a cache drops sharply | Eviction storm or key-renderer change |

## Audit

Not applicable: caching changes no business state. Evictions are triggered by audited writes in the owning
modules.

## Runbook

| Symptom | Likely cause | What to do |
|---|---|---|
| Startup fails: `CacheConfigurationException` listing problems | One of the refused combinations, or a `caches.<name>` typo | Fix every listed item; the message names the cache and property |
| Startup fails: key namespace version already recorded against a different shape | Value type changed, version not bumped | Increase `ludwig.cache.caches.<name>.key-namespace.version` |
| Revoked role still works | Security cache TTL; missed eviction | Wait ≤ TTL; to force: restart pods (local tier) and delete `ludwig:<app>:authorities:*` keys if shared |
| Newly created item reported "not found" | Negative cache | Wait the negative TTL; evict the key after commit on create |
| `ludwig_cache_shared_eviction_failures_total` > 0 | Redis unreachable at eviction time | Restore Redis; stale entries heal at TTL, or delete the affected keys manually |
| High latency on cache misses for one key | Loader slow; coalescing makes callers wait for one load | Fix the backing store; consider early refresh or a longer TTL (performance caches only) |

## Testing

- Unit (no Docker): loading, eviction (including **no eviction on rollback**), coalescing (16 concurrent
  misses → one load), validator, settings resolution, auto-configuration, `ValueShape`.
- `SharedTierIntegrationTest` (Docker, digest-pinned Redis from `test-support`): two-tier eviction failure
  path, key versioning and shape mismatch at startup.
- `ModuleIndependenceTest`: no dependency on any other platform module; public API does not expose Redis
  types.

## Design decisions

- **Zero in-repo dependencies.** `security-spring-boot-starter` depends on this module; any dependency here on
  audit-core, web-core or security would create a cycle. `ModuleIndependenceTest` enforces it.
- **One primitive, enforced.** `architecture-rules` group `CACHING`: `caching:no-private-caffeine-cache`
  (only this module depends on Caffeine's builder; one exemption, export's per-run `EnrichmentCache`) and
  `caching:no-second-cache-spi`.
- **Not caches here:** export's `EnrichmentCache` (scoped to one report run - sharing it would mix data from
  different points in time), rest-client's `CachedToken` (refresh-before-expiry semantics), idempotency's
  `CachedBodyRequest` (a request wrapper).
- **Explicit key version, checked against a fingerprint**, rather than a version derived from the fingerprint:
  a cosmetic rename should not trigger a fleet-wide cold start nobody chose.
- **No per-module no-op caches.** `enabled: false` is the off switch.
