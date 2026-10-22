# asyncapi-spring-boot-starter

[English version](README.md)

Kafka-поверхность сервиса в виде документа AsyncAPI 3.0, сгенерированного из настоящего контекста
приложения.

[Springwolf](https://www.springwolf.dev) сканирует методы `@KafkaListener`. Этот модуль добавляет то, чего
Springwolf знать не может, чтобы документ описывал то, что на этой платформе действительно передаётся.
Документ коммитится в `docs/api/` и проверяется на расхождение так же, как документ OpenAPI. Проверка,
команда обновления и публикация в Nexus описаны в [`docs/api/README.md`](../../docs/api/README.md).

- [Что содержит документ](#что-содержит-документ)
- [Подключение](#подключение)
- [Объявление того, что производит outbox](#объявление-того-что-производит-outbox)
- [Значения по умолчанию и как их переопределить](#значения-по-умолчанию-и-как-их-переопределить)
- [Чего этот модуль сознательно не делает](#чего-этот-модуль-сознательно-не-делает)

## Что содержит документ

| Часть | Откуда |
|---|---|
| Операция receive на каждого слушателя со схемой полезной нагрузки | Сканирование Springwolf. Слушатель `ConsumerRecord<String, T>` документирует `T`; текстовый слушатель документирует строку |
| Кластер, к которому привязан канал потребителя | `ludwig.messaging.consumers.<name>.cluster` |
| Канал dead-letter топика потребителя на кластере dead letter | `dead-letter.cluster` и `dead-letter.suffix` |
| Операция send на каждый объявленный тип события outbox с маршрутом через Kafka | `OutboxEventTypes` и `ludwig.outbox.routes.*`, см. ниже |
| Конверт платформы как заголовки каждого сообщения | `MessageHeaders` и настроенный заголовок correlation id |
| Один сервер на каждый кластер Kafka, с именем; host — имя, а не адрес | `default` и каждый ключ `ludwig.kafka.clusters` |

Топик, который этот сервис только читает, документируется как операция receive с ожидаемой формой. Ничто
в документе не заявляет, что сервис им владеет.

## Подключение

Добавьте зависимость:

```xml
<dependency>
    <groupId>ru.ludwigandreas</groupId>
    <artifactId>asyncapi-spring-boot-starter</artifactId>
</dependency>
```

Затем объявите, что производит сервис (см. ниже), и добавьте `AsyncApiDocumentIT` рядом с `ApiDocumentIT`
сервиса:

```java
@Test
void committedDocumentIsCurrent() throws Exception {
    PublishedDocument.check(ARTIFACT_ID, PublishedDocument.Kind.ASYNCAPI,
            objectMapper.readTree(serializer.toJsonString(asyncApi.getAsyncAPI())), AsyncApiDocumentIT.class);
}
```

Первый запуск падает и записывает `target/asyncapi/<artifactId>.asyncapi.yaml`. Обновите документ через
`scripts/api-docs.sh refresh` и закоммитьте его.

## Объявление того, что производит outbox

Полезная нагрузка события outbox — это `Object`, поэтому описать её нельзя, пока сервис не скажет, что это.
Сделайте это один раз на каждый тип события:

```java
@Bean
OutboxEventTypes catalogEvents() {
    return OutboxEventTypes.of(Map.of("ProductCreated", ProductEventPayload.class));
}
```

Топик и кластер не повторяются: их уже задают маршруты outbox. Событие, которое код отправляет по маршруту
с именем через `OutboxEvent.route(...)`, указывает этот маршрут, потому что выбор делается в коде и ни одна
конфигурация его не фиксирует:

```java
OutboxEventTypes.routed("NotifyProductCreated", ProductEventPayload.class, "product-notifications")
```

Тип события с маршрутом через REST в этот документ не входит.

Выставьте `ludwig.outbox.events.require-declared=true` в тестовых свойствах сервиса. Тогда публикатор
отвергает необъявленный тип события, полезную нагрузку не того класса или маршрут, отличный от объявленного,
и producer не может остаться
незадокументированным без падения теста. В продакшене настройка выключена. Статическая проверка здесь
невозможна, потому что тип события — строка во время выполнения. Поэтому проверкой служат тесты, и это
работает, только если тест проходит через каждую публикацию.

## Значения по умолчанию и как их переопределить

Springwolf не строит документ без серверов, базового пакета и блока info. Этот модуль задаёт их с самым
низким приоритетом, так что любой из них можно переопределить в конфигурации сервиса:

| Свойство | По умолчанию | Почему |
|---|---|---|
| `springwolf.docket.servers.<cluster>.*` | по одному на кластер, протокол `kafka`, host = имя кластера | закоммиченный документ заменяет host именем сервера (AsyncAPI требует host); читателю нужны имена |
| `springwolf.docket.base-package` | `ru.ludwigandreas` | слушатели из стартеров платформы — часть поверхности сервиса |
| `springwolf.docket.info.title` | `spring.application.name` | |
| `springwolf.docket.info.version` | `current` | настоящая версия — это координата в Nexus; версия внутри документа делала бы каждый выпуск расхождением |
| `springwolf.payload.extractable-classes[org.apache.kafka.clients.consumer.ConsumerRecord]` | `1` | без этого слушатель `ConsumerRecord<String, T>` документируется как пустой объект |
| `springdoc.packages-to-exclude` | `io.github.springwolf` | собственные эндпоинты Springwolf не попадают в OpenAPI-документ сервиса; сервис, задающий это свойство сам, должен включить этот пакет |
| `springwolf.use-fqn` | `false` | на время сканирования Springwolf выставляет общий для JVM флаг имён типов swagger в это значение; `true` протекает полными именами схем в OpenAPI-документ |

Springwolf закреплён на 1.9.0 в `ludwig-bom` — последнем выпуске, собранном под Spring Boot 3.3.x.
Обновляйте его вместе с версией Boot.

## Чего этот модуль сознательно не делает

- **Не проверяет совместимость.** Проверка — только на расхождение. Ломающее изменение полезной нагрузки
  проходит сборку и видно в диффе.
- **Не поставляет UI Springwolf.** Другие команды читают опубликованный документ из Nexus.
- **Не документирует аннотации `@AsyncPublisher`.** Маршруты outbox и `OutboxEventTypes` — единственное
  место, где описан producer, так что топик и кластер не могут разойтись между аннотацией и маршрутизацией.
