# The notification-service local run

## Purpose
Defines the local application of `notification-service`: what it starts, what data it starts
with and what it tells the developer, so that any delivery path can be debugged with a breakpoint
and so that other services have one complete example to copy.

## Requirements

### Requirement: The local application runs the whole service
`notification-service` SHALL have a local application that starts the service's own `main` with
PostgreSQL, Kafka, a mail server, and stand-ins for the chat API, a webhook receiver and the
identity provider. The email, chat, webhook and in-app channels, the REST and Kafka ingresses, the
delivery queue poller and the identity projection SHALL all be enabled.

#### Scenario: The local application is started
- **WHEN** a developer runs `mvn -pl :notification-service spring-boot:test-run
  -Dspring-boot.run.main-class=ru.ludwigandreas.notification.local.LocalNotificationApplication`
- **THEN** the service answers `GET /actuator/health` with 200 and `GET /server/info` with its
  name, with no environment variable set and no container started by hand

#### Scenario: Nothing is switched off
- **WHEN** the local application has started
- **THEN** a notification submitted over REST to a seeded recipient with an email address and a
  chat handle reaches the mail server and the chat stand-in without any further configuration

#### Scenario: The Kafka ingress is live
- **WHEN** a notification request is published to the ingress topic
- **THEN** the service consumes it and a delivery is created

#### Scenario: It starts on the classpath the service is packaged with
- **WHEN** the local application is started by `spring-boot:test-run`, which leaves out a
  `provided` dependency the Spring Boot plugin excludes
- **THEN** it starts, because no stand-in or fixture uses a class that is only on the build's test
  classpath

### Requirement: Local configuration cannot ship
Every property the local run needs beyond the service's own defaults SHALL live under
`src/test`, and `notification-service`'s main sources and main resources SHALL NOT change.

#### Scenario: The service image is built
- **WHEN** `notification-service` is packaged
- **THEN** the artifact contains no local profile, no stand-in and no local credential

### Requirement: The service validates real tokens locally
The local application SHALL authenticate callers through the service's production token
validation, against a stand-in issuer that serves discovery metadata and signing keys. There SHALL
be no header-based or profile-based authentication bypass.

#### Scenario: A request carries a token from the stand-in issuer
- **WHEN** a request to a protected endpoint carries a token minted for a seeded caller, with the
  service's audience and tenant claim
- **THEN** the request is authorised as that caller

#### Scenario: A request carries no token
- **WHEN** a request to a protected endpoint carries no token
- **THEN** the service answers 401 with its usual problem document

#### Scenario: A token has the wrong audience
- **WHEN** a request carries a token from the stand-in issuer minted for another audience
- **THEN** the service answers 401, as it would in a deployed environment

### Requirement: The summary gives a working command per caller
The start-up summary SHALL print, for each seeded caller, a complete command that calls the
service as that caller, and the address of the API documentation.

#### Scenario: A developer pastes the printed command
- **WHEN** a developer runs the command printed for the administrator caller
- **THEN** the service answers 200

### Requirement: The seed data covers every delivery path
The local application SHALL start with seed data that contains at least: a recipient reachable by
email only; one with a chat handle; one deactivated; a notification that the service delivers
shortly after start; a notification still queued; a published announcement; and one request on the
Kafka ingress topic. Recipients SHALL be written through the identity projection's repository, and
notifications and the announcement SHALL be submitted through the service's own REST API.

The seed SHALL NOT contain a recipient with a stored opt-out or quiet hours while the service has
no preference store, and SHALL NOT contain a recipient with a webhook address, because a webhook
destination is named on the request and not held on a recipient.

#### Scenario: A developer opens the inbox of a seeded user
- **WHEN** the inbox endpoint is called as the seeded ordinary user
- **THEN** it returns the seeded in-app notification

#### Scenario: A developer lists announcements as a seeded user
- **WHEN** the announcements endpoint is called as the seeded ordinary user
- **THEN** it returns the published announcement

#### Scenario: A developer triggers a webhook delivery
- **WHEN** a notification using the local webhook template is submitted with the webhook
  stand-in's printed address as its recipient
- **THEN** the webhook stand-in records one signed request

#### Scenario: The service is launched twice
- **WHEN** the local application is stopped and started again in the default fixture mode
- **THEN** the seed data is the same as after the first start, with no duplicate

### Requirement: Partner outages are reproducible through the service's own delivery logic
The chat and webhook stand-ins SHALL support the fault switch, and a fault SHALL be handled by
the service's own failure classification and retry.

#### Scenario: The chat API is unavailable
- **WHEN** the chat stand-in is made unavailable and a chat notification is submitted
- **THEN** the delivery is recorded as `FAILED` with failure kind `RETRYABLE`, one attempt, and a
  next attempt scheduled

#### Scenario: The chat API recovers
- **WHEN** the fault is cleared and the delivery is retried
- **THEN** the delivery is recorded as delivered

#### Scenario: A webhook endpoint is slower than the read timeout
- **WHEN** the webhook stand-in is made slow and a webhook notification is submitted
- **THEN** the delivery is recorded as a retryable failure and the service does not wait beyond its
  configured read timeout

### Requirement: A redelivered Kafka request is not sent twice
The stand-ins and fixtures SHALL make the service's handling of a duplicate ingress record
testable.

#### Scenario: The same request is delivered twice
- **WHEN** one notification request is delivered to the ingress topic twice with the same key
- **THEN** exactly one notification exists for it and the mail server received one message

### Requirement: The reference example is launched by the build
An integration test SHALL start the local application through its own `main` and exercise it, so
a change that breaks the example fails the service's build.

#### Scenario: The local application stops starting
- **WHEN** a change makes the local application fail to start
- **THEN** `mvn -pl :notification-service -am verify` fails in that test

### Requirement: A new outbound HTTP partner cannot be added without a stand-in
In `notification-service`, an outbound HTTP client SHALL be built only in the one configuration
class whose partners the stub catalog covers.

#### Scenario: A new class builds an HTTP client
- **WHEN** a main class outside that configuration class builds a `RestClient` or a JDK
  `HttpClient`
- **THEN** the service's architecture test fails naming the class

#### Scenario: The local application is moved
- **WHEN** the class that uses the launcher is placed outside a package named `local`
- **THEN** the service's architecture test fails naming the class
