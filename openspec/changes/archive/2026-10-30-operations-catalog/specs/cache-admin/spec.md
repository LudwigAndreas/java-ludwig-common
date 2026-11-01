## MODIFIED Requirements

### Requirement: Caches are listable
Where `job-core`'s administrative surface and the cache starter are both present, the service SHALL declare
`cache.definition.read` (`READ`), `cache.performance.evict` (`CHANGE`) and `cache.security.evict` (`PRIVILEGED`),
on the `ADMIN` surface. `GET /admin/v1/caches` (`cache.definition.read`) SHALL list every
registered cache with its name, owner, purpose, tier and TTL, and no entry values.

#### Scenario: An operator lists caches
- **WHEN** a caller holding `cache.definition.read` lists the caches in notification-service
- **THEN** each registered cache appears once with its purpose and TTL, and no cached value appears

### Requirement: A cache can be cleared on every replica
The replica control `cacheevict` with argument `{cache}` SHALL clear every entry of the named cache on every
targeted replica, including the shared tier where the cache has one. It SHALL require `cache.performance.evict` for a
`PERFORMANCE` cache and `cache.security.evict` with a reason for a `SECURITY` cache. An unknown cache name SHALL be `400`.

#### Scenario: Clearing a performance cache
- **WHEN** an operator holding `cache.performance.evict` submits `cacheevict` for a performance cache in a service with two
  replicas
- **THEN** the command succeeds with two acknowledgements, and the next read of any previously cached key on either
  replica is a miss

#### Scenario: Clearing a security cache needs its own operation
- **WHEN** a caller holding only `cache.performance.evict` submits `cacheevict` for the authority cache
- **THEN** the response is `403`
