# outbox-spring-boot-starter

*[English](README.md) · **Русский***

Транзакционный outbox для сервисов Spring Boot на PostgreSQL. Сервис записывает событие в таблицу
`outbox_message` в той же транзакции, что и бизнес-изменение, которое оно описывает; фоновый поллер
доставляет его в Kafka, на REST-эндпоинт или через собственный транспорт. Событие об откатившемся
изменении не отправляется, а закоммиченное изменение не теряет своё событие.

| | |
|---|---|
| Роль | starter |
| Зависит от (в репозитории) | [`audit-core`](../audit-core), [`db-core`](../db-core), [`job-core`](../job-core), [`messaging-spring-boot-starter`](../messaging-spring-boot-starter) |
| Используется | стартерами `asyncapi`, `audit`, `export`, `file-action`, `file-ingest`, `user-settings`; сервисами `crud-service-example`, `notification-service` |
| Поверхности | Kafka и REST (только исходящие) |

## Использование

```xml
<dependency>
    <groupId>ru.ludwigandreas</groupId>
    <artifactId>outbox-spring-boot-starter</artifactId>
</dependency>
```

Публикуйте изнутри транзакции, которая вносит изменение:

```java
@Service
class OrderService {

    private final OutboxEventPublisher outboxEventPublisher;

    @Transactional
    public void createOrder(Order order) {
        orderRepository.save(order);

        outboxEventPublisher.publish(OutboxEvent.builder()
                .aggregateType("Order")
                .aggregateId(order.getId().toString())
                .eventType("OrderCreated")
                .payload(new OrderCreatedPayload(order.getId(), order.getTotal()))
                .build());
    }
}
```

`publish` требует внешней транзакции (`Propagation.MANDATORY`). Вызов вне транзакции бросает
исключение: без транзакции гарантия атомарности не существует.

### Что происходит после `publish`

Поллер захватывает готовые строки через `SELECT ... FOR UPDATE SKIP LOCKED`, поэтому любое число
экземпляров опрашивает таблицу параллельно, не обрабатывая строку дважды. Каждое захваченное сообщение
отправляется через транспорт, в который разрешился его маршрут, и результат записывается:

```
PENDING ──захват──> PROCESSING ──успех──> PUBLISHED
                        │
                        ├──ошибка, попытки есть──> PENDING (next_attempt_at = now + backoff)
                        └──ошибка, бюджет исчерпан──> DEAD_LETTER   (FAILED, если dead-letter выключен)
```

Строку в `PROCESSING`, владелец которой умер, реклеймер возвращает в `PENDING` по истечении
`processing.stale-timeout`. При остановке поллер дорабатывает текущий цикл (до
`processing.drain-timeout`), а не бросает захваченные строки.

### Маршрутизация

```properties
ludwig.outbox.routes.order-events.transport=KAFKA
ludwig.outbox.routes.order-events.destination=orders-topic
ludwig.outbox.routes.order-events.event-types=OrderCreated,OrderCancelled

ludwig.outbox.routes.audit-events.transport=REST
ludwig.outbox.routes.audit-events.destination=https://audit.example.com/events

ludwig.outbox.default-route.transport=KAFKA
ludwig.outbox.default-route.destination=default-topic
```

Порядок разрешения:

1. `OutboxEvent.route()` - явное имя маршрута от вызывающего кода;
2. первая запись `ludwig.outbox.routes.*`, чьи `event-types` содержат тип события;
3. `ludwig.outbox.default-route`.

**Один Kafka-транспорт на кластер.** `KAFKA` пишет через `spring.kafka.*`. Каждый кластер, объявленный в
`ludwig.kafka.clusters` (см. [messaging](../messaging-spring-boot-starter/README.ru.md)), добавляет транспорт
`KAFKA:<имя>`:

```properties
ludwig.outbox.routes.partner-invoices.transport=kafka:partner-a
ludwig.outbox.routes.partner-invoices.destination=invoices
```

Маршрут с необъявленным кластером валит старт. Чтобы добавить транспорт помимо Kafka и REST,
зарегистрируйте бин `OutboxDispatcher` (и `OutboxRouteResolver`, если меняется маршрутизация).

### Конверт сообщения

Записи Kafka несут заголовки платформенного конверта из `MessageHeaders` модуля `messaging`: тип и версия
события, тип и id агрегата, ключ идемпотентности, trace id, время публикации и все заголовки сообщения.
Ключ записи - ключ упорядочивания, если задан, иначе id агрегата, поэтому события одного агрегата
попадают в одну партицию.

Correlation id запроса сохраняется в заголовки сообщения в момент `publish` под именем
`ludwig.messaging.correlation-header` (по умолчанию `X-Correlation-Id`), если событие не задаёт его само.
Поллер работает в своём потоке без контекста запроса, поэтому `publish` - единственный момент, когда id
можно прочитать. Id берётся из `CorrelationProvider` модуля audit-core, который предоставляет
[`observability-spring-boot-starter`](../observability-spring-boot-starter).

### Объявленные типы событий

Бин `OutboxEventTypes` объявляет, какой класс payload несёт каждый тип события. Несколько бинов
объединяются:

```java
@Bean
OutboxEventTypes catalogEventTypes() {
    return OutboxEventTypes.merge(List.of(
            OutboxEventTypes.of(Map.of("ProductCreated", ProductEventPayload.class)),
            // Событие, отправляемое по маршруту по имени, объявляет этот маршрут.
            OutboxEventTypes.routed("NotifyProductCreated", ProductEventPayload.class, "product-notifications")));
}
```

- Тип, объявленный дважды с разными классами или маршрутами, валит старт.
- [`asyncapi-spring-boot-starter`](../asyncapi-spring-boot-starter) читает объявления и описывает каждое
  событие в AsyncAPI-документе сервиса - на том топике и кластере, куда разрешается его маршрут.
- При `ludwig.outbox.events.require-declared=true` `publish` отказывает необъявленному типу, payload
  чужого класса и явному маршруту, отличному от объявленного. Включайте это в **тестовых** свойствах
  каждого сервиса, чтобы необъявленный продюсер валил собственные тесты сервиса. В продакшене остаётся
  `false` по умолчанию.

### Упорядочивание

События с общим `OutboxEvent.orderingKey(...)` отправляются в порядке создания: запрос поллера захватывает
сообщение, только когда все более ранние неразрешённые сообщения с тем же ключом находятся в `PUBLISHED`.
Сообщение в `DEAD_LETTER` больше не блокирует свой ключ, поэтому одно «ядовитое» сообщение не стопорит
ключ навсегда. Отключается `ludwig.outbox.ordering.enabled=false`.

### Идемпотентность

`OutboxEvent.idempotencyKey(...)` заставляет `publish` вернуть существующую строку вместо вставки
дубликата (уникальный частичный индекс). Две действительно одновременные публикации одного ключа
откатывают транзакцию проигравшего с `DataIntegrityViolationException`; трактуйте это как «уже
обработано».

## Конфигурация

| Свойство | По умолчанию | Смысл |
|---|---|---|
| `ludwig.outbox.enabled` | `true` | Главный выключатель |
| `ludwig.outbox.polling.enabled` | `true` | Запускать поллер и реклеймер зависших захватов |
| `ludwig.outbox.polling.fixed-delay` | `5s` | Пауза между циклами опроса |
| `ludwig.outbox.polling.initial-delay` | `5s` | Пауза перед первым циклом |
| `ludwig.outbox.polling.batch-size` | `100` | Максимум строк за цикл |
| `ludwig.outbox.polling.lock-owner` | hostname + случайный суффикс | Значение, записываемое в `locked_by` |
| `ludwig.outbox.processing.stale-timeout` | `5m` | Строка в `PROCESSING` старше этого возвращается в `PENDING` |
| `ludwig.outbox.processing.stale-reclaim-fixed-delay` | `1m` | Как часто работает реклеймер |
| `ludwig.outbox.processing.drain-timeout` | `20s` | Сколько остановка ждёт текущий цикл. Держите ниже termination grace period пода |
| `ludwig.outbox.retry.max-attempts` | `10` | Попыток до dead-letter |
| `ludwig.outbox.retry.initial-interval` | `1s` | Первый интервал backoff |
| `ludwig.outbox.retry.multiplier` | `2.0` | Множитель роста backoff |
| `ludwig.outbox.retry.max-interval` | `5m` | Потолок backoff |
| `ludwig.outbox.retry.jitter` | `0.2` | Каждый интервал рандомизируется на ±20%, чтобы сообщения, упавшие вместе, не повторялись в один момент. `0` - детерминированно |
| `ludwig.outbox.ordering.enabled` | `true` | Учитывать `orderingKey` |
| `ludwig.outbox.idempotency.enabled` | `true` | Учитывать `idempotencyKey` |
| `ludwig.outbox.dead-letter.enabled` | `true` | Переводить исчерпавшие попытки сообщения в `DEAD_LETTER`; иначе они остаются `FAILED` |
| `ludwig.outbox.audit.persist-history` | `false` | Дополнительно сохранять каждый переход в `outbox_status_history` |
| `ludwig.outbox.metrics.enabled` | `true` | Публиковать метрики Micrometer (если Micrometer есть в classpath) |
| `ludwig.outbox.liquibase.enabled` | `true` | Применять поставляемый changelog отдельным бином `SpringLiquibase` |
| `ludwig.outbox.rest.connect-timeout` / `read-timeout` | `5s` / `10s` | Таймауты REST-диспетчера |
| `ludwig.outbox.rest.endpoints.<key>` | - | Логическое имя эндпоинта → URL, для назначений, которые не являются полным URL |
| `ludwig.outbox.kafka.send-timeout` | `10s` | Максимальное ожидание подтверждения брокера на сообщение |
| `ludwig.outbox.events.require-declared` | `false` | Отказывать необъявленным типам событий (см. [Объявленные типы событий](#объявленные-типы-событий)) |
| `ludwig.outbox.routes.<name>.transport` / `.destination` / `.event-types` | - | Именованный маршрут |
| `ludwig.outbox.default-route.transport` / `.destination` | - | Маршрут для событий, не подошедших ни под один именованный |

Backoff, планирование и захват строк берутся из [`job-core`](../job-core) и общие с
[`reconciliation-spring-boot-starter`](../reconciliation-spring-boot-starter), поэтому все воркеры
платформы повторяют попытки по одной кривой.

## Развёртывание

**Схема (только PostgreSQL).** Liquibase changelog `classpath:db/changelog/outbox/outbox-changelog.xml`
создаёт `outbox_message` и `outbox_status_history`. Применить его можно двумя способами:

- подключить из собственного master changelog сервиса:
  ```xml
  <include file="classpath:db/changelog/outbox/outbox-changelog.xml" relativeToChangelogFile="false"/>
  ```
  и выставить `ludwig.outbox.liquibase.enabled=false`;
- или дать стартеру выполнить его вторым бином `SpringLiquibase` на основном `DataSource`
  (по умолчанию, нужен `liquibase-core` в classpath).

Id changeset-ов имеют свой namespace (`outbox-NNNN`, автор `ludwig-outbox`) и не пересекаются с
changeset-ами сервиса.

**Репозитории** регистрирует сам стартер и только для своих пакетов; сервису не нужно настраивать
сканирование сущностей и репозиториев.

**Kafka/REST.** Топики и эндпоинты из маршрутов должны существовать и быть доступны. Учётные данные Kafka
берутся из `spring.kafka.*` или из конфигурации именованного кластера.

**Планирование.** Поллер работает на собственном `TaskScheduler` стартера; `@EnableScheduling` в сервисе
не нужен.

**Рост данных.** Стартер не удаляет строки в `PUBLISHED`. Запланируйте задачу хранения (или
партиционирование `outbox_message`) под объём событий; см. [Runbook](#runbook).

## Наблюдаемость

**Метрики** (Micrometer; имена в Prometheus в скобках):

| Метрика | Тип | Теги | Смысл |
|---|---|---|---|
| `ludwig.outbox.published` (`ludwig_outbox_published_total`) | counter | `eventType`, `transport` | События, записанные в outbox |
| `ludwig.outbox.filtered` | counter | `eventType` | События, отброшенные `OutboxPublishFilter` |
| `ludwig.outbox.dispatch.succeeded` | counter | `eventType`, `transport`, `destination` | Успешные доставки |
| `ludwig.outbox.dispatch.failed` | counter | `eventType`, `transport`, `destination` | Неудачные попытки доставки (включая те, что будут повторены) |
| `ludwig.outbox.deadlettered` | counter | `eventType`, `transport` | Сообщения, переведённые в `DEAD_LETTER` |
| `ludwig.outbox.dispatch.duration` | timer | `transport`, `destination` | Длительность одного вызова диспетчера |
| `ludwig.outbox.latency` | timer | `eventType`, `transport` | Сквозное время `created_at` → `PUBLISHED`, включая задержку опроса и backoff. **Метрика для SLO** |

**Логи.** `WARN`, когда диспетчер бросает исключение (id сообщения, причина), и когда зависшие строки
`PROCESSING` возвращаются (количество). Payload никогда не логируется.

**Трассировка.** Trace id публикующего запроса хранится в `outbox_message.trace_id` и отправляется в
заголовке `ludwig-trace-id`, чтобы консьюмер мог связать себя с трассой продюсера.

**Health.** Health-индикатора нет.

**Рекомендуемые алерты:**

| Условие | Почему |
|---|---|
| `increase(ludwig_outbox_deadlettered_total[15m]) > 0` | Нижестоящая система не получит эти события без вмешательства |
| `histogram_quantile(0.99, rate(ludwig_outbox_latency_seconds_bucket[5m]))` выше SLO (например, 60s) | Доставка отстаёт: медленное назначение, остановился поллер или накопился бэклог |
| `rate(ludwig_outbox_dispatch_failed_total[5m]) / rate(ludwig_outbox_dispatch_succeeded_total[5m]) > 0.1` | Назначение отказывает; за этим последуют dead letter |

**Известные пробелы:**

- Нет gauge для бэклога (число `PENDING`, возраст самого старого готового сообщения). Остановившийся
  поллер не публикует *ничего*, и ни один алерт выше не сработает. Пока gauge нет, мониторьте SQL-запросом:
  `SELECT count(*), min(next_attempt_at) FROM outbox_message WHERE status = 'PENDING' AND next_attempt_at < now();`
- Нет health-индикатора, сообщающего об остановке поллера или о накоплении dead letter.

## Аудит

Каждый переход статуса записывается через платформенный `AuditSink` ([`audit-core`](../audit-core)):

| Поле | Значение |
|---|---|
| category | `outbox` |
| action | `outbox.<status>` (`outbox.processing`, `outbox.published`, `outbox.dead_letter`, ...) |
| actor | `system` - переход происходит в потоке планировщика; публиковавший пользователь записан в самом бизнес-событии |
| resource | `outbox-message` / id сообщения / тип события |
| outcome | `FAILURE` с ошибкой для `DEAD_LETTER` и `FAILED`, иначе `SUCCESS` |
| attributes | `eventType`, `aggregateType`, `aggregateId`, `attempts`, `fromStatus`, `toStatus` |

Payload никогда не включается: это бизнес-событие целиком, а аудиторский след хранится дольше и читается
большим числом людей, чем строка outbox.

`outbox_status_history` (включается `audit.persist-history`) - **операционное состояние**, а не
аудиторский след: диспетчер читает из неё счётчики попыток и последние ошибки. Она отделена от
платформенной таблицы `audit_event`, чтобы сокращение срока хранения аудита в развёртывании не сокращало
память диспетчера.

Сервис, зарегистрировавший собственный `OutboxAuditLogger`, заменяет поставляемый и сам отвечает за
пересылку в платформенный след.

## Runbook

| Симптом | Вероятная причина | Что делать |
|---|---|---|
| Растёт `ludwig_outbox_deadlettered_total` | Назначение отвергает payload или было недоступно дольше бюджета повторов (10 попыток за ≈ 8,5 мин по умолчанию: 1s, 2s, 4s ... 256s, ±20%) | Найти: `SELECT destination, event_type, last_error, count(*) FROM outbox_message WHERE status = 'DEAD_LETTER' GROUP BY 1, 2, 3;` Исправить назначение, затем переотправить (ниже) |
| События не приходят, нет ни ошибок доставки, ни dead letter | Поллер не работает: `polling.enabled=false`, экземпляр не стартовал или все экземпляры зависли | Проверить SQL бэклога из [известных пробелов](#наблюдаемость). Если готовые строки есть - проверить лог сервиса на поллер, затем `ludwig.outbox.polling.*` |
| Много строк зависло в `PROCESSING` | Экземпляр умер, держа захваты | Они вернутся в `PENDING` за `stale-timeout + stale-reclaim-fixed-delay` (6 мин по умолчанию); предупреждение `Reclaimed ... stale PROCESSING` это подтверждает. Действий не нужно, если строки не остаются |
| События одного агрегата остановились, остальные идут | Упорядочивание: более раннее сообщение с тем же `ordering_key` ещё не `PUBLISHED` (повторяется) | `SELECT id, status, attempts, last_error FROM outbox_message WHERE ordering_key = '<key>' AND status <> 'PUBLISHED' ORDER BY created_at;` Исправить головное сообщение; ключ разблокируется, когда оно опубликуется или уйдёт в dead letter |
| Старт падает: маршрут ссылается на необъявленный кластер | `transport=kafka:<name>` без `ludwig.kafka.clusters.<name>` | Объявить кластер или исправить маршрут |
| Старт падает: тип события объявлен дважды | Два бина `OutboxEventTypes` противоречат друг другу | Согласовать объявления |
| Тест падает: "undeclared event type" | `require-declared=true`, а у нового типа события нет объявления | Добавить его в бин `OutboxEventTypes` сервиса |
| `outbox_message` растёт без ограничений | Нет политики хранения для `PUBLISHED` | Удалять пачками в непиковое время: `DELETE FROM outbox_message WHERE id IN (SELECT id FROM outbox_message WHERE status = 'PUBLISHED' AND published_at < now() - interval '7 days' LIMIT 10000);` |

**Переотправка dead letter** (после устранения причины):

```sql
UPDATE outbox_message
   SET status = 'PENDING', attempts = 0, next_attempt_at = now(), locked_at = NULL, locked_by = NULL
 WHERE status = 'DEAD_LETTER' AND destination = '<destination>';
```

Сообщение отправляется повторно с тем же заголовком `ludwig-idempotency-key`; консьюмер с фильтром
дедупликации [messaging](../messaging-spring-boot-starter/README.ru.md) отбросит копию, которую уже
обработал. Консьюмер без дедупликации получит её ещё раз.

## Тестирование

- `mvn test` - модульные тесты (backoff, маршрутизация, фильтрация, сериализация, публикатор); без Docker.
- `mvn verify` - интеграционный тест на реальном PostgreSQL в Testcontainers: применяет настоящий
  changelog, сверяет с ним JPA-маппинги (`ddl-auto=validate`) и проверяет параллельный захват
  `SKIP LOCKED`, упорядочивание по ключу, идемпотентность, повторы и dead letter, восстановление зависших
  захватов. Нужен Docker.
- В сервисе-потребителе: выставьте `ludwig.outbox.events.require-declared=true` в тестовых свойствах.

## Проектные решения

- **`Propagation.MANDATORY` на `publish`.** Запись в outbox вне бизнес-транзакции - это двойная запись,
  то есть ровно та проблема, которую устраняет паттерн.
- **Backoff с jitter.** Иначе сообщения, упавшие во время одного сбоя, наступают в один момент и роняют
  только что восстановившееся назначение.
- **Дренаж при остановке.** Брошенные захваты ждут реклеймера (минуты); дренаж делает rolling deploy
  бесплатным.
- **Dead letter освобождает ключ упорядочивания.** Иначе одно «ядовитое» сообщение навсегда стопорит
  свой ключ.
- **Payload никогда не попадает в аудит и логи.** Событие - это бизнес-данные со своими правилами доступа.
- **Общая механика в `job-core`.** Одна кривая backoff, один запрос захвата, одна база планирования для
  всех воркеров - поэтому во время сбоя партнёра все воркеры ведут себя одинаково.
