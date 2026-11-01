## RENAMED Requirements

- FROM: `### Requirement: The identity projection's Kafka consumer starts when enabled`
- TO: `### Requirement: The identity Kafka source starts when enabled`

## MODIFIED Requirements

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
