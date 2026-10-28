## Purpose

Lets an operator see the caches a service runs and clear one on every replica, without restarting pods, when a
cached value is known to be wrong before its TTL would heal it.

## ADDED Requirements

### Requirement: Caches are listable
Where `job-core`'s administrative surface and the cache starter are both present, the service SHALL declare
`caches.read`, `caches.operate` and `caches.privileged`. `GET /admin/v1/caches` (`caches.read`) SHALL list every
registered cache with its name, owner, purpose, tier and TTL, and no entry values.

#### Scenario: An operator lists caches
- **WHEN** a caller holding `caches.read` lists the caches in notification-service
- **THEN** each registered cache appears once with its purpose and TTL, and no cached value appears

### Requirement: A cache can be cleared on every replica
The replica control `cacheevict` with argument `{cache}` SHALL clear every entry of the named cache on every
targeted replica, including the shared tier where the cache has one. It SHALL require `caches.operate` for a
`PERFORMANCE` cache and `caches.privileged` with a reason for a `SECURITY` cache. An unknown cache name SHALL be `400`.

#### Scenario: Clearing a performance cache
- **WHEN** an operator holding `caches.operate` submits `cacheevict` for a performance cache in a service with two
  replicas
- **THEN** the command succeeds with two acknowledgements, and the next read of any previously cached key on either
  replica is a miss

#### Scenario: Clearing a security cache needs the privileged tier
- **WHEN** a caller holding only `caches.operate` submits `cacheevict` for the authority cache
- **THEN** the response is `403`
