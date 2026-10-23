# asyncapi-spring-boot-starter

*[English](README.md) · **Русский***

Генерирует Kafka-поверхность сервиса как документ AsyncAPI 3.0 из его настоящего контекста приложения.
[Springwolf](https://www.springwolf.dev) сканирует методы `@KafkaListener`; модуль добавляет то, чего
Springwolf знать не может, - кластеры, dead-letter каналы, заголовки платформенного конверта и события,
которые сервис публикует через outbox. Документ коммитится в `docs/api/` и проверяется на расхождение так
же, как OpenAPI-документ (см. [`docs/api/README.md`](../../docs/api/README.md)).

| | |
|---|---|
| Роль | starter (документация на этапе сборки) |
| Зависит от (в репозитории) | [`messaging-spring-boot-starter`](../messaging-spring-boot-starter), [`outbox-spring-boot-starter`](../outbox-spring-boot-starter) |
| Используется | `crud-service-example`, `notification-service` |
| Поверхности | собственных нет (эндпоинт документа Springwolf) |

## Использование

```xml
<dependency>
    <groupId>ru.ludwigandreas</groupId>
    <artifactId>asyncapi-spring-boot-starter</artifactId>
</dependency>
```

1. Объявите, что сервис публикует (ниже).
2. Добавьте `AsyncApiDocumentIT` рядом с `ApiDocumentIT` сервиса:

   ```java
   @Test
   void committedDocumentIsCurrent() throws Exception {
       PublishedDocument.check(ARTIFACT_ID, PublishedDocument.Kind.ASYNCAPI,
               objectMapper.readTree(serializer.toJsonString(asyncApi.getAsyncAPI())), AsyncApiDocumentIT.class);
   }
   ```

3. Первый запуск падает и пишет `target/asyncapi/<artifactId>.asyncapi.yaml`. Выполните
   `scripts/api-docs.sh refresh` и закоммитьте документ.

### Что содержит документ

| Часть | Источник |
|---|---|
| Операция receive на каждый listener со схемой payload | Сканирование Springwolf. Listener `ConsumerRecord<String, T>` документирует `T`; текстовый listener - строку |
| Кластер, к которому привязан канал потребителя | `ludwig.messaging.consumers.<name>.cluster` |
| Канал dead-letter топика каждого потребителя на dead-letter кластере | `dead-letter.cluster`, `dead-letter.suffix` |
| Операция send на каждый объявленный тип события outbox, маршрутизированный в Kafka | `OutboxEventTypes` и `ludwig.outbox.routes.*` |
| Платформенный конверт как заголовки каждого сообщения | `MessageHeaders` плюс настроенный заголовок корреляции |
| Один server на Kafka-кластер, именованный; его host - имя кластера, а не адрес | `default` и каждый ключ `ludwig.kafka.clusters` |

Топик, который сервис только читает, документируется как операция receive с ожидаемой формой; документ
не претендует на владение им. События, маршрутизированные через REST, в документ не попадают.

### Объявление того, что публикует outbox

Payload outbox имеет тип `Object`, поэтому сервис объявляет каждый тип события один раз:

```java
@Bean
OutboxEventTypes catalogEvents() {
    return OutboxEventTypes.of(Map.of("ProductCreated", ProductEventPayload.class));
}
```

Топик и кластер берутся из маршрутов outbox. Событие, публикуемое по маршруту по имени через
`OutboxEvent.route(...)`, объявляет маршрут, потому что этот выбор сделан в коде:

```java
OutboxEventTypes.routed("NotifyProductCreated", ProductEventPayload.class, "product-notifications")
```

Выставьте `ludwig.outbox.events.require-declared=true` в **тестовых** свойствах сервиса: публикатор
тогда отказывает необъявленным типам, чужим классам payload и необъявленным маршрутам, и
недокументированный продюсер валит тест. Это покрывает только публикации, которые проходит какой-то тест,
- тип события это строка времени выполнения, статическая проверка невозможна.

## Конфигурация

Модуль задаёт обязательные для Springwolf значения с наименьшим приоритетом; сервис может переопределить
любое.

| Свойство | По умолчанию | Почему |
|---|---|---|
| `springwolf.docket.servers.<cluster>.protocol` / `.host` | `kafka` / имя кластера, по одному на кластер | Закоммиченный документ называет кластеры, а не адреса |
| `springwolf.docket.base-package` | `ru.ludwigandreas` | Listener-ы платформенных стартеров - часть поверхности сервиса |
| `springwolf.docket.info.title` | `spring.application.name` | |
| `springwolf.docket.info.version` | `current` | Версию несёт координата в Nexus; версия внутри документа делала бы расхождением каждый релиз |
| `springwolf.payload.extractable-classes[org.apache.kafka.clients.consumer.ConsumerRecord]` | `1` | Без него listener `ConsumerRecord<String, T>` документируется как пустой объект |
| `springdoc.packages-to-exclude` | `io.github.springwolf` | Не пускает эндпоинты Springwolf в OpenAPI-документ. Сервис, задающий это свойство, должен включить пакет |
| `springwolf.use-fqn` | `false` | `true` протаскивает полные имена схем в OpenAPI-документ (Springwolf на время сканирования ставит JVM-глобальный флаг swagger) |

Springwolf закреплён на 1.9.0 в `ludwig-bom` - последнем релизе под Spring Boot 3.3.x. Обновляйте его
вместе со Spring Boot.

## Развёртывание

Развёртывать нечего. Закоммиченный документ публикуется в Nexus конвейером; см.
[`docs/api/README.md`](../../docs/api/README.md#publishing-to-nexus).

## Наблюдаемость

Не применяется: модуль только строит документ при старте и не добавляет поведения в обработку сообщений.

## Аудит

Не применяется: изменений состояния нет.

## Runbook

| Симптом | Вероятная причина | Что делать |
|---|---|---|
| `AsyncApiDocumentIT` падает с diff | Изменилась Kafka-поверхность сервиса (новый listener, маршрут, поле payload) | Прочитать diff. Если так и задумано: `scripts/api-docs.sh refresh`, закоммитить документ вместе с изменением |
| Payload listener-а описан как пустой объект | Удалено переопределение `extractable-classes` | Вернуть значение по умолчанию |
| Событие outbox отсутствует в документе | Не объявлено в `OutboxEventTypes` или маршрутизировано через REST | Объявить; REST-маршруты вне области документа |
| Эндпоинты Springwolf появились в OpenAPI-документе | Сервис переопределил `springdoc.packages-to-exclude` без `io.github.springwolf` | Добавить пакет |

## Тестирование

`AsyncApiDocumentShapeIT` строит документ из контекста с listener-ами, кластерами, маршрутами и
объявленными событиями (без брокера и Docker) и проверяет его форму, в том числе что объявленное событие
без Kafka-маршрута отсутствует. Каждый сервис-потребитель имеет свой `AsyncApiDocumentIT`.

## Проектные решения

- **Проверка расхождения, а не совместимости.** Ломающее изменение payload проходит сборку и видно как diff
  на ревью.
- **Без UI Springwolf.** Потребители читают опубликованный документ из Nexus.
- **Без аннотаций `@AsyncPublisher`.** Маршруты outbox плюс `OutboxEventTypes` - единственное место
  описания продюсера, поэтому топик и кластер не могут разойтись между аннотацией и маршрутизацией.
