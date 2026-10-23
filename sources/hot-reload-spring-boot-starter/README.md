# hot-reload-spring-boot-starter

***English** · [Русский](README.ru.md)*

Configuration that changes without a restart: live-reloading property and YAML files, HashiCorp Vault
secrets (KV polling, lease-renewed dynamic secrets, Kubernetes auth), FreeMarker templates, and typed,
validated configuration objects that always hold the latest valid value. Environment variables and system
properties always override a reloaded value. Every reload is audited with secrets redacted.

| | |
|---|---|
| Role | starter |
| Depends on (in-repo) | [`audit-core`](../audit-core) |
| Used by | `export`, `observability`, `user-settings` starters; `notification-service` |
| Surfaces | none (reads files and Vault) |

| Feature | Package |
|---|---|
| Reload engine (diff, last-known-good on failure, listeners) | `core`, `core.watch` |
| Live property/YAML files | `properties`, `propertysource` |
| Typed, validated configuration | `binding` (`RefreshableConfig`, `HotReloadTypedConfigFactory`) |
| Vault secrets | `secrets` |
| FreeMarker templates | `template` |
| Reload notification | `event` (`ConfigurationRefreshedEvent`) |
| Audit | `audit` (`HotReloadAuditEntry`, `AuditingSourceChangeListener`) |
| Metrics | `metrics` |

## Usage

```xml
<dependency>
    <groupId>ru.ludwigandreas</groupId>
    <artifactId>hot-reload-spring-boot-starter</artifactId>
</dependency>
```

### Live files

```properties
ludwig.hotreload.files[0].path=/etc/config/app.properties
```

Every key in the file is a live Spring property (`@Value`, `Environment.getProperty`,
`@ConfigurationProperties`). Edits - including Kubernetes ConfigMap/Secret updates of a mounted volume - are
picked up automatically. The watcher watches the directory, so atomic symlink swaps (ConfigMaps, Vault
Agent) are handled.

**Precedence.** Reloaded sources sit at the **lowest** precedence: below `application.yml`, system
properties and environment variables. An explicit override (`MYAPP_MYSERVICE_NAME=local-override`) always
wins.

### Typed configuration that refreshes in place

```java
@Service
class MyService {

    private final RefreshableConfig<MyServiceProperties> config;

    MyService(HotReloadTypedConfigFactory typedConfigFactory) {
        this.config = typedConfigFactory.create("myapp.myservice", MyServiceProperties.class);
    }

    void doWork() {
        MyServiceProperties current = config.get(); // always the latest valid value
    }
}
```

`jakarta.validation` constraints on the class are checked on every reload; a reload that fails validation is
rejected and the previous value keeps serving. Add `spring-boot-starter-validation` (Hibernate Validator plus
the EL implementation; `hibernate-validator` alone fails with `HV000183`). Without a validator, binding works
and constraints are not checked.

### Vault on Kubernetes

1. **Vault Agent Injector (preferred where available).** The sidecar renders secrets to files on an
   `emptyDir`; point `ludwig.hotreload.files` at them. No Vault client, no network call from the application.
2. **Direct Vault API (`ludwig.hotreload.vault.*`).** For clusters without the injector, or for dynamic
   (leased) secrets. KV secrets are polled; dynamic secrets (database credentials, PKI) are renewed and
   rotated by `SecretLeaseContainer`. Vault values may not be present when the first beans are constructed
   (authentication is a network call), so read them through `RefreshableConfig`, or use pattern 1 if a value
   is needed from the first line of `main()`.

### FreeMarker

```properties
ludwig.hotreload.freemarker.template-directory=/etc/templates
```

With `freemarker` on the classpath, a `freemarker.template.Configuration` bean reloads a template the moment
its directory changes. If the application defines its own `Configuration`:

```java
@Bean
Configuration freeMarkerConfiguration(HotReloadableTemplateLoader templateLoader) {
    Configuration configuration = new Configuration(Configuration.VERSION_2_3_32);
    configuration.setTemplateLoader(templateLoader);
    templateLoader.bindTo(configuration); // required so invalidateAll() clears this Configuration's cache
    return configuration;
}
```

### Reload notifications

Every successful reload publishes a `ConfigurationRefreshedEvent`:

```java
@EventListener
void onConfigChanged(ConfigurationRefreshedEvent event) {
    log.info("{} changed: {}", event.getSourceId(), event.getChangedKeys());
}
```

[observability](../observability-spring-boot-starter#runtime-log-levels) uses this to apply `logging.level.*`
at runtime.

## Configuration

```properties
ludwig.hotreload.enabled=true

# Files
ludwig.hotreload.files[0].path=/etc/config/app.properties
ludwig.hotreload.files[0].key-prefix=
ludwig.hotreload.file-watch.debounce=500ms

# Vault
ludwig.hotreload.vault.enabled=true
ludwig.hotreload.vault.uri=https://vault.vault.svc:8200
ludwig.hotreload.vault.poll-interval=30s
# Kubernetes auth (in-cluster: exchanges the pod's service account JWT for a Vault token)
ludwig.hotreload.vault.kubernetes.role=my-app
ludwig.hotreload.vault.kubernetes.auth-path=kubernetes
# ludwig.hotreload.vault.kubernetes.service-account-token-file defaults to the projected token path
# Static token auth - local development only
# ludwig.hotreload.vault.token=...
# Static KV secret (v1 or v2), re-read every poll-interval
ludwig.hotreload.vault.secrets[0].mount=secret
ludwig.hotreload.vault.secrets[0].path=myapp/config
ludwig.hotreload.vault.secrets[0].kv-version=2
ludwig.hotreload.vault.secrets[0].key-prefix=
# Dynamic secret, renewed/rotated through Vault leases
ludwig.hotreload.vault.dynamic-secrets[0].path=database/creds/my-role
ludwig.hotreload.vault.dynamic-secrets[0].rotating=true
ludwig.hotreload.vault.dynamic-secrets[0].key-prefix=db.

# FreeMarker
ludwig.hotreload.freemarker.template-directory=/etc/templates

# Audit
ludwig.hotreload.audit.enabled=true
ludwig.hotreload.audit.actor=            # default: local hostname

# Metrics
ludwig.hotreload.metrics.enabled=true
```

## Deployment

- **Files**: mount ConfigMaps/Secrets as volumes (not `subPath` mounts, which Kubernetes does not update).
- **Vault, Kubernetes auth**: a Vault role bound to the service account, with policies for the listed paths;
  the projected service-account token mounted (default path).
- **Vault, static token**: local development only; never in a deployed profile.
- Keep secrets out of `application.yml` and the image; put them in Vault or a mounted Secret.

## Observability

**Metrics** (when Micrometer is present):

| Metric | Type | Tags | Meaning |
|---|---|---|---|
| `ludwig.hotreload.reload.succeeded` | counter | `source` | Successful reloads |
| `ludwig.hotreload.reload.failed` | counter | `source`, `exception` | Failed reloads (last-known-good keeps serving) |
| `ludwig.hotreload.reload.changed.keys` | summary | `source` | Keys changed per reload |
| `ludwig.hotreload.reload.seconds.since.last.success` | gauge | `source` | Staleness of a source. **The one to alert on**: a failing source is silent by design |

**Logs.** `INFO Source '<id>' <action> (<n> key(s) changed)`. `WARN` when a source reports a failure
(keeping last known good), when a typed configuration fails validation (keeping the previous value), when the
initial load of a file fails, and on Vault lease errors. `ERROR` when a listener throws.

**Recommended alerts:**

| Condition | Why |
|---|---|
| `ludwig_hotreload_reload_seconds_since_last_success` > expected change interval + poll interval, for Vault sources | Source stopped refreshing; secrets may expire |
| `increase(ludwig_hotreload_reload_failed_total[15m]) > 0` | A bad edit or an unreachable Vault |

## Audit

| Field | Applied | Failed |
|---|---|---|
| category | `config` | `config` |
| action | `config.reloaded` | `config.reload-failed` |
| actor | this instance (hostname or `ludwig.hotreload.audit.actor`), principal type `INSTANCE` | same |
| outcome | `SUCCESS` | `FAILURE` |
| attributes | `changes` (`key → {old, new}`), sorted `changedKeys` | the error |

- **One reload = one event**, with all changed keys.
- The actor is the instance that **applied** the change, not the person who made it. Upstream authorship is in
  Vault's audit device or the ConfigMap's git history.
- **Secret values are redacted before the event exists**: every Vault-sourced key (provenance `vault:` /
  `vault-lease:`) and every key whose name matches a secret pattern is `***REDACTED***`. Extend with
  `ludwig.audit.redaction.*` (see [audit-core](../audit-core#redaction)).
- `ludwig.hotreload.audit.enabled=false` disables this module's audit events.

## Runbook

| Symptom | Likely cause | What to do |
|---|---|---|
| Edited ConfigMap not picked up | `subPath` mount, or the key is overridden by an env var / `application.yml` | Mount the whole volume; check precedence (`/actuator/env` is not exposed - inspect the deployment's env) |
| `Refresh of configuration ... failed validation` | Invalid value in the source | Fix the source; the previous value is still in use |
| `seconds_since_last_success` growing for a Vault source | Vault unreachable, token/role expired, policy changed | Check Vault connectivity and the role binding; the last secret keeps being used until its lease expires |
| `Vault lease error` | Dynamic secret lease could not be renewed or rotated | Check the Vault secrets engine; credentials expire at lease end |
| Template change not visible | Own `Configuration` bean not bound to the loader | Call `templateLoader.bindTo(configuration)` |
| Value missing at startup from Vault | Read before Vault authentication completed | Use `RefreshableConfig` or the Vault Agent file pattern |

## Testing

Unit tests only, no Docker: reload coordination and last-known-good, property and YAML sources, file watching
(`FileResourceWatcherTest`, `PollingResourceWatcherTest`), environment bridging, typed binding and validation,
audit entry redaction, metrics, auto-configuration.

**Not covered by tests:** the Vault sources (KV polling, dynamic secret leases, Kubernetes auth) have no
automated test. Verify Vault changes manually against a real Vault before release.

## Design decisions

- **Lowest precedence** for reloaded values: externally managed config must never silently mask an explicit
  override.
- **Last-known-good**: a broken edit never replaces a working value.
- **Redact at construction**: the audit sink can never receive a secret.
- **Not a Vault audit log**: the module records who applied a change, not who made it.
