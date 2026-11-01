## Why

`replica-control-broadcast` makes a control reach every replica and report per replica. The other per-replica
controls that `admin-surface-follow-ups.md` §2 lists can now be offered honestly:

- forcing a configuration or secret reload, when a watcher missed a change or Vault rotated a secret early;
- dropping a minted outbound token, when the identity provider rotated keys or a client's scopes changed;
- seeing and setting circuit-breaker state, when a partner is back and the breaker is still open, or a partner is
  failing in a way the breaker has not caught.

Today each of these needs a rolling restart.

## What Changes

- **`hotreload` area** (`hot-reload-spring-boot-starter`):
  - `GET /admin/v1/hotreload/sources` (`hotreload.read`) lists reloadable sources: id, kind (file, Vault KV, Vault
    lease, template directory), and last successful reload as seen by the answering replica. Values are never
    shown.
  - The control `hotreloadreload` with `{source}` (`hotreload.operate`) reloads a source on every replica. Each
    replica's result is `reloaded`, `unchanged` or `rejected`; a rejected reload keeps the last-known-good value, as
    a watcher-triggered reload already does.
- **`restclient` area** (`rest-client-spring-boot-starter`):
  - `GET /admin/v1/restclient/clients` (`restclient.read`) lists named clients with their auth kind and resilience
    configuration.
  - The control `restclienttokenreset` with `{client}` (`restclient.operate`) drops a client-credentials client's
    cached token on every replica, so the next call mints a fresh one.
  - The control `restclientbreakers` (`restclient.read`) returns every circuit breaker's state and failure rate from
    each replica.
  - The control `restclientbreaker` with `{client, state}`, where `state` is one of `CLOSED`, `FORCED_OPEN` or
    `DISABLED` (`restclient.privileged`, reason required), sets a breaker on every replica. Forcing a breaker
    changes whether this service talks to a partner at all.
- Both areas are contributed only where `job-core`'s replica-control channel and the security starter's
  administrative surface are present. Both dependencies are optional.
- **Services**:
  - `notification-service` grants `hotreload.*` to `NOTIFICATION_ADMIN` and `hotreload.read` to
    `NOTIFICATION_SUPPORT`;
  - `crud-service-example` grants `restclient.*` and `hotreload.*` to `CATALOG_ADMIN`;
  - only areas a service actually runs are granted.

## Capabilities

### New Capabilities
- `config-reload-admin`: listing reloadable sources and reloading one on every replica.
- `outbound-client-admin`: inspecting named outbound clients, resetting their tokens, and reading or setting their
  circuit breakers on every replica.

### Modified Capabilities
- None. `outbound-token` is unchanged: a reset is an early expiry, after which the existing mint-once and
  renew-before-expiry behaviour applies.

## Non-goals

- Editing configuration values through the API.
- Bulkhead, rate-limiter or retry controls.
- Relay tokens. A relay token is the caller's own and is never cached.
- gRPC client channels, which are a candidate once `grpc-client` has its own admin area.

## Impact

- `hot-reload-spring-boot-starter` (library; parent `common`) gains optional dependencies on `job-core`,
  `security-spring-boot-starter` and `web-core-spring-boot-starter`. In-repo dependents:
  - `export-spring-boot-starter`
  - `notification-service`
  - `observability-spring-boot-starter`
  - `user-settings-spring-boot-starter`
- `rest-client-spring-boot-starter` (library; parent `common`) gains optional dependencies on `job-core` and
  `security-spring-boot-starter`. In-repo dependents:
  - `crud-service-example`
  - `export-spring-boot-starter`
  - `reconciliation-spring-boot-starter`
  - `test-support-local`
- `outbound-token-core` is unchanged; `CachingTokenSource.invalidate()` already exists.
- Services: `notification-service` and `crud-service-example`, through `application.yml`.
- Shared contracts: the new capabilities above.
- Module POM changes only; the manifest is rebuilt.
- READMEs (EN and RU), the admin guide, both OpenAPI documents, and `CHANGELOG` (EN and RU).
- Depends on `replica-control-broadcast` being applied first.
