## Context

See `proposal.md`. Each defect was found by a real application context and confirmed in the code:

- `AudienceValidatingJwtDecoderPostProcessor.postProcessAfterInitialization` returns the bean
  untouched unless it is a `NimbusJwtDecoder`. `notification-service` logged "JWT audience
  validation enabled" zero times and answered 200 to a token for another audience.
- `IdentityKafkaAutoConfiguration` is `@AutoConfiguration(after = IdentityProjectionAutoConfiguration.class)`.
  Boot orders unrelated auto-configurations alphabetically, and `ru.ludwigandreas.identity...`
  sorts before `ru.ludwigandreas.messaging...`, so its `@ConditionalOnBean(ListenerContainerFactoryBuilder)`
  is evaluated before `MessagingAutoConfiguration` registers that bean.
- `UserSettingsHotReloadAutoConfiguration` is conditional on the hot-reload factory only, and one
  of its beans requires the `LudwigCache` that `UserSettingsAutoConfiguration` creates behind
  `SettingsModeCondition` and `ludwig.user-settings.enabled`.
- `PostgresContainerConfiguration` exposes `Containers.postgres()` as a `@ServiceConnection` bean.
  Spring Boot's Testcontainers lifecycle closes a `Startable` bean when its context closes unless
  Testcontainers reuse is enabled in the environment, which it is not by default or in CI.

## Goals / Non-Goals

**Goals:** each defect fixed where it lives, with a test that fails without the fix; the
workarounds in `notification-service`'s test sources removed.

**Non-Goals:** those in `proposal.md`; no new configuration property; no public API change.

## Decisions

### D1. Audience: wrap what cannot be configured

For a `NimbusJwtDecoder` the post-processor keeps doing what it does - it sets the validator, which
also keeps the issuer check. For any other `JwtDecoder` bean it returns a decorator that delegates
`decode` and then applies `AudienceValidator` to the decoded token, throwing
`JwtValidationException` on failure, which the resource-server filter turns into the same 401.

*Why a decorator and not unwrapping `SupplierJwtDecoder`:* its delegate is private and created
lazily on first use; reaching it means reflection into Spring Security. A decorator works for that
type and for a decoder an application supplies itself.
*Why not set Boot's own `spring.security.oauth2.resourceserver.jwt.audiences`:* that is a second
property for one fact, and it would have to be set by every service; the module's own property is
the one services already set.

The "enabled" log line is written for both paths.

### D2. Identity: order by name

`afterName = "ru.ludwigandreas.messaging.config.MessagingAutoConfiguration"` is added to
`IdentityKafkaAutoConfiguration`. By name, so the ordering does not turn the starter's existing
dependency into a hard class reference at annotation level.

### D3. User-settings: the same two conditions as the configuration it serves

`UserSettingsHotReloadAutoConfiguration` gains `@ConditionalOnProperty(ludwig.user-settings.enabled,
matchIfMissing = true)` and `@Conditional(SettingsModeCondition.class)` - exactly what guards
`UserSettingsAutoConfiguration`. With no mode there is nothing for a defaults provider to serve.

### D4. Shared containers ignore `stop()`

`Containers`' four holders create small named subclasses whose `stop()` does nothing. `close()`
calls `stop()`, so a context's destroy callback, a JUnit `@Container` field and a stray
`try`-with-resources all become harmless. Ryuk still removes the containers at JVM exit, as the
existing comment in `Containers` already relies on.

*Why not change `PostgresContainerConfiguration` to expose connection details instead of the
container:* seventeen modules import it and some rely on `@ServiceConnection` semantics; making the
singleton un-stoppable fixes every consumer, including code that never goes through that class.
`Containers`' method signatures do not change.

### D5. Dependency direction and POMs

No in-repo dependency is added, so there is no direction question. None of the three structural
POMs changes, and no module POM changes.

### D6. Checks

| Convention | Check | Owner |
|---|---|---|
| A configured audience is enforced for any decoder type | unit test on the post-processor with a non-Nimbus decoder; `LocalNotificationApplicationIT` end to end | behaviour tests |
| The identity consumer starts in a full context | `ApplicationContextRunner` test ordering the two auto-configurations as Boot does; the local profile turning the consumer on | behaviour tests |
| The user-settings starter is inert with no mode | `ApplicationContextRunner` test | behaviour test |
| A shared container survives a context | integration test in `test-support` that closes a context | behaviour test |

None of these is a structural or source-text rule, so none belongs to ArchUnit or Checkstyle.

## Risks / Trade-offs

- **A deployment starts returning 401** to callers that used a token for another audience. → This
  is the configured behaviour finally taking effect; the changelog says so under `Fixed`, in bold.
- **A no-op `stop()` hides a caller that really wanted a fresh container.** → No such caller
  exists in the repository (checked); the javadoc says to create a container directly for that.
- **The gate is the whole reactor** and its API baseline step cannot pass on this machine. →
  `mvn clean install` is run by hand and reported as such.

## Open Questions

None.
