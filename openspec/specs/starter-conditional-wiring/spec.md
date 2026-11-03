# Starter conditional wiring

## Purpose
A starter's beans that are conditional on another starter's beans must be evaluated after them,
and a starter that is on the classpath with nothing enabled must not prevent start-up. Both
failures appear only when a real application context is built with the feature switched on.

## Requirements

### Requirement: The identity Kafka source starts when enabled
With `identity-source-kafka-spring-boot-starter`, the platform messaging starter and at least one subscription
under `ludwig.identity.kafka.subscriptions`, an application SHALL start with the identity listener registered on its
own container factory. With `ludwig.identity.kafka.enabled=false` it SHALL start with no identity listener.

#### Scenario: The consumer is enabled in a full application
- **WHEN** an application with `identity-projection-spring-boot-starter`, `identity-source-kafka-spring-boot-starter`
  and `messaging-spring-boot-starter` starts with a subscription for a declared source
- **THEN** it starts, and a bean named `identityKafkaListenerContainerFactory` exists

#### Scenario: The consumer is disabled
- **WHEN** the same application starts with `ludwig.identity.kafka.enabled=false`
- **THEN** it starts with no identity listener

### Requirement: The user-settings starter is inert when no mode is enabled
With `user-settings-spring-boot-starter` on the classpath, hot-reload enabled, and neither the
owner nor the projection mode enabled, an application SHALL start, and the starter SHALL register
no hot-reload bean.

#### Scenario: The starter is present with nothing enabled
- **WHEN** an application with the starter and with hot-reload enabled starts with neither
  `ludwig.user-settings.owner.enabled` nor `ludwig.user-settings.projection.enabled` set
- **THEN** it starts

#### Scenario: A mode is enabled
- **WHEN** the same application starts with `ludwig.user-settings.projection.enabled=true`
- **THEN** the defaults provider and the reload listener are registered, as before

#### Scenario: The starter is switched off
- **WHEN** the same application starts with `ludwig.user-settings.enabled=false`
- **THEN** it starts and no hot-reload bean of the starter is registered

### Requirement: The security starter works without web-core
An application with `security-spring-boot-starter` and without `web-core-spring-boot-starter` on its classpath SHALL
start. In it, a call to a `PRIVILEGED` operation without a stated reason, or with a personal access token, and a call
naming an administrative object outside the caller's scope, SHALL be refused with the security starter's own
reason-required, credential-not-permitted or object-not-found refusal, never with a linkage error. Where web-core is
present, those refusals SHALL be rendered as before: `400` (`INVALID_ARGUMENT` on gRPC) with the reason-required
problem type, `403` (`PERMISSION_DENIED`) with the credential-not-permitted problem type, and `404` with the area's
not-found code.

#### Scenario: A worker without web-core starts
- **WHEN** an application with the security starter and no web-core on its classpath starts with every security
  auto-configuration enabled
- **THEN** it starts

#### Scenario: A privileged call without a reason, without web-core
- **WHEN** a caller holding a `PRIVILEGED` business operation calls it without a reason in that application
- **THEN** the call is refused with the security starter's reason-required refusal, carrying the reason-required code

#### Scenario: A privileged call with a personal access token, without web-core
- **WHEN** the same caller calls it with a reason, authenticated by a personal access token, in that application
- **THEN** the call is refused with the security starter's credential-not-permitted refusal, carrying its code

#### Scenario: The refusal over HTTP is unchanged
- **WHEN** the same call is made over HTTP in an application with web-core
- **THEN** the response is `400` with the reason-required problem type

#### Scenario: An object outside the caller's scope is still not found
- **WHEN** a caller reads an administrative object outside their scope over HTTP in an application with web-core
- **THEN** the response is `404` with the area's not-found code, as for an object that does not exist
