## MODIFIED Requirements

### Requirement: A caller can discover the operations it holds

`GET /admin/v1/me/operations` SHALL return, as `application/json`, the operations the caller holds, each with its
id, area, tier and a localized description, and SHALL NOT list operations the caller does not hold. Any caller the
administrative chain admits may call it.

#### Scenario: An administrative UI decides which controls to show
- **WHEN** a user holding only `ROLE_NOTIFICATION_SUPPORT` calls it in notification-service
- **THEN** the response lists `outbox.read` and not `outbox.operate`, with descriptions in the caller's locale

#### Scenario: A caller holding nothing
- **WHEN** an authenticated user with no granted operation calls it
- **THEN** the response is `200` with an empty list

#### Scenario: The published contract names the media type
- **WHEN** a service's OpenAPI document is generated
- **THEN** the `200` response of `GET /admin/v1/me/operations` has content type `application/json` and no `*/*`
  entry

## ADDED Requirements

### Requirement: Every catalogued operation has a description
Application startup SHALL fail when an operation in the administrative operation catalog has a description key that
does not resolve in the default locale. The failure SHALL name the operation id and the key.

#### Scenario: A module forgets a bundle key
- **WHEN** a contributor declares `widgets.read` with key `ludwig.widgets.admin.read` and no bundle defines that key
- **THEN** the application fails to start with a message naming `widgets.read` and `ludwig.widgets.admin.read`

#### Scenario: Both services start
- **WHEN** `notification-service` and `crud-service-example` start with their configured grants
- **THEN** startup succeeds, and every operation listed by `GET /admin/v1/me/operations` has a description that
  differs from its id
