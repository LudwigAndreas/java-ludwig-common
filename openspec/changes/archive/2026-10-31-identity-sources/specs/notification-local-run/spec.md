## MODIFIED Requirements

### Requirement: The seed data covers every delivery path
The local application SHALL start with seed data that contains at least: a recipient reachable by
email only; one with a chat handle; one deactivated; a notification that the service delivers
shortly after start; a notification still queued; a published announcement; and one request on the
Kafka ingress topic. Recipients SHALL be written through the identity projection's change feed, as
changes of a declared local source, and notifications and the announcement SHALL be submitted
through the service's own REST API.

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
