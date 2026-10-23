# messaging-spring-boot-starter

*[English](README.md) · **Русский***

Сторона потребителя платформенного Kafka-конверта. Модуль строит каждую фабрику контейнеров
`@KafkaListener` по одному набору решений - безопасная десериализация, backoff и затем dead-letter топик,
подтверждение по записи, контроль версии payload, дедупликация на стороне потребителя, метрики лага и
тишины, несколько Kafka-кластеров - и владеет именами заголовков, которые пишет
[`outbox-spring-boot-starter`](../outbox-spring-boot-starter). Сервис объявляет свой listener, топик и тип
payload; всё остальное берётся отсюда.

| | |
|---|---|
| Роль | starter |
| Зависит от (в репозитории) | [`audit-core`](../audit-core); опционально: [`idempotency-spring-boot-starter`](../idempotency-spring-boot-starter), [`web-core-spring-boot-starter`](../web-core-spring-boot-starter) |
| Используется | стартерами `outbox`, `asyncapi`, `identity-projection`, `messaging-db`, `user-settings`; `notification-service` |
| Поверхности | Kafka (входящие; исходящие - в dead-letter топики) |

Пакеты:

```
ru.ludwigandreas.messaging
├── api             MessageHeaders, InboundEnvelope, EnvelopeReader, DeadLetterTopics
├── settings        MessagingProperties, разрешение настроек потребителя, KafkaClusters
├── config          автоконфигурации
├── consumer        ListenerContainerFactoryBuilder - единый набор решений
├── serialization   безопасный JSON-десериализатор и контроль версии
├── dlt             dead-letter продюсер и аудируемый recoverer
├── audit           DeadLetterAudit
├── metrics         счётчики и gauge тишины
└── error           две ошибки, которые бросает модуль
```

## Использование

```xml
<dependency>
    <groupId>ru.ludwigandreas</groupId>
    <artifactId>messaging-spring-boot-starter</artifactId>
</dependency>
```

Попросите у билдера фабрику ровно под тип payload и зарегистрируйте её под собственным именем бина:

```java
@Bean(CONTAINER_FACTORY)
ConcurrentKafkaListenerContainerFactory<String, OrderCreated> orderListenerContainerFactory(
        ListenerContainerFactoryBuilder builder, OrderProperties properties) {
    return builder.forJsonPayload("orders-ingress", OrderCreated.class, properties.getTopic());
}

@KafkaListener(topics = "${orders.topic}", containerFactory = CONTAINER_FACTORY)
void onOrderCreated(OrderCreated event, @Header(MessageHeaders.EVENT_TYPE) String type) { ... }
```

- `forTextPayload(name, topics...)` строит фабрику со `String`-payload для listener-а, который
  диспетчеризует по `ludwig-event-type` несколько типов payload в одном топике.
- Первый аргумент - **имя потребителя**: ключ, под которым лежат его переопределения в
  `ludwig.messaging.consumers.*`. Это не имя бина, поэтому переименование бина никогда молча не меняет
  конфигурацию.
- Фабрика конструируется под собственный тип потребителя; ничего общего не ищется и не приводится.
- **Listener не должен объявлять параметр `Acknowledgment`.** Режим подтверждения по умолчанию -
  `RECORD`, в нём Spring не передаёт `Acknowledgment`; параметр разрешается как payload, и каждая запись
  падает с `Payload value must not be empty`, после чего уходит в dead-letter.

`InboundEnvelope` / `EnvelopeReader` читают заголовки конверта из записи.

### Конверт

| Заголовок | Несёт |
|---|---|
| `ludwig-event-type` | вид события; по нему диспетчеризует потребитель |
| `ludwig-event-version` | версия схемы payload - см. [Версии схемы payload](#версии-схемы-payload) |
| `ludwig-aggregate-type` | тип агрегата, о котором событие |
| `ludwig-aggregate-id` | экземпляр агрегата |
| `ludwig-idempotency-key` | ключ дедупликации продюсера, стабильный при повторных публикациях и доставках |
| `ludwig-trace-id` | W3C trace id, если трасса продюсера была засэмплирована |
| `ludwig-produced-at` | когда продюсер отправил событие, ISO-8601 UTC (не timestamp брокера) |
| `X-Correlation-Id` | correlation id |

`X-Correlation-Id` сохраняет имя, которое [`observability-spring-boot-starter`](../observability-spring-boot-starter)
использует в HTTP и Kafka, чтобы у одного id было одно имя везде. Оно настраивается там, и `EnvelopeReader`
учитывает переопределение. Читаются только эти имена; запись без `ludwig-event-type` имеет неизвестный тип.

### Обработка ошибок

Каждая фабрика, которую строит билдер, имеет:

- **`ErrorHandlingDeserializer` вокруг ключа и значения.** Битый payload превращается в запись, которую
  обработчик ошибок может классифицировать и отправить в dead-letter, а не в бесконечный цикл poison pill.
- **Auto-commit выключен.** Контейнер коммитит после возврата из listener-а. Поэтому сбой БД никогда не
  сдвигает offset за необработанные записи.
- **`DefaultErrorHandler` с экспоненциальным backoff**: 1s × 3, потолок 30s, **4 доставки, включая
  первую**, переопределяется на потребителя. Backoff выполняется в контейнере: партиция встаёт на паузу,
  и повторяемая запись сохраняет порядок относительно записей за ней.
- **Неповторяемые `DeserializationException` и `MessageConversionException`**: payload, который нельзя
  разобрать, уходит в dead-letter топик с первой попытки.
- **`DeadLetterPublishingRecoverer` в `<topic>.dlt`, партиция `-1`**: партицию выбирает брокер, поэтому
  dead-letter топику не нужно столько же партиций, сколько у исходного.
- **Dead-letter продюсер, умеющий отправлять сырые байты** (`DeadLetterTemplates`,
  `DelegatingByTypeSerializer`), потому что запись, не прошедшая десериализацию, переотправляется как
  исходный `byte[]`.

Каждая отправка в dead-letter считается и аудируется. Отправка в dead-letter, которая сама не удалась,
считается и аудируется отдельно (`dropped`): запись потеряна.

### Подтверждение

`RECORD`: запись коммитится, когда её listener вернул управление, и её ошибка не влияет на другие записи
poll-а. `MANUAL` доступен для потребителя, который действительно работает пачками, и только при явном
`ludwig.messaging.consumers.<name>.ack-mode`.

### Дедупликация на стороне потребителя

Хранилище дедупликации - [`idempotency-spring-boot-starter`](../idempotency-spring-boot-starter). Этот
модуль подключает любой `RecordFilterStrategy` из контекста ко всем фабрикам, которые строит, поэтому
развёртывание с `ludwig.idempotency.kafka.enabled=true` получает дедупликацию на всех потребителях по
`ludwig-idempotency-key`.

- Отключение для потребителя: `ludwig.messaging.consumers.<name>.dedup=false`. Проекция, сходящаяся к
  значению, должна отключать: она и так корректна при повторе, а claim добавляет запись в таблицу и
  режим отказа.
- **Claim требует транзакции listener-а.** `TRANSACTIONAL` claim на контейнере без менеджера транзакций
  коммитится вне работы listener-а, поэтому откатившаяся запись остаётся занятой, а её повторная доставка
  отбрасывается как дубликат. Модуль подключает менеджер транзакций приложения при
  `ludwig.idempotency.kafka.mode=TRANSACTIONAL` и отказывается стартовать, если против этого режима
  задано `ludwig.messaging.dedup.container-transaction=false`.

### Версии схемы payload

Потребитель объявляет версии, которые понимает:

```yaml
ludwig:
  messaging:
    consumers:
      orders-ingress:
        accepted-versions: { min: 1, max: 1 }
```

`VersionGatingDeserializer` читает `ludwig-event-version` до разбора и отказывает версии вне диапазона
неповторяемым `DeserializationException`, поэтому запись уходит в dead-letter топик с первой попытки.
Payload версии 2 никогда не отображается на тип версии 1. По умолчанию диапазон не ограничен.

Чтобы отправлять неизвестные версии в отдельный топик, задайте потребителю `dead-letter.suffix`.

### Неблокирующие retry-топики

Включаются для конкретного топика и запрещены потребителю, полагающемуся на порядок: неблокирующие
повторы переотправляют упавшую запись, и она применяется **после** записей, созданных позже, что ломает
порядок по ключу, обещанный `orderingKey` продюсера.

```yaml
ludwig:
  messaging:
    retry-topics.enabled: true          # включает @EnableKafkaRetryTopic для приложения
    consumers:
      search-index-feed:
        ordered: false                  # обязательно, иначе старт падает
        topics: [ search.index.feed ]   # обязательно, иначе конфигурация не применяется ни к одному топику
        retry.non-blocking: true
```

- `ludwig.messaging.defaults.retry.non-blocking` запрещено: отказ от порядка - решение про конкретный топик.
- Модуль предоставляет однопоточный `RetryTopicSchedulerWrapper`, если в приложении его нет, чтобы
  `@EnableKafkaRetryTopic` стартовал без второго бина `TaskScheduler`, который перехватил бы методы
  `@Scheduled` приложения.
- Retry-топики остаются на кластере `default`.

### Kafka clusters

Сервис может читать из нескольких кластеров и отправлять в них dead-letter. `spring.kafka.*` - это
кластер с именем `default`. Именованный кластер имеет форму `spring.kafka.*`, связывается поверх неё и
указывает только отличия:

```yaml
ludwig:
  kafka:
    clusters:
      partner-a: { bootstrap-servers: a.partner:9093, security: { protocol: SSL } }
      internal:  { bootstrap-servers: our-kafka:9092 }
  messaging:
    consumers:
      orders-ingress:
        cluster: partner-a                 # откуда читает
        dead-letter: { cluster: internal } # куда идут его dead-letter; по умолчанию `source`
```

- **Один кластер на потребителя.** Чтение двух кластеров требует двух listener-ов. Кластер - это
  конфигурация по имени потребителя; код listener-а не меняется.
- **Dead-letter** идут в кластер, откуда пришла запись (`source`), если `dead-letter.cluster` не называет
  другой. `ludwig.messaging.defaults.dead-letter.cluster` задаёт его для всех потребителей.
- **Дедупликация - по кластеру.** Scope claim-а - `kafka:<cluster>/<topic>`; для `default` - `kafka:<topic>`.
  Собственный `RecordFilterStrategy` должен учитывать кластер сам.
- **Метрики** несут тег `cluster`.
- **Продюсеры.** Каждый продюсер платформы (dead-letter шаблоны, шаблоны outbox) берётся из бина
  `KafkaClusterProducers`. Архитектурное правило `kafka.producers-come-from-the-cluster-seam` удерживает
  его единственным местом.
- Имена кластеров: строчные буквы, цифры и дефисы, не длиннее 44 символов (чтобы транспорт outbox
  `kafka:<name>` помещался в колонку). `default` зарезервировано. Потребитель с необъявленным кластером
  валит старт.

`ListenerContainerFactoryBuilder.consumerNameOf(factory)` возвращает имя потребителя фабрики;
[`asyncapi-spring-boot-starter`](../asyncapi-spring-boot-starter) использует его, чтобы описать каждый
listener с его кластером и dead-letter каналом.

### Безопасность по умолчанию

`setUseTypeHeaders(false)` на каждом JSON-десериализаторе, **без свойства, которое это включает**. С
type-заголовками класс, который Jackson создаёт у потребителя, выбирает продюсер - это гаджет
десериализации. Потребитель с несколькими типами payload в одном топике диспетчеризует по
`ludwig-event-type` на заранее известные ему типы.

## Конфигурация

Каждый ключ в `ludwig.messaging.consumers.<name>` есть и в `ludwig.messaging.defaults` (для всех
потребителей). Блок потребителя переопределяет только то, что называет.

| Свойство | По умолчанию | Смысл |
|---|---|---|
| `ludwig.messaging.enabled` | `true` | Главный выключатель (также требует `@KafkaListener` в classpath) |
| `ludwig.kafka.clusters.<name>.*` | нет | Именованный кластер; форма `spring.kafka.*`, связывается поверх неё |
| `ludwig.messaging.correlation-header` | `X-Correlation-Id` | Должен совпадать с `ludwig.observability.correlation.kafka.header-name` |
| `ludwig.messaging.metrics.enabled` | `true` | Метрики модуля и метрики Kafka-клиента (лаг) |
| `ludwig.messaging.dedup.enabled` | `true` | Подключать `RecordFilterStrategy` из контекста, если он есть |
| `ludwig.messaging.dedup.container-transaction` | из режима claim | `false` при `TRANSACTIONAL` запрещено |
| `ludwig.messaging.retry-topics.enabled` | `false` | Включить инфраструктуру неблокирующих повторов для приложения |
| `…consumers.<name>.ack-mode` | `RECORD` | `MANUAL` для потребителя, который действительно работает пачками |
| `…consumers.<name>.concurrency` | из фабрики | Потоки listener-а |
| `…consumers.<name>.ordered` | `true` | `false` обязательно для неблокирующих повторов |
| `…consumers.<name>.dedup` | `true` | `false` для сходящейся проекции |
| `…consumers.<name>.silence-threshold` | нет | Без него gauge тишины не создаётся |
| `…consumers.<name>.retry.initial-backoff` | `1s` | |
| `…consumers.<name>.retry.multiplier` | `3.0` | |
| `…consumers.<name>.retry.max-backoff` | `30s` | |
| `…consumers.<name>.retry.max-attempts` | `4` | **Доставок, включая первую** |
| `…consumers.<name>.retry.non-blocking` | `false` | См. [Неблокирующие retry-топики](#неблокирующие-retry-топики) |
| `…consumers.<name>.dead-letter.enabled` | `true` | `false` = залогировать и отбросить |
| `…consumers.<name>.dead-letter.suffix` | `.dlt` | |
| `…consumers.<name>.dead-letter.cluster` | `source` | `source` или объявленный кластер |
| `…consumers.<name>.cluster` | `default` | Кластер, объявленный в `ludwig.kafka.clusters` |
| `…consumers.<name>.accepted-versions.min` / `.max` | `1` / без ограничения | Граница включает контроль версии |
| `…consumers.<name>.topics` | пусто | Нужно только для неблокирующих повторов |

## Развёртывание

**Dead-letter топики должны существовать до выкатки сервиса.** В продакшен-кластере обычно
`auto.create.topics.enable=false`; без dead-letter топика первая «ядовитая» запись не уходит в dead-letter,
её offset коммитится, и запись теряется (`ludwig.messaging.dropped` > 0).

| Исходный топик | Dead-letter топик |
|---|---|
| ingress уведомлений (`ludwig.notification.ingress.topic`) | `<topic>.dlt` |
| `ludwig.user-settings.projection.topic` (по умолчанию `user.settings`) | `user.settings.dlt` |
| `ludwig.identity.kafka.topic` (по умолчанию `oidc.users`) | `oidc.users.dlt` |
| любой другой топик потребителя | `<topic><dead-letter.suffix>` |

Число партиций и репликация могут не совпадать с исходным топиком (recoverer отправляет в партицию `-1`).
Задайте dead-letter топику **больший срок хранения**, чем у исходного: его читает человек, возможно после
выходных.

Учётные данные именованных кластеров передаются в `ludwig.kafka.clusters.<name>.*` из хранилища секретов,
никогда не в образ.

## Наблюдаемость

**Метрики:**

| Метрика | Тип | Теги | Смысл |
|---|---|---|---|
| `kafka.consumer.fetch.manager.records.lag` | gauge | теги клиента (topic, partition, group) | Лаг потребителя от Kafka-клиента (`MicrometerConsumerListener` на каждой фабрике) |
| `ludwig.messaging.consumed` | counter | `cluster`, `topic` | Записи, переданные listener-у |
| `ludwig.messaging.retries` | counter | `cluster`, `topic` | Повторные попытки |
| `ludwig.messaging.dead.lettered` | counter | `cluster`, `topic`, `dlt`, `exception` | Записи, отправленные в dead-letter топик |
| `ludwig.messaging.dropped` | counter | `cluster`, `topic` | Неудачные отправки в dead-letter - запись потеряна |
| `ludwig.messaging.silent` | gauge | `cluster`, `topic` | `1`, если ничего не пришло за `silence-threshold` |

`silent` ловит то, чего не видит лаг: топик без трафика, мёртвый контейнер и опечатка в group id дают
нулевой лаг. Отсчёт начинается при **старте**, поэтому сообщается и потребитель, который вообще ничего не
получил. Gauge вычисляется при scrape, а не публикуется планировщиком, который сам может остановиться.

**Логи.** `WARN` на каждую запись в dead-letter (координаты, попытки, исключение); `ERROR`, если
отправка в dead-letter не удалась; `WARN` при старте для дедупликации без менеджера транзакций и для
`retry-topics.enabled` без неблокирующих потребителей. Payload никогда не логируется.

**Трассировка.** Interceptor корреляции из `observability-spring-boot-starter` встраивается в каждую
фабрику, поэтому correlation id попадает в MDC каждого потока listener-а.

**Health.** Health-индикатора нет; состояние потребителя видно по лагу и `silent`.

**Рекомендуемые алерты:**

| Условие | Почему |
|---|---|
| `increase(ludwig_messaging_dropped_total[5m]) > 0` | Записи теряются; обычно нет dead-letter топика. Будить дежурного |
| `increase(ludwig_messaging_dead_lettered_total[15m]) > 0` | Записи не обработаны и требуют человека |
| `ludwig_messaging_silent == 1` | Потребитель ничего не получал дольше порога |
| `kafka_consumer_fetch_manager_records_lag` выше допустимого для топика дольше 10 мин | Потребитель отстаёт |

## Аудит

| Поле | Dead-letter | Потеря |
|---|---|---|
| category | `messaging` | `messaging` |
| action | `messaging.dead-lettered` | `messaging.dropped` |
| actor | `system` | `system` |
| resource | координаты записи, тип события | то же |
| outcome | `FAILURE` (запись не обработана) | `FAILURE` |
| attributes | topic, partition, offset, eventType, eventVersion, aggregateType, aggregateId, deadLetterTopic, exception, attempts | то же, `deadLetterTopic` = null |

Correlation и trace id копируются из конверта, поэтому событие связывается со следом продюсера. Payload
никогда не включается.

## Runbook

| Симптом | Вероятная причина | Что делать |
|---|---|---|
| `ludwig_messaging_dropped_total` > 0, `ERROR Failed to dead-letter the record` | Нет dead-letter топика или нет ACL на запись в него | Создать `<topic>.dlt` (или выдать ACL). Потерянные записи этот потребитель больше не увидит: перечитайте их из исходного топика, сдвинув offset группы на координаты из события аудита / лога, или попросите продюсера переопубликовать |
| Растёт `ludwig_messaging_dead_lettered_total` | Битый payload, неподдерживаемая версия или ошибка listener-а | Смотреть тег `exception` и dead-letter топик. Устранить причину, затем переиграть записи из `.dlt` в исходный топик (например, `kafka-console-consumer` в `kafka-console-producer` с заголовками, или replay-эндпоинт) |
| Все записи топика уходят в dead-letter с `Payload value must not be empty` | Listener объявляет параметр `Acknowledgment` | Убрать параметр |
| `ludwig_messaging_silent == 1` | Нет трафика, мёртвый контейнер, неверный group id / топик | Сначала метрики продюсера; затем `kafka-consumer-groups --describe --group <group>` - отсутствие назначенных партиций означает неверный топик или группу |
| Лаг стабильно растёт | Listener медленнее входящего потока или застрял на повторах одной записи | Смотреть `ludwig_messaging_retries_total`; поднять `concurrency` (≤ числа партиций) или устранить медленную зависимость |
| Старт падает: `dedup.container-transaction=false with ... TRANSACTIONAL` | Противоречивые настройки дедупликации | Убрать переопределение или сменить режим claim |
| Старт падает: неблокирующий повтор на упорядоченном потребителе | `retry.non-blocking=true` без `ordered=false` | Решить, может ли топик терять порядок; ставить `ordered: false` только если может |
| Старт падает: неизвестный кластер | Потребитель называет кластер, которого нет в `ludwig.kafka.clusters` | Объявить кластер или исправить имя |

## Тестирование

- Модульные тесты разрешения настроек, контроля версии и билдера.
- Интеграционные тесты на реальной Kafka (`SharedConsumerIT`, `MultiClusterConsumerIT`; Testcontainers,
  нужен Docker): «ядовитые» записи сразу уходят в dead-letter, ограниченное число доставок, отсутствие
  тихих потерь, дедупликация, сохранение порядка при повторе, отказ неизвестным версиям, игнорирование
  типа от продюсера, сообщение о тишине, потребление и dead-letter по кластерам.
- Модуль прогоняет на себе весь набор `architecture-rules`.
- В сервисе-потребителе: тестируйте listener с `@LudwigKafkaTest` из [`test-support`](../test-support),
  включая одну битую запись.

## Проектные решения

- **Один билдер фабрик, с принуждением.** Архитектурные правила `kafka.container-factories-set-an-error-handler`
  (каждая фабрика задаёт обработчик ошибок или берётся из билдера) и `kafka.no-private-dead-letter-recoverer`;
  Checkstyle `SecondDeadLetterSuffix` запрещает вторую константу `.dlt`.
- **`outbox` зависит от этого модуля** ради имён заголовков. Обратное направление было бы циклом в
  реакторе. Чтобы не раздувать транзитивные зависимости outbox, spring-kafka, web-core, validation API и
  стартер идемпотентности здесь опциональны.
- **Здесь нет хранилища дедупликации.** Хранилище - стартер идемпотентности; модуль только подключает его.
- **Нет ограничения версий по умолчанию.** Граница, придуманная платформой, отправила бы в dead-letter
  живой трафик.
- **Три значения «идемпотентности» разделены**: дедупликация публикации в outbox, тип ключа партнёра в
  reconciliation и дедупликация работы в стартере идемпотентности - разные механизмы.
- **web-core опционален.** Здесь ничто не пишет HTTP-ответ; бандл и маппер проблем нужны сервису, который
  пробрасывает ошибку messaging со своей поверхности.
