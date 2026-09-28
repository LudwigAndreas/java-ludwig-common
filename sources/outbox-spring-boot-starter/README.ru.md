# outbox-spring-boot-starter

*[English](README.md) · **Русский***

Промышленный транзакционный outbox для сервисов на Java 17 + PostgreSQL + Spring Boot. Добавьте
зависимость, подключите поставляемый changelog Liquibase, вызывайте
`OutboxEventPublisher.publish(...)` внутри своих существующих `@Transactional`-методов — и события
надёжно доедут до Kafka, REST или вашего собственного транспорта: ни одно сообщение не будет
опубликовано без того, чтобы породившее его изменение зафиксировалось, и ни одно изменение не
зафиксируется с молча потерянным событием.

## Быстрый старт

```xml
<dependency>
    <groupId>ru.ludwigandreas</groupId>
    <artifactId>outbox-spring-boot-starter</artifactId>
    <version>1.0.0</version>
</dependency>
```

Подключите поставляемый changelog в свой мастер-changelog Liquibase (или дайте ему примениться
самостоятельно — см. [Схема](#схема-только-postgresql)):

```xml
<include file="classpath:db/changelog/outbox/outbox-changelog.xml" relativeToChangelogFile="false"/>
```

Публикуйте изнутри той транзакции, которая делает изменение, описываемое событием:

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

`OutboxEventPublisher.publish` требует окружающей транзакции (`Propagation.MANDATORY`): вызов вне её
бросает исключение сразу, а не ломает молча ту самую гарантию атомарности, ради которой шаблон и
существует.

## Что происходит дальше

Фоновый поллер (настройка не нужна — см. [Планирование](#планирование)) захватывает подошедшие строки
через `SELECT ... FOR UPDATE SKIP LOCKED`, поэтому поллер может работать на любом числе экземпляров
приложения и никогда не обработает одну строку дважды. Каждое захваченное сообщение отправляется тем
транспортом, в который разрешился его маршрут (Kafka, REST или ваш собственный), и результат
записывается: успех переводит его в `PUBLISHED`; неудача планирует повтор с экспоненциальным backoff,
а по исчерпании `ludwig.outbox.retry.max-attempts` — переводит в `DEAD_LETTER`.

## Возможности и где они живут

| Возможность | Пакет |
|---|---|
| API публикации, фильтрация | `api`, `publisher`, `publisher.filter` |
| Сериализация JSON | `serialization` (по умолчанию Jackson, заменяема) |
| Маршрутизация по нескольким назначениям | `routing` (из конфигурации либо явным `OutboxEvent.route()`) |
| Отправка в Kafka / REST, свои транспорты | `dispatch`, `dispatch.kafka`, `dispatch.rest` |
| Backoff повторов, обработка dead-letter | `BackoffCalculator` из `job-core`, `scheduler` (`OutboxOutcomeRecorder`) |
| Упорядочивание, идемпотентность | на уровне схемы и запросов (`entity`, `repository` — отдельного пакета нет) |
| Плановый публикатор, восстановление зависших захватов | `scheduler` |
| Аудит | `audit` (по умолчанию структурные логи, опционально сохраняемая история) |
| Метрики | `metrics` (Micrometer, опционально) |
| Конфигурация | `config` (`OutboxProperties`, `ludwig.outbox.*`) |

## Что этот модуль разделяет с остальной платформой

Кривая backoff, база самопланирующегося жизненного цикла, на которой построены поллер и сборщик
зависших захватов, запрос захвата `FOR UPDATE SKIP LOCKED` и идентичность инстанса, которая пишется в
`locked_by`, живут в [`job-core`](../job-core) и разделяются с
[`reconciliation-spring-boot-starter`](../reconciliation-spring-boot-starter). Они были вынесены, а не
скопированы, по конкретной причине: две разошедшиеся реализации кривой повторов - это ошибка, которая
проявляется только как "другой сервис восстанавливается после недоступности партнёра иначе, чем этот",
спустя месяцы и без единой зацепки.

В ходе этого выноса для модуля изменились две вещи, обе видны в конфигурации:

- **`ludwig.outbox.retry.jitter` (по умолчанию `0.2`).** Интервалы backoff теперь разбрасываются на
  пятую часть в обе стороны. Без этого все сообщения, упавшие во время короткой недоступности одного
  назначения, снова становились готовыми ровно в один и тот же момент, на всех инстансах сразу, и
  только что восстановившееся назначение опрокидывалось собственным накопленным трафиком. Поставьте
  `0`, если нужен детерминированный график.
- **`ludwig.outbox.processing.drain-timeout` (по умолчанию `20s`).** Остановка теперь дожидается уже
  идущего цикла опроса, а не отменяет его. Раньше его строки оставались в `PROCESSING`, принадлежа
  процессу, которого больше нет, пока сборщик зависших не замечал их спустя
  `stale-timeout + stale-reclaim-fixed-delay` - то есть каждый rolling deploy задерживал всё, что было
  в полёте, на минуты. Держите значение заметно меньше grace period остановки контейнера.

## Маршрутизация по нескольким назначениям

```properties
ludwig.outbox.routes.order-events.transport=KAFKA
ludwig.outbox.routes.order-events.destination=orders-topic
ludwig.outbox.routes.order-events.event-types=OrderCreated,OrderCancelled

ludwig.outbox.routes.audit-events.transport=REST
ludwig.outbox.routes.audit-events.destination=https://audit.example.com/events

ludwig.outbox.default-route.transport=KAFKA
ludwig.outbox.default-route.destination=default-topic
```

Порядок разрешения: `OutboxEvent.route()` (явное переопределение по имени маршрута) → первая запись
`ludwig.outbox.routes.*`, чей `event-types` содержит тип события → `ludwig.outbox.default-route`.
Зарегистрируйте собственный бин `OutboxRouteResolver`/`OutboxDispatcher`, чтобы добавить транспорт
помимо встроенных Kafka и REST.

## Упорядочивание

Задайте `OutboxEvent.orderingKey(...)` для событий, которые обязаны отправляться в порядке друг
относительно друга (например, все события одного агрегата). Запрос опроса захватывает сообщение только
после того, как каждое более раннее (меньшие `created_at`/`id`) неразрешённое сообщение с тем же ключом
достигло `PUBLISHED` — это блокировка головы очереди по мере сил, а не глобальный замок. Сообщение,
исчерпавшее повторы и ушедшее в `DEAD_LETTER`, больше не блокирует последующие сообщения того же ключа
(иначе намертво застрявшее сообщение заклинило бы весь ключ). Полностью выключается через
`ludwig.outbox.ordering.enabled=false`.

## Идемпотентность

Задайте `OutboxEvent.idempotencyKey(...)`; повторная публикация того же ключа возвращает уже
сохранённую строку вместо вставки дубликата. Обеспечивается уникальным частичным индексом в схеме.
По-настоящему конкурентная двойная публикация одного ключа прерывает транзакцию вызывающего с
`DataIntegrityViolationException` (штатное поведение Postgres/JDBC при нарушении ограничения) —
трактуйте это как «дубликат, уже обработано» в собственной обработке ошибок at-least-once-обработчика.
Полное объяснение — в Javadoc у `OutboxEventPublisher`.

## Планирование

Поллер и сборщик зависших захватов работают на выделенном внутреннем `TaskScheduler`, который модуль
создаёт сам, — ни `@EnableScheduling`, ни бина `TaskScheduler` от приложения не требуется. Оба
выключаются через `ludwig.outbox.polling.enabled=false`.

## Схема (только PostgreSQL)

Поставляется changelog-ом Liquibase по пути `classpath:db/changelog/outbox/outbox-changelog.xml`
(таблицы `outbox_message` и `outbox_status_history`, JSONB для payload и заголовков, частичные индексы
под запрос опроса, ключ упорядочивания и ключ идемпотентности). Применить можно двумя способами:

- **Включить в свой changelog** (показано выше в быстром старте), если миграциями управляете вы сами.
- **Дать примениться самостоятельно**: при наличии `liquibase-core` в classpath
  `OutboxLiquibaseAutoConfiguration` регистрирует его вторым, независимым бином `SpringLiquibase`
  против вашего основного `DataSource`, отдельно от вашего `spring.liquibase.change-log`. Выключается
  через `ludwig.outbox.liquibase.enabled=false`.

Идентификаторы и автор набора изменений (`outbox-NNN`/`ludwig-outbox`) вынесены в собственное
пространство имён, поэтому они никогда не столкнутся с вашими в общей таблице `DATABASECHANGELOG`.

## Репозитории

`OutboxMessageRepository` и `OutboxStatusHistoryRepository` сканируются и получают настоящую реализацию
`getByIdOrThrow` целиком из автоконфигурации этого модуля
(`@EnableJpaRepositories(basePackageClasses = ..., repositoryBaseClass = BaseRepositoryImpl.class)`,
ограниченной пакетами самого модуля) — никакой настройки сканирования репозиториев и сущностей в
приложении не требуется.

## Конфигурация (`ludwig.outbox.*`)

| Свойство | По умолчанию | Значение |
|---|---|---|
| `ludwig.outbox.enabled` | `true` | Общий выключатель автоконфигурации модуля |
| `ludwig.outbox.polling.enabled` | `true` | Включает плановый поллер и сборщик зависших захватов |
| `ludwig.outbox.polling.fixed-delay` | `5s` | Пауза между циклами опроса |
| `ludwig.outbox.polling.initial-delay` | `5s` | Пауза до первого цикла опроса |
| `ludwig.outbox.polling.batch-size` | `100` | Максимум строк, захватываемых за цикл |
| `ludwig.outbox.polling.lock-owner` | имя хоста + случайный суффикс | Значение, записываемое в `locked_by` |
| `ludwig.outbox.processing.stale-timeout` | `5m` | Строка в `PROCESSING` старше этого возвращается в `PENDING` |
| `ludwig.outbox.processing.stale-reclaim-fixed-delay` | `1m` | Как часто работает задача возврата зависших |
| `ludwig.outbox.processing.drain-timeout` | `20s` | Сколько остановка ждёт незавершённый цикл опроса, вместо того чтобы бросить захваченные им строки |
| `ludwig.outbox.retry.max-attempts` | `10` | Попыток до отправки в dead-letter |
| `ludwig.outbox.retry.initial-interval` | `1s` | Первый интервал backoff |
| `ludwig.outbox.retry.multiplier` | `2.0` | Коэффициент роста backoff |
| `ludwig.outbox.retry.max-interval` | `5m` | Потолок backoff |
| `ludwig.outbox.retry.jitter` | `0.2` | Доля случайного разброса каждого интервала, чтобы сообщения, упавшие вместе, не становились готовыми к повтору в один и тот же момент |
| `ludwig.outbox.ordering.enabled` | `true` | Учитывать `OutboxEvent.orderingKey` |
| `ludwig.outbox.idempotency.enabled` | `true` | Учитывать `OutboxEvent.idempotencyKey` |
| `ludwig.outbox.dead-letter.enabled` | `true` | Переводить исчерпавшие повторы в `DEAD_LETTER` (иначе остаются `FAILED`) |
| `ludwig.outbox.audit.persist-history` | `false` | Дополнительно сохранять каждый переход в `outbox_status_history` |
| `ludwig.outbox.metrics.enabled` | `true` | Публиковать метрики Micrometer (только если он есть в classpath) |
| `ludwig.outbox.liquibase.enabled` | `true` | Применять поставляемый changelog отдельным бином `SpringLiquibase` |
| `ludwig.outbox.rest.connect-timeout` / `read-timeout` | `5s` / `10s` | Таймауты HTTP-клиента REST-отправителя |
| `ludwig.outbox.rest.endpoints.<key>` | — | Логическое имя эндпоинта → URL, для назначений, не являющихся полным URL |
| `ludwig.outbox.kafka.send-timeout` | `10s` | Максимум ожидания подтверждения брокера на сообщение |
| `ludwig.outbox.routes.<name>.*` / `ludwig.outbox.default-route.*` | — | См. [Маршрутизацию](#маршрутизация-по-нескольким-назначениям) |

Помимо счётчиков публикаций, фильтраций и отправок и длительности вызова отправки, метрика
`ludwig.outbox.latency` фиксирует полное сквозное время от `OutboxMessage.createdAt` до момента, когда
сообщение записано как `PUBLISHED`, — включая интервал опроса, backoff повторов и время ожидания в
очереди, а не только сам вызов отправки. Именно на эту метрику стоит вешать SLA и алерт.

## Тестирование

`mvn test` прогоняет модульные тесты (расчёт backoff, разрешение маршрутов, фильтрация, сериализация,
публикатор) без внешних зависимостей. Интеграционный тест дополнительно поднимает настоящий контейнер
PostgreSQL через Testcontainers — применяя реальный changelog Liquibase и сверяя его с JPA-маппингами
через `ddl-auto=validate`, — чтобы сквозь всё пройти конкурентный захват `FOR UPDATE SKIP LOCKED`,
упорядочивание по ключу, идемпотентность, повторы с dead-letter и восстановление зависших захватов; ему
нужен работающий демон Docker.

## Аудит

У этого модуля больше нет собственного механизма аудита. `Slf4jOutboxAuditLogger` удалён, его место занял `AuditSinkOutboxAuditLogger`. Его журнал теперь идёт через
единственный в платформе `AuditSink`, который развёртывание направляет в лог, в таблицу `audit_event`
(только на добавление), в SIEM через транзакционный outbox или в несколько мест сразу - см.
[`audit-core`](../audit-core) and [`audit-spring-boot-starter`](../audit-spring-boot-starter).

**`OutboxAuditLogger` и `PersistingOutboxAuditLogger` остаются, и `outbox_status_history` тоже.**
Это единственное место в репозитории, где два хранилища аудита - правильный ответ, и причина записана
прямо в `PersistingOutboxAuditLogger`, чтобы кто-нибудь позже не "доделал работу":
`user_setting_audit` и `sync_audit_record` были журналами аудита и ничем больше, а эта таблица -
операционное состояние, *которое читает сам диспетчер*: число попыток, последняя ошибка, когда
сообщение пробовали в последний раз. Складывание её в общую таблицу связало бы отправку с retention
аудита, и развёртывание, укоротившее окно аудита, укоротило бы вместе с ним память диспетчера.

Каждый переход теперь *дополнительно* доходит до журнала платформы через `OutboxTransitionAudit`,
поэтому "кому так и не сообщили, что произошло" отвечаемо без знания схемы этого модуля.
`DEAD_LETTER` и `FAILED` записываются с исходом `FAILURE`; полезная нагрузка не включается никогда, по
той же причине, которую про тела называет каждый другой модуль.

**Что заметит развёртывание:** логгера `ru.ludwigandreas.outbox.audit` больше нет; переходы - в `ru.ludwigandreas.audit` с
`category=outbox`. Сервис, публикующий свой `OutboxAuditLogger`, по-прежнему выигрывает и теперь сам
отвечает за пересылку в журнал - поставляемая обвязка композирует оба, а не выбирает.
