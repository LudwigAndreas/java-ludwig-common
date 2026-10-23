# db-core

*[English](README.md) · **Русский***

Базовые классы сущностей, построчный аудит (`created_by` / `last_modified_by`), мягкое удаление,
неизменяемые снимки, базовый репозиторий, хелперы QueryDSL и отображаемые исключения персистентности для
сервисов Spring Boot на PostgreSQL. Наследуйтесь от базовой сущности и получите id, версию и колонки
аудита без шаблонного кода.

| | |
|---|---|
| Роль | library (с автоконфигурацией) |
| Зависит от (в репозитории) | опционально: [`web-core-spring-boot-starter`](../web-core-spring-boot-starter) |
| Используется | стартерами `audit`, `export`, `file-action`, `file-ingest`, `idempotency`, `identity-projection`, `outbox`, `pat`, `reconciliation`, `user-settings`; обоими сервисами |
| Поверхности | нет (добавляет отображения проблем в web-core) |

## Использование

```xml
<dependency>
    <groupId>ru.ludwigandreas</groupId>
    <artifactId>db-core</artifactId>
</dependency>
```

Типичная сущность приложения наследует `AuditedEntity` - сгенерированный UUID id, оптимистическая
блокировка и created/updated by/at, всё подключается автоматически:

```java
@Entity
@Table(name = "orders")
public class Order extends AuditedEntity<UUID> {
    private String description;
}
```

### Иерархия сущностей

```
AbstractEntity<ID>                       контракт id + equals/hashCode/toString
│
├── JpaBaseEntity<ID>                    id назначает приложение или внешняя система
│     ├── VersionedEntity<ID>            + @Version
│     ├── DateAuditableEntity<ID>        + createdAt/updatedAt
│     │     └── UserAuditableEntity<ID>  + createdBy/updatedBy
│     ├── SoftDeleteEntity<ID>           + deleted/deletedAt
│     └── ExternalEntity<ID>             данные другой системы; тип id - как у источника
│           └── SnapshotEntity<ID>       неизменяемая копия на момент времени
│
└── GeneratedEntity<ID>                  сгенерированный UUID id (GenerationType.UUID)
      └── AuditedEntity<ID>              + полный аудит + @Version - от неё наследуется большинство
```

- **Внутренние** сущности (создаются этим приложением) наследуют `GeneratedEntity` / `AuditedEntity` /
  `VersionedEntity` / ...
- **Внешние** сущности (зеркало Kafka-события или REST-payload другого сервиса) наследуют `ExternalEntity`
  или `SnapshotEntity`; тип их id - как у источника (`Long`, `String`, `UUID`).

`GeneratedEntity` объявляет собственный `id`, а не наследует `JpaBaseEntity`: в JPA нет переносимого способа
добавить `@GeneratedValue` к `@Id`, уже объявленному выше в цепочке `@MappedSuperclass`.

### `ExternalEntity`

Несёт `importedAt` (ставится при первом сохранении и не меняется), `sourceSystem` (не меняется),
`sourceVersion` и `sourceTimestamp` (изменяемые, чтобы строку можно было обновлять при каждой загрузке):

```java
@Entity
@Table(name = "customers")
public class Customer extends ExternalEntity<String> {  // id = id в системе-источнике
    private String name;
}

Customer customer = customerRepository.findById(externalId).orElseGet(Customer::new);
customer.setSourceVersion(newData.version());
customer.setName(newData.name());
customerRepository.save(customer);
```

### `SnapshotEntity`

Неизменяемая копия: `SnapshotImmutabilityListener` (подключается автоматически) бросает
`IntegrityViolationException` на любом `@PreUpdate`. С id источника в качестве первичного ключа это одна
строка на внешнюю сущность, записанная один раз. Для полной истории версий дайте снимку сгенерированный
суррогатный id, а id источника храните в неуникальной колонке.

### Мягкое удаление

`SoftDeleteEntity` объявляет `deleted` / `deletedAt`. `@SQLDelete` / `@SQLRestriction` Hibernate требуют
буквального имени таблицы, поэтому их добавляет каждая сущность:

```java
@Entity
@Table(name = "widgets")
@SQLDelete(sql = "UPDATE widgets SET deleted = true, deleted_at = now() WHERE id = ?")
@SQLRestriction("deleted = false")
public class Widget extends SoftDeleteEntity<UUID> { ... }
```

`repository.delete(entity)` превращается в `UPDATE`, а удалённые строки исключаются из производных и JPQL
запросов. **Нативные запросы ограничение обходят.**

### Репозитории

```java
public interface OrderRepository extends BaseRepository<Order, UUID> {
}
```

`BaseRepository` = `JpaRepository` + `QuerydslPredicateExecutor` + `getByIdOrThrow(id)` (бросает
`EntityNotFoundException`). Включите реализацию:

```java
@EnableJpaRepositories(repositoryBaseClass = BaseRepositoryImpl.class)
```

### Построчный аудит

`created_by` / `last_modified_by` проставляются автоматически. Аудитор берётся из:

1. бина `AuditorProvider` сервиса, если он есть;
2. с [`audit-spring-boot-starter`](../audit-spring-boot-starter): `ActorResolverAuditorProvider` на основе
   единого `ActorResolver` платформы, поэтому строка и её событие аудита называют одного человека;
3. иначе имя принципала Spring Security или `"system"` без Spring Security.

Это «кто последним трогал строку». «Что произошло» - аудиторский след в `audit_event`
([`audit-core`](../audit-core)); это разные вопросы, и механизмы разделены.

### Хелперы запросов

```java
BooleanExpression predicate = Predicates.allOf(
        Predicates.whenNotNull(filter.status(), QOrder.order.status::eq),
        Predicates.whenNotNull(filter.customerId(), QOrder.order.customerId::eq));

Pageable pageable = PageableUtils.of(page, size, properties.getMaxPageSize(), sort, defaultSort);
```

`Predicates.allOf` отбрасывает `null`-выражения и всегда возвращает не-null предикат; `PageableUtils.of`
ограничивает размер страницы и применяет сортировку по умолчанию.

### Ответы об ошибках

С [`web-core-spring-boot-starter`](../web-core-spring-boot-starter) в classpath `DbCoreProblemMapper` и бандл
`i18n/ludwig-db-messages` превращают исключения персистентности в локализованные проблемы RFC 9457:

| Исключение | Статус | Код |
|---|---|---|
| `EntityNotFoundException` | 404 | `ludwig.db.error.entity-not-found` |
| `DuplicateEntityException` | 409 | `ludwig.db.error.duplicate-entity` |
| `IntegrityViolationException` | 409 | `ludwig.db.error.integrity-violation` |
| `UnsupportedIdTypeException` | 500 | `ludwig.db.error.unsupported-id-type` |

Поэтому отсутствующая строка - 404 даже там, где сервис её не преобразовал. Текст общий (модуль знает класс
сущности, а не её имя в API); собственное `LocalizedException` сервиса имеет приоритет.

## Конфигурация

| Свойство | По умолчанию | Смысл |
|---|---|---|
| `ludwig.db.auditing-enabled` | `true` | Включить подключение JPA-аудита |
| `ludwig.db.default-page-size` | `20` | Размер страницы по умолчанию для вызывающих `PageableUtils` |
| `ludwig.db.max-page-size` | `200` | Верхняя граница для вызывающих `PageableUtils` |
| `ludwig.db.metrics.enabled` | `true` | Публиковать метрики Micrometer (если Micrometer есть) |

## Развёртывание

Развёртывать нечего: модуль не поставляет схему. Таблицу каждой сущности создаёт Liquibase changelog
владеющего сервиса, включая колонки базовых классов (`id`, `version`, `created_at`, `created_by`,
`updated_at`, `updated_by`, `deleted`, `deleted_at`, `imported_at`, `source_system`, `source_version`,
`source_timestamp` - по необходимости). Сервисы работают с `spring.jpa.hibernate.ddl-auto=validate`.

## Наблюдаемость

**Метрики** (если есть Micrometer):

| Метрика | Тип | Теги | Смысл |
|---|---|---|---|
| `ludwig.db.auditor.resolved` | counter | `present` | Запросы аудитора. Устойчивое `present=false` для пользовательских запросов значит, что принципал не доходит до аудитора (подключение security) |
| `ludwig.db.snapshot.mutation.blocked` | counter | `entityType` | Отвергнутые изменения `SnapshotEntity`: что-то считает чужие данные своими. **Алерт на любое значение** |

**Логи.** Собственных нет. SQL-логированием Hibernate управляет сервис.

**Известные пробелы:** на уровне модуля нет; метрики пула соединений и запросов даёт Spring Boot (Hikari,
наблюдения `spring.data.repository`), за них отвечает сервис.

## Аудит

Не применяется: модуль проставляет `created_by` / `last_modified_by`, но событий аудита не пишет.

## Runbook

| Симптом | Вероятная причина | Что делать |
|---|---|---|
| `created_by` = `system` для пользовательских запросов; растёт `ludwig_db_auditor_resolved_total{present="false"}` | Нет принципала в момент проставления (асинхронный поток, нет security-контекста) | Проверить, что путь кода выполняется в потоке запроса или передаёт security-контекст |
| `IntegrityViolationException` при изменении снимка; растёт `ludwig_db_snapshot_mutation_blocked_total` | Код меняет неизменяемую `SnapshotEntity` | Исправить код: вставлять новый снимок или использовать `ExternalEntity` для upsert |
| Мягко удалённые строки попадают в результаты | Нативный запрос | Добавить `deleted = false` в нативный запрос |
| `OptimisticLockException` / 409 при параллельном редактировании | Два писателя обновили одну версию | Ожидаемо; клиент повторяет со свежей версией |
| `getByIdOrThrow` не реализован во время выполнения | Не задан `repositoryBaseClass` | Добавить `@EnableJpaRepositories(repositoryBaseClass = BaseRepositoryImpl.class)` |

## Тестирование

- `mvn test`: равенство сущностей, хелперы предикатов и страниц.
- `mvn verify` (Docker, PostgreSQL): генерация id, аудит, оптимистическая блокировка, мягкое удаление и
  неизменяемость снимков целиком.

## Проектные решения

- **Два семейства сущностей.** Стратегия id и правила изменяемости различаются для своих и отражённых
  данных; их смешение - путь к перезаписи внешнего id сгенерированным.
- **Неизменяемость снимков обеспечивает слушатель**, и его же использует аудиторский след, поэтому в
  платформе один механизм только-добавления.
- **Общие тексты ошибок.** Предметные формулировки принадлежат сервису.
