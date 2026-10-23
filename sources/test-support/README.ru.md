# test-support

*[English](README.md) · **Русский***

Единственное место, где записаны образы контейнеров платформы, и сами контейнеры и фикстуры для контейнерных тестов:
закреплённые по digest константы `DockerImageName` из `images.properties`, по одному общему синглтону
PostgreSQL/Kafka/LocalStack/Redis на JVM, связывание через `@ServiceConnection`, составные тестовые аннотации,
очистка между тестами, внутрипроцессные заглушки SMTP и HTTP, управляемый `Clock` и проверка публикуемых
API-документов.

| | |
|---|---|
| Роль | поддержка тестов (jar с областью test) |
| Зависит от (в репозитории) | ничего - и не должен |
| Используется | почти всеми модулями с интеграционными тестами (см. `PROJECT_INDEX.md`) |
| Поверхности | нет |

## Использование

```xml
<dependency>
    <groupId>ru.ludwigandreas</groupId>
    <artifactId>test-support</artifactId>
    <scope>test</scope>
</dependency>
```

```java
@LudwigPostgresTest
class SomethingIntegrationTest {
    // контекст подключён к работающему PostgreSQL с закреплённым digest
}
```

или вместе с существующим `@SpringBootTest`:

```java
@SpringBootTest(classes = TestApplication.class)
@Import(PostgresContainerConfiguration.class)
class SomethingIntegrationTest { }
```

**Называйте контейнерные тесты `*IT` или `*IntegrationTest`**: surefire исключает эти имена, а failsafe запускает
только их, поэтому контейнерный тест с другим именем выполнится не в той фазе (и упадёт без Docker).

| Тип | Что это |
|---|---|
| `LudwigTestImages` | `POSTGRES`, `KAFKA`, `LOCALSTACK`, `REDIS` как `DockerImageName` из `images.properties`, с применённым `asCompatibleSubstituteFor(...)` |
| `Containers` | Один контейнер на образ на JVM, запускается лениво; `stop()` и `close()` ничего не делают |
| `PostgresContainerConfiguration` | `@TestConfiguration`, отдающая PostgreSQL через `@ServiceConnection` |
| `KafkaPropertiesInitializer` | Направляет `spring.kafka.bootstrap-servers` на общий брокер |
| `LocalStackS3Initializer` / `LocalStackS3` | Направляет `ludwig.storage.s3.*` на LocalStack; SDK-клиент плюс идемпотентное создание/очистка бакетов |
| `Containers.redis()` | Общий Redis (общий уровень кэша); порт читайте из контейнера (`REDIS_PORT` - внутренний порт) |
| `@LudwigPostgresTest`, `@LudwigKafkaTest`, `@LudwigLocalStackTest` | Составные аннотации |
| `DatabaseCleaner` | `TRUNCATE ... RESTART IDENTITY CASCADE` всех таблиц, кроме Liquibase |
| `GreenMailFixture`, `WireMockFixture` | Внутрипроцессные заглушки SMTP и HTTP (заглушки для `@LudwigRestClient` - в [test-support-local](../test-support-local)) |
| `TestClock` | Изменяемый `Clock`: `advance(Duration)`, `set(Instant)`, фабрика фиксированных часов |
| `docs.PublishedDocument` | Основа каждого `ApiDocumentIT` / `AsyncApiDocumentIT`: канонизирует документ, пишет `target/<kind>/`, сравнивает с `docs/api/`, перезаписывает при `-Dludwig.apidocs.write=true` |

### Изоляция при общем контейнере

Общий контейнер не даёт каждому классу пустую БД. Очищайте в `@BeforeEach`:

```java
@BeforeEach
void reset() {
    new DatabaseCleaner(dataSource).clean();
}
```

Особенно важно, когда тест проверяет общее состояние (аренду, окно rate limit, строку outbox).
`LocalStackS3.clearBucket(...)` - аналог для S3. Тест, которому нужно остановить контейнер, создаёт свой из
`LudwigTestImages`.

## Конфигурация

Закрепления образов - в `src/main/resources/images.properties`, у каждого комментарий `# renovate:`, чтобы бот
зависимостей мог его обновлять:

```properties
# renovate: datasource=docker depName=postgres
ludwig.test.image.postgres = postgres:16-alpine@sha256:cf78e766...
```

`LudwigTestImages` не инициализируется, если у закрепления нет `@sha256:` с 64-символьным hex-digest. Опционально:
`testcontainers.reuse.enable=true` в `~/.testcontainers.properties` переиспользует контейнеры локально между
запусками (каждый синглтон - `withReuse(true)`); в CI держите выключенным (переиспользованный контейнер содержит
прежние строки).

## Развёртывание

Не применяется (только область test). Агентам CI нужен демон Docker; без него контейнерные тесты **падают** (а не
пропускаются).

## Наблюдаемость

Не применяется.

## Аудит

Не применяется.

## Runbook

| Симптом | Вероятная причина | Что делать |
|---|---|---|
| `Could not find a valid Docker environment` | Docker не запущен | Запустить Docker (нужен для `mvn verify` / `mvn clean install`) |
| `LudwigTestImages` не инициализируется | Закрепление без digest | Добавить `@sha256:<64 hex>` |
| Тесты проходят по отдельности, но не вместе | Общее состояние контейнера | Добавить `DatabaseCleaner` / очистку бакета в `@BeforeEach` |
| `connection refused` в последующих классах | Кто-то остановил общий контейнер (не должно быть возможно) | Не оборачивать контейнеры из `Containers` в `@Container`; создавать свои из `LudwigTestImages` |
| Обновление образа | Пересборка из-за CVE или апгрейд | Изменить одну строку в `images.properties` (с digest), запустить `scripts/check_image_pins.sh` и затронутые наборы |

## Тестирование

Модульный тест на отказ закрепления без digest и `PostgresFixtureIntegrationTest`, поднимающий контекст через
`@LudwigPostgresTest` (доказывает, что `@ServiceConnection` разрешается для имени образа с digest). Порога покрытия
нет: классы модуля проверяются наборами других модулей.

## Проектные решения

- **Digest-и в одном properties-файле**, а не в Java-константах: однострочные обновления ботом, одно место для аудита.
- **Один контейнер на JVM на образ**: Maven запускает один переиспользуемый fork на модуль, поэтому классы модуля его
  делят; между модулями (разными JVM) - нет.
- **Заёмщик не может остановить контейнер** (Spring, JUnit и try-with-resources все пытаются); Ryuk удаляет их при
  выходе JVM.
- **Нет зависимостей в репозитории**: DAG реактора Maven учитывает область test, а модуль используют `db-core` и
  `audit-core`.
- **Не `test-jar`**; обычный jar в области test.
- **Нет security-фикстур** (им нужен `LudwigPrincipal`; см. [test-support-security](../test-support-security)), нет
  контейнеризованных GreenMail/WireMock (внутрипроцессные библиотеки), нет создания бакетов и топиков (словарь
  потребителя).
