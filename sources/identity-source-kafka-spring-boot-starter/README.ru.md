# identity-source-kafka-spring-boot-starter

*[English](README.md) · **Русский***

Наполняет [`identity-projection-spring-boot-starter`](../identity-projection-spring-boot-starter) от провайдеров,
которые публикуют изменения пользователей в Kafka: одна подписка на каждый объявленный источник, опубликованный
контракт `IdentityUserEvent` v1 и платформенный потребитель из
[`messaging-spring-boot-starter`](../messaging-spring-boot-starter) - повторы с растущими паузами, затем топик
недоставленных сообщений.

| | |
|---|---|
| Роль | starter |
| Зависит от (в репозитории) | [`identity-projection-spring-boot-starter`](../identity-projection-spring-boot-starter), [`messaging-spring-boot-starter`](../messaging-spring-boot-starter) |
| Поверхности | Kafka (входящие потоки пользователей) |

## Использование

```xml
<dependency>
    <groupId>ru.ludwigandreas</groupId>
    <artifactId>identity-source-kafka-spring-boot-starter</artifactId>
</dependency>
```

```yaml
ludwig:
  identity:
    sources:
      staff: {}
      customers: {}
    kafka:
      group-id: orders-identity              # обязательно: каждый сервис читает своей группой
      subscriptions:
        staff:
          topic: corp.staff.users
        customers:
          topic: shop.customer.users
```

Каждый подписанный топик наполняет свой источник; источник должен быть объявлен в `ludwig.identity.sources`, где
находится его сопоставление ролей. Без подписки стартер ничего не регистрирует.

### Контракт события

```json
{
  "eventId":   "5f1c...",
  "type":      "UPSERT",
  "subject":   "0b7c1b5e-...",
  "displayName": "Anna Schmidt",
  "tenantId":  "tenant-a",
  "roles":     ["CATALOG_ADMIN", "ORDER_AGENT"],
  "email":     "anna.schmidt@example.com",
  "emailVerified": true,
  "alternateEmail": "anna@personal.example",
  "phoneNumber": "+49301234567",
  "phoneVerified": true,
  "chatHandle": "@anna",
  "sourceVersion": "7",
  "occurredAt": "2026-03-01T10:00:00Z"
}
```

- `type` - `UPSERT`, `DISABLE` или `DELETE` (применяется как удаление: статус, никогда не удалённая строка); `type`
  и `occurredAt` обязательны.
- **`roles` - полный набор, никогда не дельта**; событие без него или без любого поля профиля сохраняет хранимое
  значение.
- **`occurredAt` - ключ упорядочивания**: событие не новее хранимого состояния пропускается, поэтому повтор никогда
  не возвращает отозванную роль.
- **Неизвестные поля игнорируются**: схемой владеет провайдер.

### Обработка сбоев

| Сбой | Обработка |
|---|---|
| Не JSON, не объект контракта или нет `type` / `occurredAt` | В топик недоставленных при первой доставке (без повторов); партиция идёт дальше |
| Сбой применения (база недоступна, две реплики вставляют одного нового пользователя) | Повторы с растущими паузами, затем в топик недоставленных; применение идемпотентно, повторная доставка безопасна |

## Конфигурация

| Свойство | По умолчанию | Значение |
|---|---|---|
| `ludwig.identity.kafka.enabled` | `true` | Читать вообще (выключено: таблицы проекции по-прежнему читаются) |
| `ludwig.identity.kafka.group-id` | - | **Обязательно** при любой подписке: одна группа на сервис |
| `ludwig.identity.kafka.subscriptions.<source>.topic` | - | Топик, наполняющий `<source>`; один топик - один источник |

Настройки потребителя слушателя (повторы, недоставленные, кластер) находятся в
`ludwig.messaging.consumers.identity-projection.*` (см. [messaging](../messaging-spring-boot-starter)).

## Развёртывание

- **Топики**: каждый подписанный топик и его топик недоставленных **`<topic>.dlt`** должны существовать.
- **Группа потребителей**: отдельный `group-id` на сервис; общая группа давала бы каждому сервису только часть
  потока.

## Наблюдаемость

Метрики потребления - из [messaging](../messaging-spring-boot-starter#observability) (`ludwig.messaging.consumed`,
`.retries`, `.dead.lettered`, отставание потребителя, `silent`); что проекция сделала с каждым событием -
`ludwig_identity_changes_total` (см. README проекции).

**Журнал.** `ERROR Dead-lettering an unparseable identity event on <topic>`. Содержимое никогда не пишется в журнал
(данные каталога о человеке).

**Рекомендуемые оповещения:** оповещение messaging о недоставленных для каждого топика идентичности;
`ludwig_messaging_silent` для потребителя идентичности с `silence-threshold` под частоту изменений провайдера.

## Runbook

| Симптом | Вероятная причина | Что делать |
|---|---|---|
| У нового пользователя нет ролей | Событие ещё не прочитано, потребитель застрял, или неверный топик/группа | Проверьте отставание и `silent` для `identity-projection`; `GET /admin/v1/identity/sources` показывает подписку |
| Записи в `<topic>.dlt` | Неразбираемое событие или повторяющийся сбой применения | Для неразбираемого: попросите провайдера опубликовать заново; для сбоев применения: устраните причину и переотправьте недоставленные (применение идемпотентно) |
| Старт падает с `group-id` | Подписка без группы потребителей | Задайте `ludwig.identity.kafka.group-id` |
| Старт падает с именем источника | Подписанный источник не объявлен | Объявите его в `ludwig.identity.sources` |

## Тестирование

`IdentityUserEventListenerTest` (топик в источник, контракт, сбои преобразования без повторов, повторяемые сбои
применения), `IdentityKafkaSubscriptionsTest`, `IdentityKafkaAutoConfigurationTest` (фабрика контейнеров и по
каналу на подписку; ничего без подписки или при `enabled=false`), `IdentityKafkaOrderingTest`.

## Проектные решения

- **Отдельный стартер**: проекция нейтральна к провайдеру; Kafka и её зависимости доходят только до сервисов,
  которые подписываются.
- **Один слушатель, топик выбирает источник**: новый провайдер - это строка конфигурации, а не бин.
- **Текстовые сообщения разбираются в модуле**: нарушение контракта - сбой преобразования, который платформа сразу
  отправляет в недоставленные, в отличие от сбоя применения, который повторяется.
- **Обязательная группа**: группа по умолчанию, случайно общая для сервисов, - тот сбой, при котором события теряются
  молча.
