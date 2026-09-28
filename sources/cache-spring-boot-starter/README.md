# cache-spring-boot-starter

**The platform's one caching primitive**: named, typed caches declared by the module that owns them and
configured by the deployment that runs them, with a local Caffeine tier, an optional shared Redis tier,
per-key stampede coalescing, jittered early refresh, negative caching that distinguishes *absent* from
*failed*, after-commit eviction, versioned shared key namespaces and per-cache Micrometer metrics.

The one thing to take from this README, if you take nothing else:

> **Every named cache declares what its TTL *means*.** `purpose: security` says the TTL is how long a
> revoked grant keeps working. `purpose: performance` says it is how long a user sees an old timezone.
> The two are governed differently - a ceiling and no stale reads for the first, minutes and
> stale-while-revalidate for the second - and getting it wrong is the one mistake this module cannot
> detect for you.

---

## Contents

- [The evidence: the same class, written twice](#the-evidence-the-same-class-written-twice)
- [One TTL, two meanings](#one-ttl-two-meanings)
- [The API](#the-api)
- [Negative caching, and why it shapes the API](#negative-caching-and-why-it-shapes-the-api)
- [Eviction, and the three parts of a two-tier eviction](#eviction-and-the-three-parts-of-a-two-tier-eviction)
- [The two tiers, and why local-only is the default](#the-two-tiers-and-why-local-only-is-the-default)
- [Key namespaces and versioning](#key-namespaces-and-versioning)
- [Stampede protection](#stampede-protection)
- [Eviction by predicate, and why it is refused with the shared tier](#eviction-by-predicate-and-why-it-is-refused-with-the-shared-tier)
- [Metrics](#metrics)
- [Configuration](#configuration)
- [What this module deliberately does not absorb](#what-this-module-deliberately-does-not-absorb)
- [Module structure, and the zero-dependency rule](#module-structure-and-the-zero-dependency-rule)
- [The ArchUnit rule that stops the third copy](#the-archunit-rule-that-stops-the-third-copy)
- [Migrating a module onto a named cache](#migrating-a-module-onto-a-named-cache)
- [Tests](#tests)

---

## The evidence: the same class, written twice

Two modules had independently produced the identical triad, differing only in their key and value types:

| | `user-settings` | `security` |
|---|---|---|
| SPI | `SettingsCache` | `AuthorityCache` |
| local implementation | `CaffeineSettingsCache` | `CaffeineAuthorityCache` |
| off switch | `NoopSettingsCache` | `NoopAuthorityCache` |
| constructor | `(Duration ttl, long maximumSize)` | `(Duration ttl, long maximumSize)` |
| operations | `get`, `get(k, loader)`, `put`, `evict`, `evictAll` (+ a coarse `evict(PrincipalRef)`) | `get`, `get(k, loader)`, `put`, `evict`, `evictAll` |
| expiry | `expireAfterWrite`, with a Javadoc paragraph on why not `expireAfterAccess` | `expireAfterWrite`, with a Javadoc paragraph on why not `expireAfterAccess` |
| stampede | `default get(k, loader)` is the naive uncoalesced form; the Caffeine subclass overrides it | identical, in nearly the same words |

Both Javadocs even made the thundering-herd argument the same way: *"every expiry of a hot subject releases
one lookup per in-flight request at once - a self-inflicted thundering herd against the role store, arriving
exactly when traffic is highest."* `user-settings`' `AfterCommitEviction` was the third piece, and
`identity-projection` registered its own `TransactionSynchronization` for the same purpose in
`IdentityProjectionService` - so after-commit eviction had been written twice too.

Both were reasonable local decisions. A module needed a cache, Caffeine is the obvious answer, and nobody was
in a position to see the other one.

**What the duplication cost was not the lines:**

- neither implementation called `recordStats()`, so **hit ratio was unobservable across the whole
  platform** - for both of the caches every request went through;
- neither had negative caching, cross-replica stampede protection, or a key namespace;
- the one difference between them that genuinely mattered - one TTL was a revocation window, the other a
  throughput knob - existed only in prose, in two files nobody read together.

Nothing owned any of it, so the next module would have written it a third time.

## One TTL, two meanings

`SettingsCache`'s Javadoc had already drawn the distinction, and it is the most important thing in this
module:

> `AuthorityCache`'s TTL is a security window: it is how long a revoked role keeps working, which is why
> that one is measured in seconds and why its Javadoc says sizing it is a security decision. **This
> cache's TTL is a performance decision, not a security window.** A stale setting means a user briefly
> sees the old timezone - a correctness wrinkle with no privilege attached.

A shared module with one default TTL makes one of those two wrong whatever the number is: seconds wastes the
settings cache, minutes silently lengthens a revocation window. So the meaning is declared, the default
follows from it, and the checks that only make sense for one of the two apply only to that one.

| | `purpose: security` | `purpose: performance` |
|---|---|---|
| default TTL | 30s | 5m |
| TTL ceiling | `ludwig.cache.security-ttl-ceiling` (2m), **fails startup** above it | none |
| stale reads past the TTL | **refused** - startup fails on a non-zero `stale-grace` | available |
| cluster-wide load lease | **refused** - it works by serving stale | available |
| jittered early refresh | **available** - it reloads *inside* the TTL | available |

The last row is worth its own sentence, because the two stampede answers are not equivalent here. An early
refresh re-reads the store and replaces the entry **within** its TTL, so the oldest value any caller can see
is still bounded by the TTL. The lease works by having the losing replica serve a value **past** its expiry,
which is exactly what a revocation window forbids.

### The TTL is not the consistency mechanism

Eviction is. `SettingsCache` said it plainly: *"the TTL is the backstop for the case those miss - a dropped
event, a consumer that was down - so that a wrong value heals on its own instead of persisting until the next
restart."*

So "eviction on commit rather than TTL guessing" is right in emphasis and wrong if taken literally: it is
eviction on commit **with the TTL as the backstop**. A cache with eviction and no TTL never heals from a
missed event, which is why a TTL is required rather than optional whatever the purpose.

### Expiry is `expireAfterWrite`, and that is not configurable

Both deleted caches had reached this independently and both had written down why, in different words:

- for a security purpose, access-based expiry lets a busy caller **hold a grant open indefinitely** simply
  by calling often. A window that resets on use is not a window;
- for a performance purpose, an actively used key's entry would never expire - so the TTL would stop being a
  backstop for a missed eviction for exactly the keys most likely to notice one.

## The API

A module declares its cache once, as a bean, and resolves it from the registry.

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
```

```java
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

Reading is one call, and the loader form is the one to use:

```java
Optional<Authorities> authorities = cache.get(ref, key ->
        CacheLoad.present(resolver.resolve(key)));
```

### Why a declaration rather than a name

The configuration model is `rest-client-spring-boot-starter`'s, deliberately: a map of named things under
one prefix, where the name is the lookup key, the meter tag and the string in every log line. The property
that makes that model work is that **a typo fails at startup rather than producing a silently unconfigured
instance**, and it must carry over. Both directions are checked:

- a definition with no `ludwig.cache.caches.<name>` block works, on the module's defaults - a service should
  not have to write YAML to get a working authority cache;
- a `ludwig.cache.caches.<name>` block that matches **no** declaration **fails startup**. That is the typo
  case, and without the check it is completely silent: `authoritys:` binds, nothing reads it, the cache runs
  on its defaults, and the operator concludes the TTL they set has no effect on anything.

### Why the value type and the key renderer are required even for a local-only cache

Caffeine holds object references and needs neither; only the shared tier uses them. They are required anyway
so that **switching a cache to `tiers: [local, shared]` is a change to one YAML file and not a change to the
module that owns the cache.** A definition that could not be promoted without a code change would make the
shared tier an architectural decision instead of an operational one.

The key renderer must be **total and injective**. Two distinct keys rendering the same string would serve one
caller's value to another, and the shared tier has no way to notice. There is deliberately no default
renderer: `Object#toString` is not required to be stable across releases, and one that worked until somebody
added a field to a record is worse than none.

## Negative caching, and why it shapes the API

Missing from both deleted caches, and the design needs one distinction to be useful rather than dangerous.

- A **"not found"** answer is a fact about the data and may be cached, on its own shorter TTL, so that a
  hammering lookup for a key that does not exist stops reaching the store.
- An **error** - a timeout, a connection refused, a 5xx - must **never** be cached. Caching a failure turns a
  two-second blip into a sustained outage for exactly the keys that were unlucky, and it is self-reinforcing:
  the negative entry suppresses the recovery probe that would have noticed the store came back.

A loader returning `Optional.empty()` for both makes that distinction unrepresentable, and therefore makes
the safe implementation impossible. So the loader says which, in its return type:

```java
public sealed interface CacheLoad<V> {
    record Present<V>(V value) implements CacheLoad<V> { }
    record Absent<V>()        implements CacheLoad<V> { }   // cacheable, briefly
    record Failed<V>(Throwable cause) implements CacheLoad<V> { }   // never cached
}
```

A loader that throws is treated exactly as `Failed`; an exception is already an unambiguous statement of
failure. `CacheLoad.ofOptional(…)` exists for the common case where a repository returns `Optional` and any
problem arrives as an exception, and it is a *named* factory so that the ambiguity cannot creep back in
through a shorter spelling.

Negative caching is **off by default**. A negative entry is a promise that a key does not exist, and the
caller who notices it was wrong is usually the one who just created the row.

## Eviction, and the three parts of a two-tier eviction

Eviction that follows a database write belongs **after the commit**, and `LudwigCache` has that as a method
so that nobody has to remember:

```java
cache.evictAfterCommit(subject);    // one key
cache.evictAllAfterCommit();        // a role-, tenant- or platform-scoped write
```

Evicting inside the transaction is the obvious implementation and it is wrong in both directions:

- it opens a window in which another thread misses, re-reads the row as it was **before** the uncommitted
  change, and repopulates the cache with the stale value - which then survives until the TTL, long after the
  write everybody believes took effect. The eager eviction does not merely fail to help; it actively creates a
  stale entry that would not otherwise exist;
- if the transaction rolls back, the eviction has still happened, so a correct cached value was discarded for
  nothing.

The rollback case is the one the tests pin, because eviction-on-commit-that-races is correct-but-wasteful and
eviction-on-rollback caches nothing and hides a bug.

### The three parts

With a shared tier, an eviction is three things, and all three are specified rather than assumed:

1. **evict local, on this replica** - unconditionally, and not contingent on (2) succeeding. This replica
   being right is worth having even when the others cannot be told;
2. **delete the shared entry**, with retries and **a metric on failure**
   (`ludwig.cache.shared.eviction.failures`). *Alert on it.* A shared eviction that did not happen leaves
   **every** replica serving the stale value - strictly worse than the single-tier case, where only the
   writing replica was wrong and every other replica's TTL was already independent and already ticking;
3. **tell the other replicas to drop their local copies**, on a Redis pub/sub channel. Deleting the shared
   entry does nothing about the local copies every other replica holds.

Part 3 is **on by default** and turning it off is allowed - and is not silent. Without the channel, the local
TTL stops being a backstop for a missed event and becomes the *only* bound on every other replica's stale copy
after a perfectly successful eviction. So a cache with `shared.invalidation: false` must keep its TTL at or
below `ludwig.cache.tiers.shared.local-ttl-cap-without-invalidation`, and startup fails otherwise. That is
what keeps the choice a choice rather than a five-minute fleet-wide stale read nobody decided on.

The retries carry **no backoff**, deliberately: the delete runs in `afterCommit` on the request thread, and a
backoff would hold a response open waiting for a Redis that is probably not coming back inside it. The retries
cover a connection reaped between the commit and the delete, which is the common case and is fixed by trying
again at once.

## The two tiers, and why local-only is the default

```
ludwig.cache.caches.<name>.tiers: [local]            # the default
ludwig.cache.caches.<name>.tiers: [local, shared]    # opt-in, and needs the second switch too
```

`local` is always present whether it is listed or not. A shared-only cache - a network round trip for a value
this process computed a millisecond ago - is not a cache, it is a second database, and letting the
configuration express one would be letting it express a mistake.

**The shared tier is opt-in per cache and off by default**, and the reason is not caution:

- most caches in this platform front a database that is already shared, so the shared tier saves a query the
  database was going to answer from its own buffer cache anyway;
- it adds a failure mode the single-tier arrangement does not have - see part 2 above.

It earns its keep for a genuinely expensive load: a report aggregate, a remote partner lookup, a cold-start
warm-up that would otherwise be paid once per replica per deploy. It does not for a single-row read by primary
key.

Reaching Redis needs **two** switches - `ludwig.cache.tiers.shared.enabled` and `shared` in the cache's
`tiers` - for the reason `rest-client`'s TLS switches are paired: a single per-cache line is one edit in one
YAML file, and YAML files get copied from a laptop into a Helm chart. A separate, differently-named top-level
switch means the copy does not silently start using Redis.

### Unavailable is not an error

Every shared read and write is best-effort. A Redis that cannot be reached makes the service slower, not
broken, so an unreachable tier is counted, logged once per cooldown, and bypassed in favour of the local
tier. The one operation that reports its failure upward is eviction, for the reason above.

## Key namespaces and versioning

Keys are:

```
ludwig:{app}:{namespace}:v{version}:{key}
```

plus two reserved siblings: `:__shape`, which records the value type's structural fingerprint, and
`:__lease:{key}`, the cluster-wide load lease. The `{app}` segment is `spring.application.name`, and a shared
tier without one **fails startup**: it is what stops two services sharing a Redis from sharing one key space
with two value types in it.

The version is a real rolling-deploy hazard made safe. v1 and v2 pods share one Redis; v2 changes the
serialized shape of a cached value; without a version a v1 pod reads a v2 entry and either throws or - worse -
deserializes into something subtly wrong, in a field that is silently absent.

Two things about it are worth knowing before deciding it is ceremony:

- **it only matters for the shared tier.** Local Caffeine holds object references in one process and is
  immune. Somebody whose local cache has never broken will conclude versioning is optional;
- **a hand-maintained integer that somebody forgets to bump is worse than no versioning at all**, because it
  converts a loud failure into a silent one: the reader is now confident it is looking at a compatible entry.

So forgetting is made **loud** rather than hoped against. On first use, a shared cache writes
`ValueShape.of(valueType)` - a fingerprint over the type's name and its sorted record components or fields,
recursing into this platform's own types and stopping at JDK and third-party ones - to `:__shape` with
`SET NX`. On every later boot the recorded fingerprint is compared with this process's, and a version reused
across a shape change **fails the context**, naming the cache, both fingerprints and the property to change.
The operator bumps the version, which is the fix they would have applied anyway, three weeks earlier.

Deriving the version *from* the fingerprint - no integer at all - was the other candidate and was rejected: it
makes every incidental refactor of a value type silently abandon a full cache, so a cosmetic field rename
becomes a fleet-wide cold start nobody chose. An explicit version that fails loudly when it is stale keeps the
decision with the person deploying.

An unreachable Redis at startup is **not** a startup failure. The tier stays *unverified*, which means it
neither serves nor stores - serving from an unverified namespace is the hazard the version exists to prevent -
the cache runs local-only, and the check is retried on use.

## Stampede protection

**Per-key coalescing is not optional**: it is how the cache loads, always. Concurrent misses for one key run
the loader once and the rest receive its result; the lock is per key, so different keys are unaffected. This is
lifted from both deleted implementations, including the arrangement where the interface's `default` method is
the naive uncoalesced form and the real implementation overrides it - a good pattern, because it makes the
uncoalesced path explicit rather than accidental.

What coalescing cannot solve: Caffeine computes at most once **per process**, so N replicas still issue N loads
on a cold key - simultaneously, because they all cached it during the same deploy or the same cold start.

Two answers, in the order to prefer them:

1. **Jittered early refresh** (`stampede.early-refresh`, **on by default**). Past a configurable fraction of
   the TTL an entry becomes a refresh candidate, with the probability rising from zero at the threshold to one
   at expiry. Each replica rolls independently, so the reloads spread across the tail of the TTL instead of
   landing together on its edge. It needs no coordination, adds no failure mode and nothing new that can be
   unavailable - and it addresses the actual cause, which is **synchronised expiry** rather than concurrency.
   The refresh runs on a small, bounded, **discarding** pool: a refresh nobody is waiting for must never queue
   indefinitely or run on the caller.
2. **A short cluster-wide load lease** (`stampede.lease`, opt-in, needs the shared tier). `SET NX PX`; the
   loser **serves the stale value rather than blocking**, and loads anyway if it has nothing stale, because
   blocking on a lease whose holder may have crashed converts a slow load into a stalled request. Refused for a
   security purpose, and refused with no stale grace to serve from - a loser that loads anyway is a Redis round
   trip per miss that changes nothing.

Note what the lease is deliberately **not**: `job-core`'s `RunLock`. That is a leased lock for scheduled work,
with a database round trip per acquisition; one per cache miss would cost more than the load it protects. It is
the obvious wrong move and somebody will suggest it.

A refresh that fails changes nothing: the existing entry stays, and the ordinary expiry path will load again. A
failure there must not evict - that would convert a transient store problem into a guaranteed synchronous miss
for the next caller, which is the opposite of the intent.

## Eviction by predicate, and why it is refused with the shared tier

`user-settings` needs it: a support path that holds a principal and no tenant has to evict that principal
across every tenant they are cached under. The previous API offered it as `evict(PrincipalRef)`, an
innocuous-looking overload sitting beside the O(1) `evict(SettingsSubject)`, with the cost buried in the
implementation's Javadoc - *"scans the key set, because Caffeine has one map and no secondary index."*

**A generic method that is secretly O(n) is a performance bug waiting for its second caller.** So:

- the operation is called `evictByScan(Predicate<K>)`. The cost is in the name;
- a cache must declare it - either the owning module says `requiresScanEviction(true)`, because it is a
  property of the code rather than of the deployment, or the deployment sets `scan-eviction: true`;
- **it is refused alongside the shared tier, at startup.** Eviction by predicate against Redis needs either a
  `KEYS` sweep - which blocks the single-threaded server for its whole duration and has taken production
  instances down - or a real secondary index, which is a second data structure to keep consistent with the
  first for the sake of one administrative call site. Refusing the combination is the honest third option, and
  refusing it *at startup* is what stops it working in the local-only environment it was written in and failing
  on the request that first needs it in production.

`user-settings` keeps a typed wrapper, `SettingsCaches.evictAcrossTenants(cache, ref)`, so the intent stays
readable and the reason the O(n) is acceptable lives in one place.

## Metrics

Caffeine's `recordStats()` is on, unconditionally, and `CaffeineCacheMetrics` is bound per named cache - which
is what finally makes hit ratio, load time, eviction count and size observable. None of it existed before this
module.

On top of what Caffeine can see:

| meter | why |
|---|---|
| `ludwig.cache.hits{tier}` | a shared hit and a local hit cost two different amounts |
| `ludwig.cache.misses` | with the hits, the ratio |
| `ludwig.cache.loads{outcome}` | what a miss actually costs, and how often it is `absent` or `failed` |
| `ludwig.cache.negative.hits` | how much the negative TTL is saving, and the first place to look when a caller says a new row is not visible |
| `ludwig.cache.early.refreshes` | whether the jitter is doing anything |
| `ludwig.cache.lease.lost{served}` | how often a replica served stale rather than loaded |
| `ludwig.cache.shared.eviction.failures` | **alert on this.** Every replica is now serving the stale value |
| `ludwig.cache.shared.errors{operation}` | the tier degraded to local-only |
| `ludwig.cache.shared.shape.mismatches` | non-zero during a rolling deploy means the key version was not bumped |
| `ludwig.cache.shared.available` | 0 while the tier is unreachable or unverified |
| `ludwig.cache.maximum.size` | the ceiling, to compare against Caffeine's size gauge - a cache permanently at it is undersized, and eviction pressure says so earlier and more clearly than the hit ratio |

Every meter carries `cache` and `purpose`. The purpose is a tag rather than only a configuration value so that
a dashboard can separate the two kinds of cache without a hard-coded list of names: a security cache's hit
ratio and a performance cache's hit ratio are not the same quantity and should not share an axis.

## Configuration

```yaml
ludwig:
  cache:
    enabled: true                       # master switch; off registers nothing at all
    security-ttl-ceiling: 2m            # the longest TTL a purpose=security cache may declare
    refresh:
      pool-size: 2
      queue-capacity: 1000              # bounded and discarding; a refresh is best-effort
    tiers:
      shared:
        enabled: false                  # Redis; the first of the two switches
        local-ttl-cap-without-invalidation: 60s
    caches:
      authorities:
        enabled: true
        purpose: security               # seconds-scale default, ceiling enforced, no stale reads
        ttl: 30s
        maximum-size: 10000
        tiers: [local, shared]
        key-namespace: { name: authorities, version: 3 }
        negative: { enabled: true, ttl: 5s }
        shared: { invalidation: true, eviction-retries: 2 }
      settings:
        purpose: performance            # minutes-scale default
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

Every field left unset falls back to the owning module's declaration, and then to the purpose's default. A
cache's own block is optional; a block for a cache nobody declared is a startup failure.

### What the startup validator refuses

All of these are relationships between two settings, which is why Bean Validation cannot express any of them
and why they are checked together, with every problem reported at once:

| refused | because |
|---|---|
| `purpose: security` with a TTL above the ceiling | nothing at runtime reports it; the symptom is a revoked role that keeps working |
| `purpose: security` with a non-zero `stale-grace` | a stale read past the TTL is what a revocation window forbids |
| `scan-eviction` with the shared tier | `KEYS` sweep or secondary index; neither is acceptable |
| `shared` in `tiers` without `tiers.shared.enabled` | the second switch exists so a copied YAML block does not silently reach Redis |
| the shared tier with no `spring.application.name` | two services would share one key space |
| `shared.invalidation: false` with a TTL above the cap | that TTL is now the only bound on every replica's stale copy |
| the lease without the shared tier | there is nowhere to take it |
| the lease with `purpose: security` | the loser serves stale |
| the lease with no `stale-grace` | the loser loads anyway; the round trip buys nothing |
| a lease TTL at or beyond the cache TTL | it would outlive the entry it protects |
| a negative TTL longer than the value TTL | an absence is the answer most likely to have just stopped being true |
| an early-refresh threshold outside `(0, 1)` | at 0 every read is a candidate; at 1 the window is empty |
| a `ludwig.cache.caches` block no module declares | it binds and is then ignored, so the settings silently do nothing |

Overriding a module's `purpose: security` down to `performance` is **allowed** - the module's declaration is a
default, and a deployment may know something the module does not - and is logged at WARN by the pod that does
it, because it removes the ceiling and permits stale reads on a cache whose author said its TTL is a
revocation window.

## What this module deliberately does not absorb

Three things in this repository look like caches and are not, or are and must not be shared. Stated here so
the next reader does not finish the job.

| | what it is | why it stays |
|---|---|---|
| `export/enrich/EnrichmentCache` | the values one report run has fetched | **Scoped to one run and bounded by it.** Giving it application-wide lifetime is a *correctness* change: a report is a point-in-time statement, two runs an hour apart must each see the partner's data as it was when that run started, and a shared cache would make the second one silently a mixture. It has no TTL at all by design - the run is the lifetime - so there is nothing for this module to govern. It already calls `recordStats()`. It is the one type named in the ArchUnit rule's exemption list |
| `rest-client/auth/CachedToken` | a single-value OAuth token holder | Not a keyed cache. The semantics it needs - refresh *before* expiry, never serve an expired token - are not cache semantics, and expressing them as a TTL would get the sign of the comparison wrong |
| `idempotency/web/CachedBodyRequest` | a servlet request wrapper | Not a cache at all, despite the name. It buffers one request body so it can be read twice |

## Module structure, and the zero-dependency rule

**One module, zero in-repo dependencies**, and that is load-bearing rather than tidy.
`security-spring-boot-starter` sits near the bottom of the reactor - identity-projection, user-settings,
web-core and every service depend on it - and it is one of the two modules whose hand-written cache this one
replaces. If this module ever acquired a dependency on `audit-core`, `web-core` or `security` itself, security
could not depend on it and the consolidation would have to be undone.

The failure mode is quiet and easy to reach: somebody wants to audit an eviction, or to render a cache error as
a `ProblemDetail`, and both are one import away and both look reasonable. `ModuleIndependenceTest` fails the
build on it, and says why, so that the right fix is obvious instead of "move the cache module".

```
ru.ludwigandreas.cache
├── api/         LudwigCache, LudwigCacheRegistry, CacheDefinition, CacheLoad, CacheLoader,
│                CachePurpose, CacheTier, CacheSettings
├── core/        DefaultLudwigCache, CacheEntry, NoopLudwigCache, CacheRefreshExecutor
├── shared/      SharedCacheTier, SharedCacheView, SharedEntry, ValueShape,
│                RedisSharedCacheTier, RedisSharedCacheView
├── config/      CacheProperties, CacheSettingsResolver, CacheConfigurationValidator,
│                DefaultLudwigCacheRegistry, LudwigCacheAutoConfiguration,
│                LudwigCacheRedisAutoConfiguration
├── metrics/     CacheMetrics, MicrometerCacheMetrics, NoopCacheMetrics
├── tx/          AfterCommitEviction
├── error/       CacheConfigurationException, CacheLoadException, UnknownCacheException
└── test/        LudwigCacheTestSupport - builds a real cache with no Spring context
```

`test/` ships in the main jar, the same arrangement `rest-client`'s `@LudwigRestClientTest` slice uses, so a
consuming module gets it with the starter. Before it existed, a module whose collaborator takes a cache had two
options in a unit test: mock the cache, which tests nothing about caching and hides the difference between a hit
and a miss, or boot a context, which is a hundred times slower than the thing under test. Both happened, in the
two modules this starter replaced.

Caffeine, `spring-tx` and `micrometer-core` are compile dependencies; `spring-data-redis` is optional. There is
deliberately **no `Noop` per module** any more: the two deleted always-miss implementations existed to survive
Caffeine being absent from the classpath, which a starter named "cache" cannot sensibly be. The off switch is
`enabled: false`, a configuration decision, served by one `NoopLudwigCache`.

Caffeine's version comes from `spring-boot-dependencies`, so it is not pinned in `ludwig-bom`.

## The ArchUnit rule that stops the third copy

`RuleGroup.CACHING`, on by default, in `architecture-rules`:

| rule | what it checks |
|---|---|
| `caching:no-private-caffeine-cache` | nothing outside `ru.ludwigandreas.cache..` depends on Caffeine's builder (one exemption, named in the rule: `EnrichmentCache`) |
| `caching:no-second-cache-spi` | no interface outside it is named like a cache **and** declares both a keyed read and an eviction |

Expressed as a *dependency* on `Caffeine` rather than as a call to `Caffeine.newBuilder()`, because the
dependency is the thing that cannot be hidden: a class reaching the builder through a static import, a helper or
a method reference still carries `Caffeine` in its constant pool.

What the rule deliberately does not check: whether a TTL is a sensible number, or whether a declared purpose is
the right one. Neither is expressible structurally - the TTL is configuration, and the purpose is a statement
about what the cached value *means*. They are covered where they can be: the TTL by this module's startup
validator, the purpose by the module author writing it down. This is the same division the repository already
draws - `architecture-rules` owns structure, `checkstyle-rules` owns source text, SonarQube owns bugs.

## Migrating a module onto a named cache

Both migrations are done; this is what they consisted of.

| step | `security` | `user-settings` |
|---|---|---|
| SPI | `AuthorityCache` **deleted** - nothing outside the module implemented it | `SettingsCache` **deleted** |
| implementations | `CaffeineAuthorityCache`, `NoopAuthorityCache` **deleted** | `CaffeineSettingsCache`, `NoopSettingsCache` **deleted** |
| what stayed | `AuthorityCaches` - the name, the purpose, the default TTL, the key renderer | `SettingsCaches`, plus `evictAcrossTenants` |
| purpose | `security` | `performance` |
| configuration | `ludwig.security.authorities.cache.*` → `ludwig.cache.caches.authorities.*` | `ludwig.user-settings.cache.*` → `ludwig.cache.caches.settings.*` |
| after-commit eviction | the hand-registered `TransactionSynchronization` in `IdentityProjectionService` → `cache.evictAfterCommit(ref)` | `AfterCommitEviction` moved here; four call sites → `cache.evictAfterCommit(subject)` / `evictAllAfterCommit()` |

The removed property blocks are replaced by a comment in each properties class naming the new key, so the
person who greps for the old one finds the answer rather than nothing.

## Tests

```
src/test/java/ru/ludwigandreas/cache/
├── unit/          CacheLoadingTest, CacheEvictionTest, StampedeTest,
│                  CacheConfigurationValidatorTest, CacheSettingsResolverTest,
│                  CacheAutoConfigurationTest, ValueShapeTest
├── integration/   SharedTierIntegrationTest - the only class that needs Docker
└── architecture/  ModuleIndependenceTest
```

The behaviours worth naming:

- **the security ceiling** - a `security`-purpose cache with a ten-minute TTL fails startup with a message
  naming the cache, and the same TTL on a `performance` cache is fine;
- **coalescing** - sixteen concurrent misses on one key invoke the loader once;
- **after-commit eviction** - an eviction inside a transaction that **rolls back** does not happen, and one in
  a transaction that commits does. The rollback case is the one that matters;
- **negative caching** - an absence is cached briefly; a **thrown** loader is not cached even on a cache with
  negative caching switched on, and the next call retries;
- **two-tier eviction** - a failed shared eviction increments the failure metric, does not throw, and the local
  eviction still happened. The test then goes on to show what the failure costs: with the tier reachable again,
  the next read promotes the surviving stale entry straight back, on the very replica that evicted it;
- **key versioning** - entries under `v1` are invisible to a cache configured at `v2`, against a real Redis;
  and reusing `v1` with a differently-shaped value type fails startup;
- **module independence** - nothing here depends on another module of this platform, and the public API does not
  reach the optional Redis types.

Only `SharedTierIntegrationTest` starts a container, and it is a digest-pinned Redis from `test-support`. Every
local-tier test runs without Docker, deliberately: a caching module whose whole suite needed Docker is a caching
module people stop running tests for.

```bash
mvn -pl cache-spring-boot-starter -am verify
# the consolidation, end to end, and the proof there is no reactor cycle
mvn -pl security-spring-boot-starter,user-settings-spring-boot-starter,identity-projection-spring-boot-starter -am verify
```
