# db-core

*[English](README.md) · **Русский***

Промышленные базовые классы сущностей, аудит, мягкое удаление, исключения и утилиты QueryDSL /
Spring Data JPA для сервисов на Java 17 + PostgreSQL + Spring Boot. Добавьте зависимость,
унаследуйте базовую сущность — готово: никаких шаблонных колонок id/version/audit и никакой ручной
проводки `AuditorAware`.

## Быстрый старт

```xml
<dependency>
    <groupId>ru.ludwigandreas</groupId>
    <artifactId>db-core</artifactId>
    <version>1.0.0</version>
</dependency>
```

Унаследуйте `AuditedEntity` для типичной сущности, принадлежащей приложению, — генерируемый UUID,
оптимистичная блокировка и полный аудит «кем и когда создано/изменено», всё подключается
автоматически:

```java
@Entity
@Table(name = "orders")
public class Order extends AuditedEntity<UUID> {

    private String description;
    // ...
}
```

## Иерархия сущностей

Два семейства, оба с корнем в `AbstractEntity<ID>` (контракт идентификатора плюс
`equals`/`hashCode`/`toString`):

```
AbstractEntity<ID>
│
├── JpaBaseEntity<ID>            обычный, id назначает приложение или внешняя система
│     ├── VersionedEntity<ID>          + @Version
│     ├── DateAuditableEntity<ID>      + createdAt/updatedAt
│     │     └── UserAuditableEntity<ID>    + createdBy/updatedBy
│     ├── SoftDeleteEntity<ID>         + deleted/deletedAt
│     └── ExternalEntity<ID>           корень «внешнего» семейства: id и жизненным циклом
│           └── SnapshotEntity<ID>         владеет внешняя система (Kafka/REST), ID никогда
│                                          не приводится к UUID по умолчанию
│
└── GeneratedEntity<ID>          генерируемый UUID (JPA 3.1 GenerationType.UUID)
      └── AuditedEntity<ID>           + полный аудит + @Version — класс, который наследует
                                        большинство сущностей
```

`GeneratedEntity` и `AuditedEntity` объявляют собственное поле `id`, а не наследуют
`JpaBaseEntity`: в JPA нет переносимого способа добавить `@GeneratedValue` поверх поля `@Id`, уже
объявленного (без генерации) выше по цепочке `@MappedSuperclass`, поэтому ветка с генерируемым
идентификатором дублирует эту горстку строк вместо того, чтобы полагаться на неопределённое
поведение при совпадении имён атрибутов.

**Внутренние против внешних:** сущности, созданные **этим** приложением (заказы, пользователи и
т. д.), наследуют `GeneratedEntity`/`AuditedEntity`/`VersionedEntity` и подобные. Сущности,
отражающие данные, которыми владеет **внешняя** система (событие Kafka, REST-payload другого
сервиса), наследуют `ExternalEntity` или `SnapshotEntity` — тип их идентификатора такой, какой
использует источник (`Long`, `String`, `UUID`, …), и никогда не навязывается UUID.

### Поля происхождения у `ExternalEntity`

`ExternalEntity` несёт `importedAt`/`sourceSystem`/`sourceVersion`/`sourceTimestamp`, общие для любой
внешней сущности. `importedAt` заполняется автоматически при первом сохранении и (как и
`sourceSystem`) больше не меняется; `sourceVersion` и `sourceTimestamp` остаются изменяемыми, поэтому
обычный наследник `ExternalEntity` можно обновлять на месте при каждом получении новой версии, не
прибегая к неизменяемости `SnapshotEntity`:

```java
@Entity
@Table(name = "customers")
public class Customer extends ExternalEntity<String> {  // id = идентификатор системы-источника
    private String name;
}

// при каждом получении: найти или создать, обновить поля, сохранить — обычный UPDATE
Customer customer = customerRepository.findById(externalId).orElseGet(Customer::new);
customer.setSourceVersion(newData.version());
customer.setName(newData.name());
customerRepository.save(customer);
```

### `SnapshotEntity`

Неизменяемая копия внешних данных на момент времени — не добавляет к `ExternalEntity` ничего, кроме
гарантии неизменяемости:

```java
@Entity
@Table(name = "customer_snapshots")
public class CustomerSnapshot extends SnapshotEntity<String> {  // id = идентификатор источника
    private String name;
}
```

`SnapshotImmutabilityListener` (подключается автоматически) бросает `IntegrityViolationException` на
любой `@PreUpdate` — строки снимков доступны только на добавление.

Обратите внимание: поскольку `ID` здесь по-прежнему идентификатор системы-источника (как у
`ExternalEntity`), эта форма поддерживает ровно одну строку на внешнюю сущность, неизменяемую после
записи, — она подходит для данных «импортировали один раз и больше не трогаем». Если нужно хранить
каждую полученную **версию** одной и той же внешней сущности отдельной строкой (настоящую историю
версий), дайте снимку генерируемый суррогатный идентификатор, а идентификатор источника оставьте
обычной неуникальной колонкой, чтобы несколько строк могли его разделять.

### Мягкое удаление

`SoftDeleteEntity` объявляет только колонки `deleted`/`deletedAt`; поскольку `@SQLDelete` и
`@SQLRestriction` из Hibernate требуют буквального имени таблицы, каждая конкретная сущность
добавляет их сама:

```java
@Entity
@Table(name = "widgets")
@SQLDelete(sql = "UPDATE widgets SET deleted = true, deleted_at = now() WHERE id = ?")
@SQLRestriction("deleted = false")
public class Widget extends SoftDeleteEntity<UUID> { ... }
```

С ними `repository.delete(entity)` превращается в `UPDATE`, а удалённые строки прозрачно исключаются
из обычных методов поиска и запросов (нативные запросы ограничение обходят).

## Репозитории

```java
public interface OrderRepository extends BaseRepository<Order, UUID> {
}
```

`BaseRepository` = `JpaRepository` + `QuerydslPredicateExecutor` + `getByIdOrThrow(id)` (бросает
`EntityNotFoundException`). Чтобы получить настоящую реализацию `getByIdOrThrow`, укажите Spring Data
`BaseRepositoryImpl` в качестве базового класса репозиториев:

```java
@EnableJpaRepositories(repositoryBaseClass = BaseRepositoryImpl.class)
```

## Аудит

Автоконфигурируется из коробки: если Spring Security есть в classpath, текущим аудитором становится
имя аутентифицированного принципала, иначе используется `"system"`. Переопределяется собственным
бином `AuditorProvider<String>` либо прямой реализацией `AuditorProvider<T>` для не-строкового типа
аудитора.

## Помощники для запросов

```java
BooleanExpression predicate = Predicates.allOf(
        Predicates.whenNotNull(filter.status(), QOrder.order.status::eq),
        Predicates.whenNotNull(filter.customerId(), QOrder.order.customerId::eq));

Pageable pageable = PageableUtils.of(page, size, properties.getMaxPageSize(), sort, defaultSort);
```

`Predicates.allOf` отбрасывает `null`-выражения и всегда возвращает безопасный ненулевой предикат;
`PageableUtils.of` ограничивает размер страницы и подставляет сортировку по умолчанию, если её не
запросили.

## Ответы об ошибках

Когда в classpath есть [`web-core-spring-boot-starter`](../web-core-spring-boot-starter/README.md),
этот модуль отдаёт в его общий конвейер проблем `DbCoreProblemMapper` и bundle
`i18n/ludwig-db-messages`, поэтому его исключения доходят до клиента локализованными проблемами
RFC 9457 под кодами `ludwig.db.error.*`, а не пятисотками.

Важнее всего `EntityNotFoundException`. Его бросает `getByIdOrThrow`, и сервисный слой обычно сначала
превращает отсутствующую строку в собственное локализованное исключение — но только на тех путях, где
об этом кто-то вспомнил. Везде остальном оно доходило до advice неотображённым и отвечалось
пятисоткой: отсутствующая строка подавалась как отказ сервера — ровно на тех участках кода, которые
никто не просматривал. Отображённое здесь, «страховочная сетка» становится поведением по умолчанию:

| Исключение | Статус | Код |
|---|---|---|
| `EntityNotFoundException` | 404 | `ludwig.db.error.entity-not-found` |
| `DuplicateEntityException` | 409 | `ludwig.db.error.duplicate-entity` |
| `IntegrityViolationException` | 409 | `ludwig.db.error.integrity-violation` |
| `UnsupportedIdTypeException` | 500 | `ludwig.db.error.unsupported-id-type` |

Текст намеренно общий — этот модуль знает класс сущности, но не то, как её называет API. Сервис,
которому нужно *«Товара с идентификатором X не существует»*, бросает собственное
`LocalizedException`, и оно имеет приоритет над этим маппером.

Без указанного стартера ничего не меняется: эти исключения обрабатывает та advice, которая есть у
сервиса.

## Метрики

Опционально (только при наличии Micrometer в classpath): `ludwig.db.auditor.resolved` считает каждое
обращение к `AuditorAware` с тегом `present=true/false` — устойчивая серия `false` обычно означает,
что Spring Security подключён не так, как ожидает этот модуль. `ludwig.db.snapshot.mutation.blocked`
считает отклонённые записи в неизменяемую `SnapshotEntity` с тегом по типу сущности; на неё стоит
поставить алерт, потому что она означает, что что-то в системе считает себя владельцем данных,
которыми на самом деле владеет внешний источник.

## Конфигурация (`ludwig.db.*`)

| Свойство | По умолчанию | Значение |
|---|---|---|
| `ludwig.db.auditing-enabled` | `true` | Включает проводку `@EnableJpaAuditing` |
| `ludwig.db.default-page-size` | `20` | Соглашение по умолчанию для вызывающих `PageableUtils` |
| `ludwig.db.max-page-size` | `200` | Верхняя граница по соглашению для `PageableUtils` |
| `ludwig.db.metrics.enabled` | `true` | Публиковать метрики Micrometer (только если он есть в classpath) |

## Тестирование

`mvn test` прогоняет модульные тесты (равенство сущностей, помощники предикатов и постраничности) без
внешних зависимостей. Интеграционные тесты дополнительно поднимают настоящий контейнер PostgreSQL
через Testcontainers, чтобы сквозь всё пройти генерацию идентификаторов, аудит, оптимистичную
блокировку, мягкое удаление и неизменяемость снимков; им нужен работающий демон Docker.
