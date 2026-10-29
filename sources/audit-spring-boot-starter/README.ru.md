# audit-spring-boot-starter

*[English](README.md) · **Русский***

Постоянная половина платформенного аудиторского следа: только-добавляемая таблица `audit_event` и её
JPA-приёмник, outbox-приёмник для отправки событий в SIEM ровно один раз, очистка по сроку хранения по
категориям, которая аудирует собственные удаления, и сторона чтения на QueryDSL. Конверт, SPI приёмника,
политика отказа и редактирование - в [`audit-core`](../audit-core); сначала прочитайте его README.

| | |
|---|---|
| Роль | starter |
| Зависит от (в репозитории) | [`audit-core`](../audit-core), [`db-core`](../db-core), [`job-core`](../job-core), [`outbox-spring-boot-starter`](../outbox-spring-boot-starter), [`web-core-spring-boot-starter`](../web-core-spring-boot-starter) |
| Используется | `user-settings-spring-boot-starter` |
| Поверхности | нет (HTTP-эндпоинта нет по замыслу); опционально Kafka через outbox |

Какие модули что берут:

| Модуль | Берёт | Почему |
|---|---|---|
| security, rest-client, hot-reload | `audit-core` | Ниже `db-core`; получают SLF4J-приёмник |
| export, file-ingest, outbox, reconciliation | `audit-core` | Назначение выбирает развёртывание |
| user-settings | этот стартер | Его след должен быть постоянным и транзакционным |
| сервис | этот стартер | Чтобы получить запрашиваемый след для всего перечисленного |

| Элемент | Что это |
|---|---|
| `AuditEventEntity` | Одна плоская только-добавляемая строка на событие (`SnapshotEntity`: `SnapshotImmutabilityListener` из `db-core` отвергает изменения) |
| `JpaAuditSink` | Пишет `audit_event` в транзакции вызывающего (`Propagation.SUPPORTS`) |
| `OutboxAuditSink` | Отправляет через outbox; id события - ключ идемпотентности и упорядочивания |
| `CategoryFilteringAuditSink` | Allow-list перед outbox-приёмником |
| `AuditEventQueryRepository` / `AuditTrailQuery` | Сторона чтения, QueryDSL, не более 1000 строк за чтение |
| `AuditRetentionPurge` | Очистка по категориям на `job-core` под арендуемым `RunLock` |
| `ActorResolverAuditorProvider` | Питает проставление `created_by` в `db-core` от определения действующего лица следа |
| `AuditMetrics` | Счётчики записанных / упавших / удалённых |
| `AuditProblemMapper` | Незаписываемая строка аудита становится **503**, а не 500 |

## Использование

```xml
<dependency>
    <groupId>ru.ludwigandreas</groupId>
    <artifactId>audit-spring-boot-starter</artifactId>
</dependency>
```

С зависимостью в classpath каждый `AuditSink.record(...)` в сервисе также пишет строку в `audit_event` - в
той же транзакции, что и бизнес-изменение, если она есть.

### Транзакции

`JpaAuditSink` использует `Propagation.SUPPORTS`: внутри бизнес-транзакции строка коммитится или
откатывается вместе с изменением; вне её (отказ авторизации до входа в бизнес-метод) используется
собственная транзакция репозитория.

- Не `REQUIRES_NEW`: след записывал бы откатившиеся изменения.
- Не `MANDATORY`: у наблюдательных событий нет внешней транзакции.

Для категории изменений неудачная вставка помечает транзакцию вызывающего rollback-only, поэтому
`settings` использует `FAIL_OPERATION` (см. audit-core). Отказ виден как **503 Service Unavailable**.

### Чтение следа

```java
List<AuditEventEntity> rows = auditEventQueryRepository.find(AuditTrailQuery.builder()
        .actorSubject("u-123")
        .from(Instant.parse("2026-10-20T00:00:00Z"))
        .to(Instant.parse("2026-10-21T00:00:00Z"))
        .limit(500)
        .build());
```

Объект критериев, только QueryDSL, жёсткий предел `AuditTrailQuery.MAX_LIMIT` (1000) строк: тому, кому
нужен весь след, нужен экспорт, а не куча строк в памяти.

**HTTP-эндпоинт не поставляется.** Кто может читать аудиторский след - самое важное решение авторизации в
подсистеме, и оно разное в каждом развёртывании (роль compliance, break-glass, отдельный внутренний сервис).
Сервис, которому нужен эндпоинт, внедряет репозиторий и монтирует свой, под своей политикой.

### Отправка в SIEM

`OutboxAuditSink` пишет каждое событие в outbox в той же транзакции, что и описываемое изменение, поэтому
SIEM никогда не узнаёт об откатившемся изменении и не пропускает закоммиченное. Тип события outbox -
`audit.event` (payload `AuditEvent`), объявлен бином `OutboxEventTypes` и попадает в AsyncAPI-документ.
Маршрутизируйте его через `ludwig.outbox.routes.*`, как любое событие.

- `AuditEvent.id` - **ключ идемпотентности** (повторная доставка распознаётся) и **ключ упорядочивания**
  (по событию, чтобы след занятого администратора не сериализовался в одну партицию).
- **Выключено по умолчанию, allow-list по категориям.** Outbox требует транзакции; категории, создаваемые
  вне её (отказы доступа, исходящие вызовы), падали бы на бизнес-пути. По умолчанию: `settings`, `export`.
  Allow-list - чтобы новая категория никогда не уходила в SIEM случайно.

### Корреляция

Чтобы записывать correlation id, опубликуйте бин `CorrelationProvider` (см.
[audit-core](../audit-core/README.ru.md#корреляция)).

## Конфигурация

Все свойства связывает `AuditProperties` из `audit-core`. Блоки, реализованные здесь:

| Свойство | По умолчанию | Смысл |
|---|---|---|
| `ludwig.audit.jpa.enabled` | `true` | Писать таблицу `audit_event` |
| `ludwig.audit.outbox.enabled` | `false` | Отправлять события через outbox |
| `ludwig.audit.outbox.categories` | `settings`, `export` | Отправляемые категории (allow-list) |
| `ludwig.audit.retention.enabled` | `true` | Запускать очистку (нужен `job-core`) |
| `ludwig.audit.retention.default-period` | `P7Y` | Срок хранения для неуказанных категорий |
| `ludwig.audit.retention.by-category.<category>` | нет | Срок по категории, например `access: P2Y` |
| `ludwig.audit.retention.interval` | `PT6H` | Интервал очистки |
| `ludwig.audit.retention.batch-size` | `500` | Строк за пачку удаления |
| `ludwig.audit.retention.drain-timeout` | `PT30S` | Ожидание выполняющейся очистки при остановке |
| `ludwig.audit.liquibase.enabled` | `true` | Применять changelog собственным бином `SpringLiquibase` |

Срок хранения - величина compliance, задаётся по категориям, потому что у записи об отказе доступа и
решения о согласии разные правовые основания. Согласуйте значения с владельцем compliance до изменения.

## Развёртывание

**Схема (PostgreSQL).** `db/changelog/audit/audit-changelog.xml`:

| Changeset | Действие |
|---|---|
| `audit-0001` | Создаёт `audit_event` с индексами `(actor_subject, occurred_at)`, `(category, occurred_at)`, `occurred_at`, `(resource_type, resource_id)`, `correlation_id` |
| `audit-0002` | Если есть `user_setting_audit`: копирует её строки в `audit_event` (`subject` → `on_behalf_of`, `actor` → `actor_subject`) |
| `audit-0003` | Если есть `sync_audit_record`: копирует её строки (её `category` становится атрибутом) |
| `audit-0004` | Заменяет маркер `[redacted]` в перенесённых строках на `***REDACTED***` |
| `audit-0006` | Добавляет `actor_via` - сторону, через которую действовало лицо, с частичным индексом по `(actor_via, occurred_at)` |

`audit-0002`/`0003` защищены предусловиями `tableExists` с `onFail="MARK_RAN"`; changeset, помеченный как
выполненный, никогда не пересматривается, поэтому этот changelog выполняется после changelog-ов
user-settings и reconciliation (`@AutoConfigureAfter`). Исходные таблицы **не удаляются**: сверьте число
перенесённых строк и удалите их сами.

**Устройство таблицы.**

| Колонка | Смысл |
|---|---|
| `occurred_at` | Когда произошло - время, которым пользуется аудитор |
| `imported_at` | Когда записана строка (отличается для пересланных событий) |
| `source_system` | Какое развёртывание записало строку (`ludwig.audit.source-system`) |
| `category`, `action`, `outcome`, `reason` | Что произошло и чем закончилось |
| `actor_subject`, `actor_type`, `actor_display_name`, `on_behalf_of` | Кто действовал и для кого |
| `actor_via` | Сторона, через которую действовало лицо (`act.sub` по RFC 8693), например административный UI; null для прямого запроса |
| `resource_type`, `resource_id`, `resource_name` | Что затронуто |
| `correlation_id`, `trace_id` | Ключи связи с логами и трассами |
| `attributes` (`jsonb`) | Детали модуля, уже отредактированные; без GIN-индекса (читаются целиком, фильтруются редко) |

Внешних ключей нет: FK блокировал бы родительскую строку при каждой вставке, а след должен переживать
описываемые строки. `source_version` и `source_timestamp` не используются и остаются null.

**Без `job-core` в classpath ничего не очищается** (очистка - `@ConditionalOnClass(RunLock.class)`).
Бесконечно растущая таблица аудита - риск раскрытия; проверьте, что очистка работает.

## Наблюдаемость

**Метрики:**

| Метрика | Тип | Теги | Смысл |
|---|---|---|---|
| `ludwig.audit.events.written` | counter | `category`, `outcome` | Записанные события |
| `ludwig.audit.sink.failures` | counter | `category`, `policy` | Отказы приёмника при любой политике |
| `ludwig.audit.events.purged` | counter | `category` (`all` для срока по умолчанию) | Строки, удалённые по сроку хранения |

**Логи.** `INFO Audit retention purge removed <n> event(s) from category <c> older than <cutoff>
(<expired> expired)`; `WARN Could not release the audit retention purge lock`.

**Рекомендуемые алерты:**

| Условие | Почему |
|---|---|
| `increase(ludwig_audit_sink_failures_total[5m]) > 0` | В следе не хватает событий (или операции падают для категорий `FAIL_OPERATION`) |
| `ludwig_audit_events_purged_total` не растёт при включённой очистке (проверять еженедельно) | Очистка не работает: нет `job-core` или застряла блокировка |
| Рост размера таблицы `audit_event` (мониторинг БД) | Планирование ёмкости; таблица на пути записи каждой аудируемой операции |

**Известные пробелы:** нет gauge возраста самой старой строки по категории, который прямо показывал бы,
успевает ли очистка.

## Аудит

Очистка записывает сама себя, чтобы «в этом окне ничего не происходило» и «кто-то удалил это окно»
различались:

| Поле | Значение |
|---|---|
| category | `audit` |
| action | `audit.purged` |
| resource | очищенная категория (`audit-event` для срока по умолчанию) |
| outcome | `SUCCESS` или `PARTIAL`, если пачка не охватила все истёкшие строки |
| attributes | category, cutoff, removed, expired |

Событие создаётся после коммита пачки и несёт фактически удалённое число.

## Администрирование

Эндпоинты оператора находятся на административной поверхности платформы, `/admin/v1/{area}`, и существуют только там, где есть поверхность `security-spring-boot-starter`. Каждый защищён одной операцией; какие роли сервиса ею владеют, решает собственный `ludwig.admin.grants` сервиса (роли из резолвера полномочий, никогда не из claims токена), а операция, не выданная ни одной роли, запрещена всем. Вызов уровня `privileged` требует заголовка `Admin-Reason`, и каждый вызов записывается в аудит с категорией `admin` и идентификатором операции в качестве действия. `GET /admin/v1/me/operations` показывает, какие операции есть у вызывающего.

| Операция | Эндпоинты | Что разрешает |
|---|---|---|
| `audit.read` | `GET /admin/v1/audit/events` | Поиск по сохранённому журналу аудита; каждый поиск тоже записывается. |
| `audit.operate` | в этом выпуске нет | Операций нет; журнал только дополняется. |
| `audit.privileged` | в этом выпуске нет | Операций нет; журнал только дополняется. |

Поиск охватывает не более 30 дней, вызывающий типа `USER` видит только события своего арендатора, и каждый поиск сам записывается в журнал. Каждый результат содержит `via` - сторону, через которую действовало лицо; фильтр по ней - `$filter=actorVia eq 'admin-console'`.

**Фильтрация `GET /admin/v1/audit/events`.** Список принимает OData `$filter` и никаких других параметров фильтрации; он листается через `cursor` и `size` в фиксированном порядке от новых к старым, поэтому `$orderby`, `$top`, `$skip` и `$count` - `400`, как и поле, не перечисленное здесь. Фильтр применяется поверх собственных правил области и может их только сузить. Фильтруемые поля: `occurredAt`, `category`, `action`, `outcome`, `actorSubject`, `actorType`, `actorVia`, `onBehalfOf`, `resourceType`, `resourceId`, `correlationId`. Индексированы, поэтому фильтруйте сначала по ним: `occurredAt`, `actorSubject`, `category`, `actorVia`, `resourceType` вместе с `resourceId`, `correlationId`. Не фильтруются: `reason`, отображаемые имена и имя ресурса, арендатор действующего лица, `attributes`. Без фильтра по `occurredAt` поиск охватывает последние 30 дней. Пример: `$filter=outcome eq 'DENIED' and startswith(action, 'pat.')`.

Блок выдачи (имена ролей - собственные роли сервиса):

```yaml
ludwig:
  admin:
    grants:
      "[audit.read]": [ORDERS_ADMIN, ORDERS_SUPPORT]
      "[audit.operate]": [ORDERS_ADMIN]
      "[audit.privileged]": [ORDERS_ADMIN]
```

## Runbook

| Симптом | Вероятная причина | Что делать |
|---|---|---|
| Изменения настроек возвращают 503 | Вставка в `audit_event` падает (схема, диск, права) | Смотреть ошибку БД в логе; исправить и повторить. Изменения откатились, чистить нечего |
| Растёт `ludwig_audit_sink_failures_total` для категорий `LOG_AND_CONTINUE` | Те же причины | Починить приёмник. События окна отказа есть только в SLF4J-логе (если включён); при необходимости полноты следа - переимпортировать из логов |
| Таблица аудита растёт сверх срока хранения | Очистка не работает | Проверить лог старта на `AuditRetentionPurge`, `job-core` в classpath, `ludwig.audit.retention.enabled` и блокировку: `SELECT * FROM job_run_lock WHERE lock_name = 'ludwig-audit-retention-purge';` |
| Результат очистки постоянно `PARTIAL` | Пачка мала для скорости истечения | Увеличить `retention.batch-size` или уменьшить `retention.interval` |
| SIEM ничего не получает | `outbox.enabled=false`, категории нет в allow-list или нет маршрута outbox для `audit.event` | Проверить конфигурацию; доставка - см. runbook outbox |
| `IllegalTransactionStateException` на аудите | В `outbox.categories` добавлена нетранзакционная категория | Убрать её из allow-list |
| Расследовать действия человека | - | `SELECT occurred_at, category, action, outcome, resource_type, resource_id FROM audit_event WHERE actor_subject = '<id>' AND occurred_at BETWEEN '<from>' AND '<to>' ORDER BY occurred_at;` |
| Расследовать запрос | - | `SELECT * FROM audit_event WHERE correlation_id = '<id>' ORDER BY occurred_at;` |

## Тестирование

`AuditStoreIntegrationTest` (Docker, PostgreSQL): JPA-приёмник в транзакции и вне её, откат вместе с
бизнес-изменением, неизменяемость, сторона чтения и её предел, очистка по категориям с событием
самоаудита, outbox-приёмник и его allow-list, миграции старых таблиц со сверкой числа строк с источником
(исходные таблицы создаёт `LegacyAuditTablesInitializer`).

## Проектные решения

- **Одна плоская таблица для всех категорий.** «Что происходило вокруг этого, по порядку» - один запрос, а
  не слияние таблиц подсистем.
- **Только добавление, с принуждением** через неизменяемость `SnapshotEntity`, а не соглашением.
- **`outbox_status_history` остаётся отдельной.** Это операционное состояние, которое читает диспетчер
  outbox; слияние привязало бы доставку к сроку хранения аудита. Её переходы дополнительно записываются сюда.
- **Без защиты от подделки.** Только-добавление - обещание, которое действительно выполняется; см. audit-core.
