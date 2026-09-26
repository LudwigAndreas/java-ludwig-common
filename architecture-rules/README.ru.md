# architecture-rules

*[English](README.md) · **Русский***

Исполняемые архитектурные соглашения для сервисов на Spring Boot: jar с тестовой областью, содержащий
готовые наборы правил [ArchUnit](https://www.archunit.org) — слои, циклы пакетов, граница REST,
хранение в JPA, обмен сообщениями через Kafka и его контракты, хранилище S3, проводка Spring,
архитектура исключений, неизменяемость моделей API, наследование сущностей, версионирование путей
REST, валидация конфигурации, границы модулей, разделение тестов и доступ к конфигурации, — который
сервис включает одной аннотацией. Каждое правило переключается независимо, каждое соглашение
настраивается, и то и другое можно переопределить для одного модуля или списка модулей. Каждый прогон
пишет два отчёта: цветную сводку в консоль для того, у кого сборка только что покраснела, и
JSON-файл для инструментов, кодирующих агентов и общеорганизационного архитектурного дашборда.

## Зачем

Соглашения, живущие в документе, в голове ревьюера или в одном образцовом сервисе, тихо ветшают. Их
переобъясняют на каждом ревью, применяют неравномерно по командам, а расхождение обнаруживают только
когда что-то ломается: JPA-сущность, незаметно ставшая опубликованным REST-контрактом; контроллер,
ходящий в базу мимо границы транзакции; два «модуля», сросшиеся так, что их больше нельзя выпустить
по отдельности.

Поставка соглашений jar-ом меняет того, кто их проверяет. Сервис добавляет одну тестовую зависимость
и одну аннотацию — и с этого момента его собственная сборка доказывает соответствие: на каждом
коммите, до ревью, называя ровно то правило, которое нарушено.

## Быстрый старт

```xml
<dependency>
    <groupId>ru.ludwigandreas</groupId>
    <artifactId>architecture-rules</artifactId>
    <version>1.0.0</version>
    <scope>test</scope>
</dependency>
```

```java
@AnalyzeArchitecture(packagesOf = OrdersApplication.class)
class ArchitectureTest extends ArchitectureRulesTest {
}
```

Это вся интеграция. Каждое включённое правило становится отдельным JUnit-тестом, названным своим
идентификатором, поэтому отчёт сборки читается так:

```
Architecture rules
  +-- layering.layered-architecture                              OK
  +-- web.controllers-do-not-expose-entities                     FAILED
  +-- persistence.entities-reside-in-entity-packages             OK
  +-- cycles.module-internals-are-free-of-cycles[com.acme.orders] OK
```

а после прогона остаются цветная сводка в консоли и машиночитаемый
`target/architecture-report.json` — см. [Отчёты](#отчёты).

Сервис без Kafka выключает соответствующие правила, больше ничего не меняя:

```java
@AnalyzeArchitecture(
        packagesOf = OrdersApplication.class,
        enable = "domain-isolation",
        disable = {"kafka", "storage"})
class ArchitectureTest extends ArchitectureRulesTest {
}
```

## Что проверяется

Девятнадцать групп, 46 правил. Каждый идентификатор ниже — это селектор: передайте идентификатор
группы, чтобы переключить группу, полный идентификатор — чтобы переключить одно правило.

Правила, проверяющие по имени, которое может дать только сама организация (базовое исключение,
базовая сущность, интерфейс издателя), строятся в любом случае. Когда имя не задано, правило валит
**только те классы, которые оно проверило бы**, и в сообщении прямо указаны свойство, которое нужно
выставить, и правило, которое можно выключить. Поэтому сервис без продюсера Kafka, без сущностей или
без собственных исключений остаётся зелёным, ничего не настраивая, а сервис, у которого всё это есть,
не может получить правило, которое молча ничего не проверяет.

### `layering` — контроллер → сервис → репозиторий → домен

| Идентификатор | Что обеспечивает |
|---|---|
| `layering.layered-architecture` | К контроллерам не обращается ничто; сервисный слой достижим только из контроллеров, обмена сообщениями, адаптеров хранилища, мапперов и конфигурации; репозитории — только из сервисного слоя |
| `layering.controllers-do-not-access-persistence` | Та же граница, выраженная прямо зависимостью пакетов, чтобы её нельзя было ослабить определением слоёв, случайно упустившим «ромб» |

Мапперы — отдельный слой: MapStruct-маппер в `..web.mapper..` по определению переводит между моделями
двух слоёв, поэтому ему можно смотреть в обе стороны и он никогда не считается точкой входа.

### `cycles` — никаких циклов пакетов

| Идентификатор | Что обеспечивает |
|---|---|
| `cycles.modules-are-free-of-cycles` | Нет циклов между модулями/ограниченными контекстами сервиса |
| `cycles.module-internals-are-free-of-cycles[<module>]` | Нет циклов между пакетами внутри одного модуля — по экземпляру правила на модуль |

Модули обнаруживаются как прямые подпакеты базовых пакетов, поэтому новая фича покрыта в день своего
создания. Объявите их явно через `modules = {...}`, если сервис нарезан иначе.

### `domain-isolation` — доменная модель без фреймворков (подключается явно)

| Идентификатор | Что обеспечивает |
|---|---|
| `domain-isolation.domain-is-free-of-spring` | Никаких типов Spring в доменных пакетах |
| `domain-isolation.domain-is-free-of-persistence` | Никаких типов и аннотаций JPA/Hibernate в доменных пакетах |
| `domain-isolation.domain-is-free-of-json` | Никакой библиотеки связывания JSON в доменных пакетах |

Выключено по умолчанию: смысл появляется только в сервисе, который держит доменную модель отдельно от
сущностей хранения. Включите через `enable = "domain-isolation"` и направьте `PackageRole.DOMAIN` на
пакеты модели.

### `web` — граница REST

| Идентификатор | Что обеспечивает |
|---|---|
| `web.controllers-do-not-expose-entities` | Никакой JPA-сущности в сигнатуре метода контроллера, включая аргументы типов (`ResponseEntity<PageResponse<ProductEntity>>` ловится) |
| `web.controllers-do-not-use-persistence-types` | Ни репозитория, ни `EntityManager`, ни `JdbcTemplate` внутри контроллера |
| `web.controllers-do-not-call-controllers` | Общее поведение принадлежит сервису, а не второму контроллеру |
| `web.dtos-are-not-jpa-entities` | DTO не несёт аннотаций хранения и не использует API хранения |
| `web.dtos-are-not-event-payloads` | Каждая граница владеет собственной моделью |

### `persistence` — Postgres/JPA

| Идентификатор | Что обеспечивает |
|---|---|
| `persistence.entities-reside-in-entity-packages` | `@Entity`/`@MappedSuperclass`/`@Embeddable` только в пакетах сущностей |
| `persistence.repositories-reside-in-repository-packages` | Репозитории Spring Data и классы с `@Repository` только в пакетах репозиториев |
| `persistence.repositories-are-interfaces` | Репозиторий Spring Data — интерфейс, никогда не класс |
| `persistence.repositories-are-used-only-by-services` | До репозитория дотягивается только сервисный слой (и проводка Spring) |
| `persistence.persistence-context-is-confined` | `EntityManager`, `Session` из Hibernate и JDBC остаются в слое хранения |

### `kafka` — обмен сообщениями

| Идентификатор | Что обеспечивает |
|---|---|
| `kafka.clients-are-confined-to-messaging` | API Kafka трогают только пакеты обмена сообщениями (и конфигурации) |
| `kafka.consumers-reside-in-messaging-packages` | Классы с `@KafkaListener` живут рядом с другими адаптерами |
| `kafka.consumers-do-not-use-repositories` | Консьюмер — точка входа: он вызывает сервисный слой, как контроллер |
| `kafka.payloads-are-free-of-jpa` | Payload — контракт на проводе, а не таблица |
| `kafka.payloads-are-not-rest-dtos` | Топик и HTTP-эндпоинт версионируются отдельно |
| `kafka.messaging-does-not-depend-on-controllers` | Две точки входа не вызывают друг друга |

### `storage` — S3 и другие объектные хранилища

| Идентификатор | Что обеспечивает |
|---|---|
| `storage.sdk-is-confined-to-storage-adapters` | `software.amazon.awssdk..`/`com.amazonaws..` видят только адаптеры хранилища (и конфигурация, собирающая клиент) |
| `storage.adapters-do-not-expose-sdk-types` | *Подключается явно.* Публичные методы адаптера говорят собственными типами сервиса, а не типами SDK |

### `spring` — проводка и транзакции

| Идентификатор | Что обеспечивает |
|---|---|
| `spring.configuration-classes-reside-in-config-packages` | Определения бинов в пакетах конфигурации (класс `@SpringBootApplication` исключён — его место в корневом пакете) |
| `spring.beans-use-constructor-injection` | Никаких `@Autowired`/`@Inject`/`@Resource` на полях |
| `spring.transactional-classes-are-in-the-service-layer` | Классы с `@Transactional` в сервисном слое |
| `spring.transactional-methods-are-in-the-service-layer` | Методы с `@Transactional` в сервисном слое |
| `spring.singleton-beans-have-no-mutable-state` | Никаких нефинальных полей экземпляра у `@Service`/`@Component`/`@RestController`/`@Configuration`/`@ControllerAdvice`/`@RestControllerAdvice` — Spring держит по одному экземпляру каждого, поэтому изменяемое поле разделяется между всеми параллельными запросами |

Внедрение через конструктор живёт здесь, а не в стилевом чекере, потому что это вопрос
зависимостей: бин с внедрением в поле нельзя сконструировать в тесте без контейнера, его поля не
могут быть final, и он скрывает, сколько у него соавторов. Если ваша команда считает это работой
Checkstyle — выключите одно правило.

### `modules` — границы ограниченных контекстов

| Идентификатор | Что обеспечивает |
|---|---|
| `modules.internals-are-not-accessed-from-other-modules` | Ничто не лезет под пакет `internal` другого модуля |
| `modules.cross-module-access-goes-through-the-api-package` | *Подключается явно.* Вариант с белым списком: модуль достижим только через свой пакет `api` |

### `tests` — разделение тестов и продакшена

| Идентификатор | Что обеспечивает |
|---|---|
| `tests.production-code-does-not-depend-on-test-frameworks` | JUnit, Mockito, AssertJ, Testcontainers и прочие остаются в тестовых исходниках — зависимость с тестовой областью отсутствует в рантайме, поэтому такое падает в продакшене, а не в сборке |

### `configuration` — доступ к окружению

| Идентификатор | Что обеспечивает |
|---|---|
| `configuration.environment-is-read-only-by-configuration-classes` | `System.getenv`/`System.getProperty` только в классах `@Configuration`/`@ConfigurationProperties` |

В Kubernetes конфигурация приходит переменными окружения и ConfigMap. `System.getenv` в методе
сервиса невидим при ревью манифеста, не имеет ни значения по умолчанию, ни типа, ни валидации и
падает на первом же запросе, которому он понадобился, а не при старте пода.

### `exceptions` — одна иерархия, одно место, которое её переводит

| Идентификатор | Что обеспечивает |
|---|---|
| `exceptions.custom-exceptions-extend-the-base-exception` | Каждое исключение, объявленное сервисом, наследует настроенное базовое (`conventions.types.base-exception`) |
| `exceptions.controllers-do-not-catch-checked-exceptions` | Контроллеры выпускают исключения наружу; переводит их `@ControllerAdvice`/`@RestControllerAdvice` |
| `exceptions.kafka-listeners-do-not-catch-checked-exceptions` | Слушатель, проглотивший отказ, подтверждает сообщение как обработанное — дайте контейнеру повторить или отправить в dead-letter |

`java.lang.Throwable` намеренно исключён из правил про catch: try-with-resources и `finally`
компилируются в обработчики «поймать всё», которые байткод не отличает от рукописного
`catch (Throwable t)`. Всё, что разработчик действительно называет — `IOException`, `Exception`,
собственное проверяемое исключение сервиса, — сообщается.

### `dto-immutability` — модель API собирается один раз

| Идентификатор | Что обеспечивает |
|---|---|
| `dto-immutability.api-models-have-no-setters` | Никаких публичных `setXxx(T)` на чём-либо в пакетах DTO/API |

Проверяется скомпилированная поверхность API, а не то, как она получена, — и именно поэтому это
работает там, где «обнаружить использование Lombok» не может: сгенерированный Lombok сеттер и
рукописный — это один и тот же байткод.

### `entity-base` — один базовый класс аудита

| Идентификатор | Что обеспечивает |
|---|---|
| `entity-base.entities-extend-the-base-entity` | Каждая `@Entity` приводима к настроенной базовой сущности (`conventions.types.base-entity`) |

От `@MappedSuperclass` и `@Embeddable` наследования не требуется: первое обычно и есть базовый класс,
у второго нет идентичности.

### `kafka-contracts` — продюсеры и консьюмеры реализуют собственные контракты сервиса

| Идентификатор | Что обеспечивает |
|---|---|
| `kafka-contracts.producers-implement-the-publisher-interface` | Класс, использующий API продюсера Kafka, реализует настроенный интерфейс издателя (`conventions.types.event-publisher`) |
| `kafka-contracts.consumers-implement-the-handler-interface` | Строится только при заданном `conventions.types.event-consumer` — многие сервисы держат обычные аннотированные слушатели |

Контрактная половина `kafka`: та группа говорит, где может появляться API Kafka, а эта — чем обязан
быть класс, который им пользуется. Граница пакета всё ещё допускает пятерых издателей с пятью разными
поведениями по повторам, заголовкам и сериализации внутри `..messaging..`.

### `rest-paths` — одна версионированная форма пути на весь эстейт

| Идентификатор | Что обеспечивает |
|---|---|
| `rest-paths.controllers-declare-a-versioned-base-path` | Каждый `@RestController` объявляет `@RequestMapping`, чей путь соответствует `conventions.settings.rest-base-path-pattern` (по умолчанию `/api/v\d+(/.*)?`) |

Реализовано условием над значениями членов аннотации: путь — это строка внутри аннотации, которую ни
одно правило о зависимостях увидеть не может.

### `configuration-properties` — плохая конфигурация валит под, а не запрос

| Идентификатор | Что обеспечивает |
|---|---|
| `configuration-properties.configuration-properties-are-validated` | Каждый класс `@ConfigurationProperties` также помечен `@Validated` |

### `optional` — `Optional` это тип возврата

| Идентификатор | Что обеспечивает |
|---|---|
| `optional.not-used-as-a-field-type` | Ни одного поля, объявленного как `Optional` |
| `optional.not-used-as-a-parameter-type` | Ни один метод или конструктор не принимает `Optional` |

Только объявленные типы. Поиск методов, возвращающих `null` там, где следовало бы вернуть `Optional`,
требует анализа потока данных и принадлежит проверяющему null-безопасность (Sonar, NullAway,
ErrorProne) — см. [что эта библиотека намеренно не
проверяет](#что-эта-библиотека-намеренно-не-проверяет).

### `mappers` — мапперы это интерфейсы MapStruct

| Идентификатор | Что обеспечивает |
|---|---|
| `mappers.mappers-are-mapstruct-interfaces` | Класс в пакете мапперов или с именем, оканчивающимся на настроенный суффикс, является интерфейсом с аннотацией `@org.mapstruct.Mapper` |

Сгенерированные классы `*MapperImpl` и вложенные типы, которые генератор кладёт рядом, исключаются —
по приводимости и по вложенности, а не по имени, поскольку `@Generated` у MapStruct имеет исходную
retention и до байткода не доходит. Правило фиксирует соглашение для классов, уже опознанных как
мапперы; оно не ищет логику отображения, написанную где-то ещё.

### `audit` - журнал аудита один, и второй никто не объявляет

| Идентификатор правила | Что обеспечивает |
|---|---|
| `audit.no-second-audit-spi` | Ни один интерфейс вне `ru.ludwigandreas.audit..` не называется как приёмник, логгер, эмиттер или рекордер аудита *и* не объявляет void-метод записи с одним аргументом |
| `audit.no-private-audit-logger-names` | **По подписке.** Ни один класс в пакете `audit` вне `ru.ludwigandreas.audit..` не объявляет собственное статическое поле-логгер SLF4J |

Эта группа - устойчивая половина консолидации. Девять модулей платформы изобрели по механизму аудита -
девять SPI, семь реализаций на SLF4J, три постоянных хранилища с тремя схемами - и каждое решение было
локально разумным: модулю нужно было место для журнала, SPI с поставляемой реализацией по умолчанию -
идиоматичный ответ, и никто не мог видеть остальные восемь. Замена их на [`audit-core`](../audit-core)
исправила состояние кода; и только правило не даёт принять то же локально разумное решение снова в
следующем квартале.

Только интерфейсы, и только те, которые кто-то мог бы реализовать. Конкретный класс с именем
`SomethingAuditRecorder` - это *вызывающая сторона* приёмника (`SettingsAuditRecorder` после консолидации
именно таков), и запрет имени запретил бы разумное имя для класса, делающего правильную вещь. Не должно
существовать второго **шва**: интерфейса, приглашающего развёртывание опубликовать свою реализацию, -
именно в этой точке журнал одного модуля перестаёт идти туда, куда идёт журнал платформы.

Одна проверка имени пометила бы маркерный интерфейс или тип стороны чтения, лишь упоминающий аудит,
поэтому правило требует ещё и void-метод с одним аргументом и без static - форму, которая была у всех
девяти. Только на форму оно не опирается, потому что один void-метод с одним объектом - это ещё и форма
любого слушателя, потребителя и колбэка в платформе.

**Вторая константа-маска проверяется Checkstyle, а не здесь.** Маска - это *значение* строковой
константы, а ArchUnit читает байт-код, где значение `static final String` - запись в пуле констант,
которую `JavaField` не раскрывает. Ближайшее структурное приближение - «нет поля с именем `MASK` или
`REDACTED`» - пропустило бы то, которое кто-то назовёт `HIDDEN`, и пометило бы законные посторонние
константы. `checkstyle-rules` видит текст исходников, поэтому там проверка точна: `SecondRedactionMask`.
То же разделение труда, что и везде в этом репозитории.

**То, что модуль действительно аудирует то, что должен, тоже не проверяется.** «Каждое изменение
состояния порождает событие аудита» требует знать, какие методы меняют состояние, а это семантическое
суждение, которого не сделает никакое структурное правило. Проверяемо - и эта группа проверяет - что
когда модуль аудирует, он аудирует через тип платформы.

## Настройка соглашений

Ничто в правилах не зашивает имя пакета. Сервис один раз накладывает свою раскладку на словарь
библиотеки — аннотацией, файлом свойств или кодом.

| Роль | Пакеты по умолчанию |
|---|---|
| `controller` | `..controller..`, `..web..`, `..rest..` |
| `service` | `..service..`, `..application..`, `..usecase..` |
| `repository` | `..repository..`, `..persistence..`, `..dao..` |
| `entity` | `..entity..`, `..entities..` |
| `domain` | `..domain..` |
| `dto` | `..dto..`, `..request..`, `..response..` |
| `mapper` | `..mapper..`, `..mappers..` |
| `configuration` | `..config..`, `..configuration..` |
| `messaging` | `..messaging..`, `..kafka..` |
| `event-payload` | `..event..`, `..events..` |
| `storage` | `..storage..`, `..s3..` |
| `transactional-host` | `..service..`, `..application..`, `..usecase..` |
| `module-internal` | `internal` (сегмент пакета, не идентификатор) |
| `module-api` | `api` (сегмент пакета, не идентификатор) |

Типы, которые сервис называет сам (`conventions.types.*`), без значений по умолчанию — правила,
которые ими пользуются, так и говорят, вместо того чтобы угадывать:

| Роль типа | Смысл |
|---|---|
| `base-exception` | Корень иерархии исключений сервиса, например `com.acme.common.ApplicationException` |
| `base-entity` | Базовый класс аудита, который наследует каждая `@Entity`, например `ru.ludwigandreas.db.core.entity.AbstractEntity` |
| `event-publisher` | Внутренний контракт, который реализует каждый продюсер Kafka, например `com.acme.messaging.EventPublisher` |
| `event-consumer` | Необязательно: контракт, который реализует каждый слушатель. Правило существует, только когда он задан |

Свободные настройки (`conventions.settings.*`):

| Настройка | По умолчанию | Смысл |
|---|---|---|
| `rest-base-path-pattern` | `/api/v\d+(/.*)?` | Регулярное выражение, которому обязан соответствовать базовый путь каждого `@RestController` |
| `mapper-name-suffix` | `Mapper` | Суффикс простого имени, опознающий маппер, наряду с пакетами мапперов |

Наряду с пакетами тем же способом настраиваются ещё три измерения: `libraries` (какие пакеты — это
Spring, JPA, Jackson, Kafka, AWS SDK, тестовые фреймворки), `annotations` (что помечает контроллер,
персистентный тип, класс конфигурации, точку внедрения, слушатель Kafka) и `types` (что такое
репозиторий Spring Data, контекст хранения, JDBC-хендл, клиент Kafka). Всё это сопоставляется по
полному имени, и именно поэтому у библиотеки нет собственных зависимостей от фреймворков и она
работает с теми версиями, которые использует сервис: добавьте собственную мета-аннотацию
`@AggregateRoot` или внутренний форк SDK — и правила последуют за ними.

### В коде

```java
@AnalyzeArchitecture(packagesOf = OrdersApplication.class)
class ArchitectureTest extends ArchitectureRulesTest {

    @Override
    protected void customize(ArchitectureRulesConfiguration.Builder builder) {
        builder.conventions(conventions -> conventions
                        .packages(PackageRole.CONTROLLER, "..api.web..")
                        .addPackages(PackageRole.ENTITY, "..persistence.jpa..")
                        .addAnnotations(AnnotationRole.PERSISTENT_TYPE, "com.acme.ddd.AggregateRoot"))
                .addRuleSet(new AcmePlatformRules());
    }
}
```

### В `architecture-rules.properties` (тестовый classpath)

```properties
architecture.rules.base-packages = com.acme.orders

# переключатели: идентификатор группы, идентификатор правила или *
architecture.rules.rules.kafka = false
architecture.rules.rules.domain-isolation = true
architecture.rules.rules.web.controllers-do-not-call-controllers = false

# соглашения: заменить значения роли либо дополнить их суффиксом .add
architecture.rules.conventions.packages.controller = ..api.web..
architecture.rules.conventions.packages.entity.add = ..persistence.jpa..
architecture.rules.conventions.libraries.aws-sdk.add = com.acme.storagesdk..
architecture.rules.conventions.annotations.persistent-type.add = com.acme.ddd.AggregateRoot

# имена, которые может дать только эта организация
architecture.rules.conventions.types.base-exception = com.acme.common.ApplicationException
architecture.rules.conventions.types.base-entity = com.acme.common.jpa.AuditableEntity
architecture.rules.conventions.types.event-publisher = com.acme.messaging.EventPublisher
architecture.rules.conventions.settings.rest-base-path-pattern = /api/v\\d+(/.*)?

# серьёзность: сообщается везде, но сборку не валит
architecture.rules.severity.modules = warning

# отчёты
architecture.rules.service-name = orders-service
architecture.rules.report.console = true
architecture.rules.report.color = auto
architecture.rules.report.json = true
architecture.rules.report.json-file = target/architecture-report.json
architecture.rules.report.max-violations-per-rule = 5

# отклонения по модулям; метка после 'module.' произвольна
architecture.rules.module.legacy.packages = com.acme.orders.legacy
architecture.rules.module.legacy.rules.layering = false
architecture.rules.module.legacy.conventions.packages.entity.add = ..jpa..
```

Неизвестный ключ под `architecture.rules.` валит сборку, а не игнорируется: опечатка в имени
свойства иначе ничего бы не выключила и обнаружилась бы только при срабатывании правила.

Настройки накладываются в фиксированном порядке — файл свойств, затем аннотация, затем `customize`, —
поэтому платформенная команда может шаблонизировать файл свойств, а сервис всё равно переопределит
один переключатель локально.

### Как разрешается переключатель

Побеждает самый конкретный селектор: идентификатор правила бьёт идентификатор группы, а тот бьёт `*`.
Включение всей группы никогда не активирует более строгие **явно подключаемые** правила внутри неё —
их нужно назвать:

```java
.disable("web")                                   // вся группа выключена
.enable("web.controllers-do-not-expose-entities")  // кроме этого
.enable("storage.adapters-do-not-expose-sdk-types") // явно подключаемое правило, включено
```

## Серьёзность: валить, предупреждать или ничего

Между «это правило валит сборку» и «это правило выключено» есть третья настройка, нужная большому
эстейту: сообщать везде, но пропускать сборку, пока долг разгребается.

```java
builder.warnOn("modules", "web.controllers-do-not-call-controllers")
       .failOn("modules.internals-are-not-accessed-from-other-modules");
```

```properties
architecture.rules.severity.modules = warning
```

Предупреждающее правило вычисляется как любое другое. Его нарушения появляются в консольном отчёте под
`WARNING` и в JSON с `"severity": "warning"`, поэтому общеорганизационный дашборд видит, что именно
сервис решил терпеть: выключенное правило исчезает, предупреждающее — нет. В JUnit тест
**прерывается** с сообщением о нарушении, а не падает, что проявляется как пропущенный тест с
указанной причиной, а не как молчаливый успех.

## Отчёты

Каждый прогон выдаёт два отчёта из одного и того же результата, для двух разных читателей.

**Консоль** — ранжированная и цветная, отказы первыми, по нескольку нарушений на правило с файлом и
строкой, плюс исправление:

```
------------------------------------------------------------------------------
 Architecture rules | catalog-service
 39 rules | 37 passed | 2 failed | 6 violations | 156 ms
------------------------------------------------------------------------------

 FAILED   web.controllers-do-not-expose-entities (3 violations)
   Controllers do not expose JPA entities
   - Method <...ProductController.get(java.util.UUID)> has ProductEntity in its signature in (ProductController.java:66)
   ... 2 more (see the JSON report)
   Fix: Return a DTO instead of the entity and map between them (a MapStruct mapper in the web
        package). Serialising an entity publishes the database schema as an API contract ...

 passed | layering 2, cycles 6, persistence 5, spring 5, ...
 JSON report: /workspace/orders/target/architecture-report.json
```

Цвет по умолчанию следует `NO_COLOR` и `TERM=dumb` (`report.color = auto|always|never`), и консоль
никогда не печатает больше `report.max-violations-per-rule` нарушений на правило.

**JSON** — весь прогон, без усечения, в `target/architecture-report.json`:

```json
{
  "schemaVersion": "1.0.0",
  "tool":    { "name": "ru.ludwigandreas:architecture-rules", "version": "1.0.0" },
  "service": { "name": "catalog-service", "basePackages": ["com.acme.orders"], "modules": ["..."] },
  "generatedAt": "2026-02-01T10:00:00Z",
  "durationMillis": 156,
  "summary": { "rules": 39, "passed": 37, "failed": 2, "warnings": 0, "violations": 6,
               "violationsByGroup": { "web": 4, "persistence": 2 } },
  "rules": [
    {
      "id": "web.controllers-do-not-expose-entities",
      "group": "web", "scope": "service",
      "severity": "error", "status": "violated",
      "description": "Controllers do not expose JPA entities",
      "remediation": "Return a DTO instead of the entity and map between them ...",
      "durationMillis": 12,
      "violationCount": 3,
      "violations": [
        { "message": "Method <...get(java.util.UUID)> has ProductEntity in its signature ...",
          "class": "com.acme.orders.web.ProductController",
          "member": "get",
          "sourceFile": "ProductController.java",
          "line": 66,
          "location": "ProductController.java:66" }
      ]
    }
  ]
}
```

Три вещи в этой форме сделаны намеренно:

- **Прошедшие правила включены.** Агрегатор обязан отличать «этот сервис проверяет правила Kafka и
  чист» от «этот сервис их вообще не проверяет». Без этого дашборд поощрял бы выключение правил.
- **Идентичность едет вместе с результатами.** Имя сервиса, базовые пакеты, модули, версия схемы и
  версия инструмента лежат в каждом файле, поэтому отчёты, собранные из многих репозиториев, можно
  объединять без соглашения об именовании, а старый отчёт остаётся интерпретируемым после смены схемы.
- **Каждое правило несёт своё исправление.** `remediation` стоит рядом с нарушениями, а каждое
  нарушение несёт класс, член, файл и строку. Кодирующему агенту, которому дали файл, хватает всего:
  доступ к исходникам этой библиотеки не нужен, — а PR-бот может превратить нарушение во встроенный
  комментарий.

Настраивается свойствами `report.*` или в коде:

```java
builder.serviceName("orders-service")
       .reporting(report -> report.jsonFile(Path.of("build/reports/architecture.json"))
                                  .maxViolationsPerRule(10)
                                  .color(ColorMode.NEVER));
```

Вне JUnit — задача Gradle, шаг CI, `main()` — тот же прогон делается одним вызовом:

```java
ArchitectureRules.checkAndReport(configuration);   // вычисляет, пишет оба отчёта, бросает при отказах
ArchitectureReport report = ArchitectureRules.run(configuration);  // то же самое, без броска
```

JSON пишется собственным писателем, а не Jackson: библиотека остаётся без зависимостей сверх ArchUnit,
чтобы ради одного файла в тестовый classpath потребителя ничего не добавлялось.

## Что эта библиотека намеренно не проверяет

Некоторые проверки выглядят уместными здесь, но таковыми не являются. Их рассмотрели и отклонили;
причины записаны, чтобы никто не выводил их заново:

| Не проверяется | Почему и чьё это |
|---|---|
| Только-ASCII / отсутствие кириллицы в исходнике | ArchUnit читает структуру скомпилированного байткода, а не текст исходника, строковые литералы или комментарии. Checkstyle (`RegexpSinglelineJava`, `RegexpMultiline`) |
| «Использован Lombok вместо рукописного шаблона» | Сгенерированный Lombok метод и рукописный — одинаковый байткод; вход процессора аннотаций не виден. Здесь не реализуемо — правило о неизменяемости вместо этого спрашивает о получившемся API, и поэтому **оно** работает |
| Самописное отображение объектов внутри сервиса | Нет структурного признака, отделяющего законный код преобразования от «здесь должен был быть MapStruct». Это суждение ревьюера, а не шаблон в байткоде |
| Методы, возвращающие `null` вместо `Optional` | Анализ потока данных по телам методов. Sonar, NullAway, ErrorProne. Группа `optional` проверяет только объявленные типы |
| Правила именования, форматирования и безопасности | Уже покрыты Checkstyle, SonarQube и политикой SCM. Библиотека остаётся на структуре и зависимостях |

Оба ограничения области заявлены и в коде — на `OptionalUsageRules` и `MapperConventionRules`, —
чтобы следующий открывший класс нашёл границу до того, как начнёт его расширять.

## Правила по модулям

Монолит редко отклоняется везде сразу: обычно один legacy-модуль отстаёт, либо двум платёжным модулям
нужно что-то строже. Это первоклассное понятие, а не второй тестовый класс:

```java
builder.customizeModules(ModuleRuleCustomization.forModules("com.acme.orders.legacy")
                .conventions(conventions -> conventions.addPackages(PackageRole.ENTITY, "..jpa.."))
                .disable("layering.controllers-do-not-access-persistence")
                .build())
       .customizeModules(ModuleRuleCustomization.forModules("com.acme.payments", "com.acme.payouts")
                .enable("domain-isolation")
                .addRuleSet(new PciAuditRules())
                .build());
```

Кастомизация **перехватывает** правило, когда переопределяет соглашения, из которых оно построено,
или когда её выборка называет это правило. Перехваченное правило затем строится дважды: один раз на
весь сервис, с исключением кастомизирующих модулей, и по разу на каждую кастомизацию — с соглашениями
и переключателями этого модуля. Все прочие правила остаются единственным экземпляром на весь сервис,
покрывающим и этот модуль. В итоге каждый класс проверяется ровно одним экземпляром каждого правила:
модуль может действительно отклоняться, и ничто не сообщается дважды.

## Добавление собственных правил

Реализуйте `ArchitectureRuleSet` и выводите каждое имя пакета из переданного `RuleContext`, чтобы
набор правил оставался переиспользуемым в сервисах с разной раскладкой. Предикаты и условия, которыми
написаны встроенные правила, опубликованы для повторного использования в
`ru.ludwigandreas.archrules.support`.

```java
public final class AcmePlatformRules implements ArchitectureRuleSet {

    @Override
    public RuleGroup group() {
        return RuleGroup.CUSTOM;
    }

    @Override
    public List<ArchitectureRule> rules(RuleContext context) {
        return List.of(ArchitectureRule.of(
                RuleId.of(RuleGroup.CUSTOM, "clocks-are-injected"),
                ArchRuleDefinition.noClasses()
                        .that(ConventionPredicates.services(context))
                        .should(ArchitectureConditions.notCallMethods(
                                Map.of("java.time.LocalDateTime", Set.of("now")),
                                "LocalDateTime.now - inject a Clock"))));
    }
}
```

Регистрируйте его на сервис (`builder.addRuleSet(...)`), на модуль
(`ModuleRuleCustomization.addRuleSet`) или на всю организацию, перечислив его в
`META-INF/services/ru.ludwigandreas.archrules.ArchitectureRuleSet`: jar в тестовом classpath тогда
вносит свои правила в каждый зависящий от него сервис, и тестовый код менять не нужно. Поставьте
`builder.includeBuiltInRuleSets(false)`, чтобы использовать библиотеку исключительно как обвязку
выполнения и конфигурации для собственных правил.

## Внедрение в существующую кодовую базу

Сервис с историей не пройдёт всё в первый день. Учтите сразу: группы, проверяющие против общего
базового типа (`exceptions`, `entity-base`, `kafka-contracts`), включены по умолчанию и попросят
настройки, как только у сервиса появятся сущность, собственное исключение или продюсер Kafka, — это
единственный шаг обновления, которого библиотека требует, и сообщение называет нужное свойство. Три
пути внутрь:

- **Сначала предупреждать.** `architecture.rules.severity.<selector> = warning` оставляет правило
  вычисляемым и сообщаемым, пока сборка остаётся зелёной. В отличие от выключения, нарушения остаются
  видны в JSON-отчёте, поэтому долг отслеживается, а не забывается.
- **Заморозить.** `architecture.rules.freeze = true` фиксирует сегодняшние нарушения как принятую
  базовую линию (`FreezingArchRule` из ArchUnit) и валит только новые, так что кодовая база может
  улучшаться постепенно и никогда не регрессировать. Храните файл нарушений в репозитории.
- **Выключить и завести долг.** Выключите правила, которым пока не соответствуете, по идентификатору,
  с комментарием, называющим задачу. Выключенное правило видно в конфигурации; ненаписанное — нет.

## Заметки о дизайне

- **Один тест на правило.** Динамический тест на каждое правило означает, что впервые упавшее правило
  проявляется новым упавшим тестом, а не более длинным сообщением, и имя теста — ровно та строка,
  которой его выключают. XML Surefire называет динамические тесты по индексу, поэтому идентификатор
  правила дополнительно ставится в начало каждого сообщения об ошибке; добавьте
  `usePhrasedTestCaseMethodName`, чтобы Surefire использовал отображаемое имя:
  ```xml
  <plugin>
      <artifactId>maven-surefire-plugin</artifactId>
      <configuration>
          <statelessTestsetReporter implementation="org.apache.maven.plugin.surefire.extensions.junit5.JUnit5Xml30StatelessReporter">
              <usePhrasedTestCaseMethodName>true</usePhrasedTestCaseMethodName>
          </statelessTestsetReporter>
      </configuration>
  </plugin>
  ```
- **Правилам об ограничении не нужен «домашний» пакет.** «AWS SDK видят только адаптеры хранилища»
  остаётся верным для сервиса, не настроившего пакет хранилища, — ему просто негде разрешить SDK.
  Пропускаются лишь правила, которые **размещают** класс (сущности в пакетах сущностей, консьюмеры в
  пакетах обмена сообщениями), когда целевая роль не настроена.
- **Никаких зависимостей от фреймворков.** Spring, JPA, Kafka и AWS SDK упоминаются по имени, никогда
  литералом класса, поэтому jar ничего не тащит в тестовый classpath сервиса и не может конфликтовать
  с его версиями. Единственная зависимость — сам `archunit` (и JUnit, необязательно, ради базового
  класса).
- **Только собственный код.** Роли пакетов сопоставляются внутри анализируемых базовых пакетов,
  потому что `..persistence..` совпадает и с `jakarta.persistence`, а `..web..` — с
  `org.springframework.web.bind.annotation`. Сторонние технологии сопоставляются через собственные
  настраиваемые пакеты библиотек.
- **Только продакшен-классы.** Импорт по умолчанию исключает тестовые исходники — иначе правило о
  разделении тестов и продакшена не могло бы ничего значить. `architecture.rules.include-tests = true`
  расширяет его.
- **Пусто — не отказ, но пустой анализ — отказ.** `allowEmptyShould` по умолчанию true, и именно это
  позволяет сервису держать правила Kafka включёнными до появления кода на Kafka. Импорт **вообще ни
  одного класса** при этом отвергается: опечатка в базовом пакете иначе позволила бы каждому правилу
  пройти на пустом множестве классов и отрапортовать зелёный архитектурный тест сервису, в котором
  ничего не проверялось. Вызовите `allowEmptyAnalysis(true)`, если пустой модуль действительно
  ожидаем.
- **Импорт кэшируется** по паре (пакеты, опции импорта) на время жизни JVM, поэтому несколько
  архитектурных тестовых классов в одной сборке сканируют байткод один раз. При этом они делят путь
  отчёта по умолчанию: модулю с более чем одним архитектурным тестовым классом стоит задать каждому
  собственный `report.json-file`, иначе побеждает последний выполнившийся.
- **Серьёзность действует везде.** Правило, пониженное до `warning`, пропускается в `check()`,
  прерывается вместо падения в JUnit и всё равно присутствует в обоих отчётах. Серьёзность,
  действующая только для одной точки входа, была бы ловушкой.

## Тестирование

`mvn -pl architecture-rules test` прогоняет правила против фикстурных сервисов в `src/test/java`:
одного, удовлетворяющего каждому правилу, и по одному на группу, который нарушает его намеренно.
Отчёты тестируются так же — JSON разбирается обратно настоящим парсером, потому что отчёт, который
агрегатор не может прочитать, хуже, чем никакого. Правило, которое всегда только проходит, ничего не
доказывает, поэтому каждое проверяется с обеих сторон. Фреймворки, используемые фикстурами, —
заглушки, объявленные под настоящими именами пакетов, что заодно доказывает работоспособность
сопоставления по именам.

Образцовый сервис в [`crud-service-example`](../crud-service-example/README.ru.md) включает библиотеку
на себе, поэтому собственный пример этого репозитория доказывает соответствие на каждой сборке.
