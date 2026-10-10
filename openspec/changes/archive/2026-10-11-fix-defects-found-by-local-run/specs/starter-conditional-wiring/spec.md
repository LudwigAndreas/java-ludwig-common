## Purpose

A starter's beans that are conditional on another starter's beans must be evaluated after them,
and a starter that is on the classpath with nothing enabled must not prevent start-up. Both
failures appear only when a real application context is built with the feature switched on.

## ADDED Requirements

### Requirement: The identity projection's Kafka consumer starts when enabled
With `ludwig.identity.kafka.enabled=true` and the platform messaging starter present, an
application SHALL start with the identity projection's listener registered on its own container
factory.

#### Scenario: The consumer is enabled in a full application
- **WHEN** an application with `identity-projection-spring-boot-starter` and
  `messaging-spring-boot-starter` starts with `ludwig.identity.kafka.enabled=true`
- **THEN** it starts, and a bean named `identityKafkaListenerContainerFactory` exists

#### Scenario: The consumer is disabled
- **WHEN** the same application starts with `ludwig.identity.kafka.enabled=false`
- **THEN** it starts with no identity listener, as before

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
