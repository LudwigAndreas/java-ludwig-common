# hot-reload-spring-boot-starter

***English** · [Русский](README.ru.md)*

Enterprise-ready hot reload for Java 17 + Spring Boot services: typed and validated configuration,
live-reloading property/YAML files, FreeMarker templates and HashiCorp Vault secrets - with environment
variables and system properties always able to override a reloaded value. Add the dependency, point it
at your files and/or Vault paths, and bound configuration beans refresh themselves as sources change - no
restart, no redeploy.

## Quick start

```xml
<dependency>
    <groupId>ru.ludwigandreas</groupId>
    <artifactId>hot-reload-spring-boot-starter</artifactId>
    <version>1.0.0</version>
</dependency>
```

Hot-reload a properties or YAML file:

```properties
ludwig.hotreload.files[0].path=/etc/config/app.properties
```

Any key in that file is now a live Spring property - read it the normal way (`@Value`,
`Environment.getProperty(...)`, `@ConfigurationProperties`) and it reflects the file's current content.
Editing the file (or, in Kubernetes, the ConfigMap/Secret it's mounted from) is picked up automatically.

For a value that needs to refresh *without* restarting a `@ConfigurationProperties` bean, use
`RefreshableConfig` instead:

```java
@Service
class MyService {

    private final RefreshableConfig<MyServiceProperties> config;

    MyService(HotReloadTypedConfigFactory typedConfigFactory) {
        this.config = typedConfigFactory.create("myapp.myservice", MyServiceProperties.class);
    }

    void doWork() {
        MyServiceProperties current = config.get(); // always the latest valid value
        ...
    }
}
```

`MyServiceProperties` can carry `jakarta.validation` constraints (`@NotBlank`, `@Min`, ...) - a reload
that fails validation is rejected and the previous, valid value keeps serving.

## Why hot reload, and what "environment overrides" means here

Hot-reloaded values (files, Vault secrets) are registered at the **lowest** precedence in the
`Environment` - below `application.yml`/`.properties`, system properties and environment variables. This
is deliberate: hot reload exists for values that live outside your deployable (secrets, ops-managed
config), and none of them should be able to silently mask an explicit override you set for local
development or troubleshooting. If you export `MYAPP_MYSERVICE_NAME=local-override`, that value wins,
full stop, regardless of what a hot-reloaded file or Vault secret says.

## Features and where they live

| Feature | Package |
|---|---|
| Reload engine (diff, last-known-good on failure, listeners) | `core`, `core.watch` |
| Live property/YAML files | `properties`, `propertysource` |
| Typed, validated configuration | `binding` (`RefreshableConfig`, `HotReloadTypedConfigFactory`) |
| HashiCorp Vault secrets (KV polling, lease-renewed dynamic secrets, Kubernetes auth) | `secrets` |
| FreeMarker templates | `template` |
| Configuration, autoconfiguration | `config` (`HotReloadProperties`, `ludwig.hotreload.*`) |
| Generic reload notification | `event` (`ConfigurationRefreshedEvent`) |
| Audit trail (who/when/old/new) | `audit` (`HotReloadAuditEntry`, `AuditingSourceChangeListener`) |
| Metrics | `metrics` (Micrometer, optional) |

## Configuration reference

```properties
ludwig.hotreload.enabled=true

# --- Files -------------------------------------------------------------
ludwig.hotreload.files[0].path=/etc/config/app.properties
ludwig.hotreload.files[0].key-prefix=
ludwig.hotreload.file-watch.debounce=500ms

# --- Vault ---------------------------------------------------------------
ludwig.hotreload.vault.enabled=true
ludwig.hotreload.vault.uri=https://vault.vault.svc:8200
ludwig.hotreload.vault.poll-interval=30s

# Kubernetes auth (recommended in-cluster: exchanges the pod's service account JWT for a Vault token)
ludwig.hotreload.vault.kubernetes.role=my-app
ludwig.hotreload.vault.kubernetes.auth-path=kubernetes
# ludwig.hotreload.vault.kubernetes.service-account-token-file defaults to the projected token path

# Static token auth (local development only)
# ludwig.hotreload.vault.token=...

# Static KV secret (v1 or v2), re-read every poll-interval
ludwig.hotreload.vault.secrets[0].mount=secret
ludwig.hotreload.vault.secrets[0].path=myapp/config
ludwig.hotreload.vault.secrets[0].kv-version=2
ludwig.hotreload.vault.secrets[0].key-prefix=

# Dynamic secret (e.g. database/creds/my-role), pushed via Vault's own lease renewal/rotation
ludwig.hotreload.vault.dynamic-secrets[0].path=database/creds/my-role
ludwig.hotreload.vault.dynamic-secrets[0].rotating=true
ludwig.hotreload.vault.dynamic-secrets[0].key-prefix=db.

# --- FreeMarker ------------------------------------------------------------
ludwig.hotreload.freemarker.template-directory=/etc/templates

# --- Audit trail -----------------------------------------------------------
ludwig.hotreload.audit.enabled=true
ludwig.hotreload.audit.actor=

# --- Metrics -----------------------------------------------------------
ludwig.hotreload.metrics.enabled=true
```

## HashiCorp Vault on Kubernetes: two supported patterns

1. **Vault Agent Injector (recommended when available).** The sidecar renders secrets to files on an
   `emptyDir`, updating them in place via an atomic symlink swap on every rotation. Point
   `ludwig.hotreload.files` at those files - no Vault client, no network call from your application at
   all. This is HashiCorp's own recommended Kubernetes integration, and `FileResourceWatcher` is built
   specifically to handle that symlink-swap update mechanism correctly (it watches the directory, not
   just the filename).
2. **Direct Vault API (`ludwig.hotreload.vault.*`).** For when you don't run the injector, or need
   dynamic (leased) secrets. `VaultKvSecretSource` polls static KV secrets; dynamic secrets (database
   credentials, PKI, ...) are requested from Vault in renewable/rotating mode and pushed via
   `SecretLeaseContainer`'s own lease renewal - no polling involved. Because authenticating against Vault
   is a network call, Vault-sourced values are not guaranteed to be present at the very first bean's
   construction the way file-sourced values are (see `HotReloadVaultAutoConfiguration`'s Javadoc) - prefer
   `RefreshableConfig`/`HotReloadTypedConfigFactory` for values sourced this way, or fall back to pattern
   1 if a value must be present from the first line of `main()`.

## Validation

Add `spring-boot-starter-validation` to get both Hibernate Validator and the EL implementation its
default message interpolator needs (`hibernate-validator` alone throws `HV000183` without one). Without
any validator on the classpath, binding still works - constraints are simply not checked.

## FreeMarker

```properties
ludwig.hotreload.freemarker.template-directory=/etc/templates
```

With that set (and `freemarker` on the classpath), a `freemarker.template.Configuration` bean is
provided automatically, wired to reload a template the moment its directory changes - no waiting for
FreeMarker's own lazy `templateUpdateDelay` check. If your application already defines its own
`Configuration` bean, wire it manually instead:

```java
@Bean
Configuration freeMarkerConfiguration(HotReloadableTemplateLoader templateLoader) {
    Configuration configuration = new Configuration(Configuration.VERSION_2_3_32);
    configuration.setTemplateLoader(templateLoader);
    templateLoader.bindTo(configuration); // required so invalidateAll() can clear this Configuration's cache
    return configuration;
}
```

## Audit trail

Every successful reload produces one `HotReloadAuditEntry` - source, timestamp, actor, and the old and
new value of every key that changed - which is flattened into the platform's audit envelope and handed to
the single `AuditSink`:

```java
public record HotReloadAuditEntry(String sourceId, Instant timestamp, String actor,
                                   Map<String, ValueChange> changes) {
    public record ValueChange(Object oldValue, Object newValue) { }
    public AuditEvent toAuditEvent() { ... }        // action: config.reloaded
}
```

**`HotReloadAuditLogger` and `Slf4jHotReloadAuditLogger` are gone.** They were two of the nine audit
mechanisms this platform had collected, and the trail now goes wherever every other module's goes - a log,
the append-only `audit_event` table, a SIEM through the transactional outbox, or several at once. See
[`audit-core`](../audit-core). For a regulated environment, publish an `@Bean AuditSink` instead of a
`HotReloadAuditLogger`, and you get every other module's trail through the same bean rather than only this
one's:

```java
@Bean
AuditSink auditSink(MySiemClient siem) {
    return event -> siem.ship(event);               // every category, not just config
}
```

**One reload is now one event, not one event per key.** The changed keys are an attribute - a map of
`key -> {old, new}` plus a sorted `changedKeys` list - because a reload *is* one thing that happened:
somebody edited a ConfigMap once. `Slf4jHotReloadAuditLogger` did log a line per key, and splitting a
reload into N events would make "what did that reload change" a correlation problem instead of a single
row. A failed reload is a separate action, `config.reload-failed`, with outcome `FAILURE`, rather than an
entry with an empty change set - "nothing changed" and "it broke" must not be the same shape.

**What `actor` is, and what it isn't.** `actor` identifies *this application instance* (the local
hostname by default, override with `ludwig.hotreload.audit.actor`) - i.e. "which instance observed and
applied the change, and when." It is **not** the upstream identity that made the change in the first
place. Neither a mounted file nor a plain Vault read exposes who wrote it - that's what Vault's own audit
device (or `git blame` on the ConfigMap/Secret manifest) is for. This library's audit trail is the
complementary "who/when noticed and applied it" record on the consuming side, not a replacement for
Vault's audit log. It is recorded with `principalType` `INSTANCE` for exactly that reason: a trail in
which a hostname sits in the same column as a person's id with nothing to tell them apart is a trail that
will be read wrong.

**Secret values are always redacted before the entry exists** - every key sourced from Vault, plus any key
whose name matches a common secret pattern (`password`, `secret`, `token`, `credential`, `apiKey`, ...), is
masked as `***REDACTED***` no matter which sink is configured, so a custom sink persisting to a database
can't accidentally leak a credential into it. If you need full audit visibility into actual secret
*values*, that has to come from Vault's own audit device, which is access-controlled for exactly that
purpose - this library deliberately doesn't try to be that.

Both of this module's classification rules survived the consolidation as composable classifiers, and the
`vault:` / `vault-lease:` prefixes are now configuration rather than source: see
`ludwig.audit.redaction.sensitive-provenance-prefixes`. A deployment with a differently named secret store
adds its own prefix there rather than patching this module.

Set `ludwig.hotreload.audit.enabled=false` to disable this module's contribution to the audit trail
entirely. **What a deployment notices:** the `ru.ludwigandreas.hotreload.audit` logger no longer exists;
reloads are on `ru.ludwigandreas.audit` with `category=config`.

## Metrics

Optional (only if Micrometer is on the classpath, enabled by default via
`ludwig.hotreload.metrics.enabled`): `ludwig.hotreload.reload.succeeded` and
`ludwig.hotreload.reload.failed` (tagged `source`, and `exception` for failures) count every reload;
`ludwig.hotreload.reload.changed.keys` is a distribution summary of how many keys changed per reload; and
`ludwig.hotreload.reload.seconds.since.last.success` is a gauge, one per source, for "how stale is this
source right now" - the metric worth alerting on, since a source that's stopped reloading fails silently
by design (it just keeps serving last-known-good content).

## Generic reload notifications

Every successful reload - file, KV secret, or dynamic secret - publishes a `ConfigurationRefreshedEvent`
on the `ApplicationContext`, regardless of whether anything is using `RefreshableConfig` for it:

```java
@EventListener
void onConfigChanged(ConfigurationRefreshedEvent event) {
    log.info("{} changed: {}", event.getSourceId(), event.getChangedKeys());
}
```
