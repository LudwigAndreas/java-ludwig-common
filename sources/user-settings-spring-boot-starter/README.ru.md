# user-settings-spring-boot-starter

*[English](README.md) · **Русский***

Движок платформы для пользовательских настроек, предпочтений и согласий - данных, которыми не владеет
OIDC-провайдер. Даёт хранилище, многоуровневое разрешение (пользователь → роль → тенант → платформа → значение по
умолчанию) с указанием уровня-источника, один запрос на субъекта, кэш с вытеснением после коммита, валидацию,
аудиторский след, только-добавляемый журнал согласий, редактирование ПДн, разделение владелец/проекция с событиями
изменений и backfill для наполнения новых проекций. Сервис-потребитель определяет, **какие настройки существуют**, -
типизированными константами в своём коде.

| | |
|---|---|
| Роль | starter |
| Зависит от (в репозитории) | `audit`-стартер, `cache`, `db-core`, `hot-reload`, `messaging`, `observability`, `odata-filter`, `outbox`, `security`, `web-core` (часть опциональна во время выполнения) |
| Используется | `notification-service` |
| Поверхности | опционально REST `/me/settings`, `/admin/v1/settings`; Kafka (владелец публикует, проекция потребляет) |

## Использование

```xml
<dependency>
    <groupId>ru.ludwigandreas</groupId>
    <artifactId>user-settings-spring-boot-starter</artifactId>
</dependency>
```

```java
public final class AccountSettings {

    public static final SettingDefinition<ZoneId> TIMEZONE = SettingDefinition
            .of("user.timezone", ZoneId.class)
            .defaultValue(ZoneId.of("UTC"))
            .category("locale")
            .userEditable(true)
            .build();
}

@Bean
SettingDefinitionSource accountSettings() {
    return SettingDefinitionSource.of(AccountSettings.TIMEZONE);
}
```

```java
ZoneId zone = settings.get(PrincipalRef.user(subject), AccountSettings.TIMEZONE);
```

**Схема - это код; общий только движок.** `SettingDefinition<T>` типизирован в каждом месте вызова, виден в «find
usages» и переименовывается рефакторингом. Хранилище обобщённое (текст плюс признак типа), что допустимо **только
потому, что по значениям настроек ничего не соединяется, не фильтруется и не строится отчётов** - индекса по колонке
значения нет. Новая настройка требует деплоя объявляющего сервиса; ни один сервис не получит настройку, которую не
понимает.

### Режимы владельца и проекции

| | Владелец | Проекция |
|---|---|---|
| Владеет таблицами | да | хранит реплику |
| `SettingsLookup` | да | да (тот же интерфейс) |
| `SettingsWriter` | да | **бина нет вовсе** (его внедрение валит старт) |
| `ConsentService` | чтение и запись | только чтение |
| События изменений | публикует через outbox | потребляет |

```yaml
ludwig.user-settings.owner.enabled: true        # сервис-владелец
ludwig.user-settings.projection.enabled: true   # сервис-читатель
```

Ни один режим не включён по умолчанию; оба сразу - ошибка старта.

### Разрешение

```
USER  →  ROLE  →  TENANT  →  PLATFORM  →  DEFAULT
```

| Уровень | Источник | Кто задаёт |
|---|---|---|
| `USER` | строка субъекта | сам субъект или администратор |
| `ROLE` | строка на каждую роль субъекта | администратор |
| `TENANT` | строка тенанта или настроенное значение тенанта | администратор / конфигурация |
| `PLATFORM` | только конфигурация (перезагружаемая) | эксплуатация |
| `DEFAULT` | определение | разработчик |

```java
ResolvedValue<ZoneId> zone = settings.getAll(ref).resolved(AccountSettings.TIMEZONE);
zone.value();    // Europe/Moscow
zone.layer();    // TENANT
zone.scopeId();  // acme
```

- **`getAll` - один запрос** к таблицам настроек (набор scope вычисляется заранее; тест считает JDBC-выражения).
  `get` идёт тем же путём. Используйте `getAll`, когда нужно больше одной настройки субъекта.
- **Порядок ролей** - политика `SettingScopeResolver`; поставляемый сохраняет порядок `AuthorityLookup`.
- **Ограничение тенантом обязательно**: у каждой строки ненулевой `tenant_id`; `SettingsSubject` нельзя построить без
  тенанта; неопределимый тенант - отказ (`TenantUnresolvableException`); администратор читает в своём тенанте; ключ
  кэша включает тенант. Вне запроса используйте перегрузки с `SettingsSubject`:

```java
settings.getAll(new SettingsSubject(PrincipalRef.user(subject), tenantId));
```

### Объявление настроек

```java
SettingDefinition.of("user.timezone", ZoneId.class)   // ключ и тип обязательны
        .defaultValue(ZoneId.of("UTC"))
        .category("locale")        // группировка в UI и метрике записей
        .userEditable(true)        // по умолчанию false
        .pii(false)                // true редактирует значение везде, кроме таблицы настроек
        .validator(SettingValidator.range(1, 100))
        .jsonEncoded(false)        // true хранит значение как JSON
        .description("...")
        .build();
```

Ключи соответствуют `^[a-z][a-z0-9]*([.-][a-z0-9]+)*$`. **Старт отвергает**: повтор ключа с другим объявлением
(одинаковое объявление дважды допустимо), тип без конвертера, значение по умолчанию, не проходящее свою валидацию,
значение по умолчанию, не переживающее круг сохранения. Ссылка на нигде не объявленное определение падает при первом
использовании с `UnknownSettingException`.

Встроенные типы: `String`, `Boolean` (строгий разбор), `Integer`, `Long`, `Double`, `BigDecimal`, `Instant`,
`Duration`, `LocalTime`, `ZoneId` (id региона, не смещение), `Locale`, `UUID`, любой `enum`. Остальным нужен бин
`SettingValueConverter` или `jsonEncoded(true)` (JSON никогда не применяется автоматически).

### Запись

```java
writer.set(ref, AccountSettings.TIMEZONE, ZoneId.of("Europe/Moscow"));
writer.reset(ref, AccountSettings.TIMEZONE);
writer.setAll(ref, List.of(
        SettingUpdate.of(AccountSettings.TIMEZONE, ZoneId.of("Europe/Moscow")),
        SettingUpdate.reset(AccountSettings.LOCALE)));

writer.setForScope(adminSubject, SettingScope.tenant("acme"), AccountSettings.TIMEZONE, zone);
```

- Каждая запись проверяет, аудирует, публикует и вытесняет в одной транзакции; пакетное обновление проверяет **все**
  изменения до записи любого и сообщает все отказы.
- `reset` удаляет значение субъекта (снова отвечают нижние уровни) и оставляет **надгробие**, чтобы проекция
  оставалась устойчивой к порядку; надгробия удаляются очисткой.
- Две одновременные записи одной настройки: проигравший получает `DataIntegrityViolationException` (никакого тихого
  «последний побеждает»).
- **Доступ**: субъект читает и пишет свои настройки; остальное требует прав администратора **и** того же тенанта,
  проверяется внутри модуля. `userEditable(false)` ограничивает субъекта, но не администратора.
- **Чтение администратором чужих настроек аудируется** (самостоятельное чтение - нет).

### Согласия

Согласия - доказательство и накапливаются; настройки - состояние и перезаписываются.

```java
consents.grant(ref, ConsentGrant.builder()
        .consentKey("marketing.email")
        .textVersion("2026-01-v3")     // обязательно
        .locale("ru-RU")
        .evidenceIp(request.getRemoteAddr())
        .evidenceUserAgent(request.getHeader(USER_AGENT))
        .build());

consents.revoke(ref, grant);                       // новая строка, никогда не обновление
consents.currentState(ref).isGranted("marketing.email");
consents.currentState(ref).isGrantedForVersion("marketing.email", "2026-02-v4");
consents.stateAsOf(ref, whenTheEmailWentOut);      // на что было согласие в тот момент
```

- **Только добавление, обеспечено трижды**: слушатель `SnapshotEntity`, триггер БД, запрещающий `UPDATE` (не
  `DELETE`, чтобы можно было исполнить политику хранения), и отсутствие пути обновления в коде.
- **Версия текста обязательна**: запись отвечает, *на что* дано согласие.
- **Текущее состояние вычисляется** сверткой журнала; таблицы текущих согласий нет.
- Первичный ключ владельца переиспользуется проекциями, поэтому повторная доставка конфликтует, а не дублирует.

### Проекция

- **Идемпотентна** (событие настройки несёт строку целиком; событие согласия - id владельца).
- **Устойчива к порядку**: событие старше сохранённого `changed_at` отбрасывается
  (`projection.dropped{reason=stale}`).
- **Вытеснение кэша после коммита.**
- Контейнер из [messaging](../messaging-spring-boot-starter): подтверждение по записи, backoff, dead-letter топик.
  Неразбираемые payload и неизвестные типы событий отбрасываются/игнорируются.
- Диспетчеризация по заголовку `ludwig-event-type` (`MessageHeaders`).

| Тип события | Payload | Когда |
|---|---|---|
| `UserSettingChanged` | `UserSettingChangedEvent` | значение задано, изменено или сброшено на любом уровне |
| `ConsentGranted` | `ConsentChangedEvent` | согласие дано |
| `ConsentRevoked` | `ConsentChangedEvent` | согласие отозвано |

Ключ упорядочивания `tenant/LAYER/scopeId`. Объявлены как `OutboxEventTypes` в режиме владельца с публикацией.

### Backfill (наполнение новой проекции)

Поток несёт **только изменения**; новый потребитель настройки двухлетней давности получил бы значения по умолчанию
для всех, кто задал её давно. Повтор топика ограничен retention; компакция не работает (ключ записи - субъект, а не
субъект + настройка). `SettingsBackfillService` (режим владельца, присутствует всегда) переопубликует текущее
состояние владельца обычными событиями:

```java
backfill.backfill(SettingsBackfillRequest.forSettings("acme", "user.timezone"));   // одна настройка, один тенант
backfill.backfill(SettingsBackfillRequest.forTenant("acme"));                       // весь тенант
SettingsBackfillResult result = backfill.backfill(SettingsBackfillRequest.builder()
        .batchSize(1000).maxRows(100_000).build());                                  // все тенанты, с бюджетом
if (!result.complete()) { /* запустить снова; курсора нет */ }
```

- **Безопасно в любой момент**: события несут исходный `changed_at`, поэтому актуальная реплика их отбрасывает.
  Повторный запуск продолжает работу.
- Надгробия переопубликуются как удаления; согласия - построчно без ключа идемпотентности outbox.
- Строк аудита нет (ничего не меняется); значения ПДн переопубликуются, как и в исходных событиях.
- Keyset-обход, транзакция на пачку. `maxRows` - бюджет, общий для обоих обходов (сначала настройки) - при бюджете
  запускайте настройки и согласия отдельно (`includeConsents(false)` / `includeSettings(false)`).
- `tenantId == null` означает все тенанты (через REST невыразимо).
- `POST /admin/v1/settings/backfill` (`{ "settingKeys": [...], "includeConsents": false, "maxRows": 50000 }` →
  `{ "settingRows": 41230, "consentRows": 0, "batches": 83, "complete": false }`) требует `web.enabled`,
  `web.backfill-enabled` и режим владельца, плюс операцию `settings.operate`; тенант - тенант вызывающего.
- Промежуточные значения, пропущенные потребителем, не восстанавливаются - только текущее состояние (и весь журнал
  согласий).

### Кэширование

Платформенный кэш `settings` (`SettingsCaches`, `purpose: performance`, TTL по умолчанию 5 мин) через
[cache](../cache-spring-boot-starter). Одна запись - **весь** набор субъекта. Согласованность обеспечивает вытеснение
после коммита; TTL - страховка. Запись на уровне роли, тенанта или платформы очищает весь кэш. Вытеснение субъекта во
всех тенантах - `SettingsCaches.evictAcrossTenants` (O(n) `evictByScan`; поэтому общий уровень Redis для этого кэша
запрещён).

### Локаль и пояс вызывающего для web-core

`StoredUserPreferenceSource` передаёт `user.locale` / `user.timezone` в предпочтения web-core, поэтому каждый
`Instant` в ответе отображается в поясе пользователя. Он отвечает, только если значение задано на `USER`, `ROLE` или
`TENANT`; на `PLATFORM`/`DEFAULT` воздерживается, чтобы работали `Accept-Language` и конфигурация. Без объявленных
определений бин всё равно есть и сообщает об этом при старте. `ludwig.user-settings.preferences.enabled=false`
отключает его.

### Общеизвестные настройки (по выбору)

```java
@Bean
SettingDefinitionSource platformSettings() {
    return WellKnownSettings.source();   // user.locale, user.timezone, тихие часы, дайджест
}

@Bean
SettingDefinitionSource notificationOptOuts() {
    return SettingDefinitionSource.of(
            WellKnownSettings.channelOptOut("billing", "email"),
            WellKnownSettings.channelOptOut("billing", "chat"));
}
```

Ничего не регистрируется автоматически (`getAll` возвращает ровно объявленное сервисом). `QuietHours` - одна
настройка, а не три, чтобы уровни не смешали начало одного уровня с концом другого.

### REST-эндпоинты (выключены по умолчанию, `web.enabled`)

| Метод | Путь | Примечания |
|---|---|---|
| `GET` | `/me/settings` | свои настройки, разрешённые |
| `PUT` | `/me/settings` | пакетное обновление; значения - закодированный текст |
| `POST` | `/me/settings/reset` | ключи в теле (в них точки) |
| `GET` | `/me/settings/consents` | текущее состояние |
| `POST` | `/me/settings/consents/grant` \| `/revoke` | доказательства берутся из запроса, никогда из тела |
| `GET` | `/admin/v1/settings/subjects/{subject}` | ПДн отредактированы; аудируется |
| `PUT` | `/admin/v1/settings/scopes/{scopeType}/{scopeId}` | только режим владельца |
| `GET` | `/admin/v1/settings/subjects/{subject}/consents[/asof?at=…]` | журнал на любой момент |

Контроллер самообслуживания не берёт субъекта из запроса. Административный поиск OData не раскрывает значений
(`value_text` и доказательства согласий не `@Filterable`).

### Точки расширения

Все `@ConditionalOnMissingBean`: `SettingScopeResolver` (наследование через группы/подразделения),
`SettingsTenantResolver`, `SettingValueSource`, `SettingValueConverter`, `SettingsMetrics`, `SettingsEventPublisher`.

## Конфигурация

| Свойство | По умолчанию | Смысл |
|---|---|---|
| `ludwig.user-settings.enabled` | `true` | Главный выключатель |
| `ludwig.user-settings.default-tenant` | `default` | Тенант для вызывающих без него. **В многотенантных развёртываниях задайте пустым** |
| `ludwig.user-settings.owner.enabled` | `false` | Владеть таблицами, принимать записи, публиковать |
| `ludwig.user-settings.owner.publish-events` | `true` | Публиковать через outbox (ничего, если outbox нет) |
| `ludwig.user-settings.projection.enabled` | `false` | Держать реплику только для чтения |
| `ludwig.user-settings.projection.topic` | `user.settings` | Топик изменений владельца |
| `ludwig.user-settings.projection.group-id` | `${spring.application.name}-user-settings-projection` | Своя группа для каждого сервиса-реплики |
| `ludwig.user-settings.projection.source-system` | `user-settings-owner` | `source_system` в спроецированных согласиях |
| `ludwig.cache.caches.settings.enabled` / `ttl` / `maximum-size` | `true` / `5m` / `10000` | Кэш настроек |
| `ludwig.user-settings.access.allow-unauthenticated` | `true` | Внутрипроцессные вызовы без принципала (воркеры, задачи) |
| `ludwig.user-settings.web.enabled` | `false` | Монтировать REST-контроллеры |
| `ludwig.user-settings.web.base-path` | `/me/settings` | Административные эндпоинты - на фиксированном `/admin/v1/settings` |
| `ludwig.user-settings.web.backfill-enabled` | `false` | Монтировать эндпоинт backfill |
| `ludwig.user-settings.retention.enabled` | `false` | Очистка надгробий |
| `ludwig.user-settings.retention.tombstones` | `30d` | Срок хранения надгробий |
| `ludwig.user-settings.retention.batch-size` / `interval` | `500` / `1h` | Пачки очистки |
| `ludwig.user-settings.metrics.enabled` | `true` | |
| `ludwig.user-settings.liquibase.enabled` | `true` | Собственный бин `SpringLiquibase` |
| `ludwig.user-settings.preferences.enabled` | `true` | Передавать локаль/пояс в web-core |
| `ludwig.user-settings.platform-defaults[<key>]` | - | Значение развёртывания (закодированный текст) |
| `ludwig.user-settings.tenant-defaults[<tenant>][<key>]` | - | Значение тенанта |

```yaml
ludwig:
  user-settings:
    platform-defaults:
      "[user.timezone]": Europe/Moscow     # в ключах точки: квадратные скобки
    tenant-defaults:
      acme:
        "[user.locale]": ru-RU
```

Значение для необъявленной настройки пропускается, а не валит старт. Значения тенанта и платформы перезагружаются с
[hot-reload](../hot-reload-spring-boot-starter) (кэш при этом очищается); определения - нет.
`ludwig.user-settings.retention.audit` связывается, но не читается: срок хранения следа изменений -
`ludwig.audit.retention.by-category.settings` (по умолчанию 7 лет).

## Развёртывание

- **Схема** `db/changelog/user-settings/user-settings-changelog.xml` (id `user-settings-NNNN`, собственный бин или
  подключение с `liquibase.enabled=false`): `user_setting_value` (одно значение на тенант, тип и id scope, ключ, плюс
  надгробия) и `user_consent` (журнал только-добавления с триггером против обновлений). Одни таблицы для обоих режимов.
  Старая таблица `user_setting_audit`, если есть, переносится в `audit_event` changeset-ом `audit-0002` аудит-стартера
  и остаётся для сверки и удаления вами.
- **Kafka** (проекция): топик и его dead-letter `<topic>.dlt` (по умолчанию `user.settings.dlt`); своя группа на
  каждый сервис-потребитель.
- **Владелец**: маршрут outbox для трёх типов событий.
- **Аудит-стартер** должен присутствовать: записи аудита `settings` идут в `audit_event` в транзакции вызывающего.

## Наблюдаемость

| Метрика | Тип | Теги | Смысл |
|---|---|---|---|
| `ludwig.user.settings.cache` | counter | `result` (`hit`/`miss`) | Эффективность кэша (также общие метрики `ludwig.cache.*`) |
| `ludwig.user.settings.resolution` | timer (гистограмма перцентилей) | | Задержка разрешения |
| `ludwig.user.settings.resolution.size` | summary | | Настроек за разрешение |
| `ludwig.user.settings.write` | counter | `category`, `layer`, `action` (`set`/`reset`) | Записи |
| `ludwig.user.settings.consent` | counter | `consent`, `decision` | Решения о согласиях |
| `ludwig.user.settings.admin.read` | counter | | Чтения чужих настроек администраторами |
| `ludwig.user.settings.projection.dropped` | counter | `reason` (`stale`) | Устаревшие события проекции (всплеск при backfill - ожидаем) |
| `ludwig.user.settings.value.unreadable` | counter | `layer` | Сохранённые значения, которые эта сборка не читает |
| `ludwig.user.settings.backfill.published` | counter | `kind` (`setting`/`consent`) | Строки backfill |

Ни одна метрика не помечена значением настройки или id субъекта.

**Логи.** `INFO`: зарегистрированные определения, перезагрузка конфигурации с очисткой кэша, очистка надгробий,
начало/конец backfill. `WARN`: отброшенные некорректные/неупорядочиваемые события, отказ в межтенантном доступе,
нечитаемые сохранённые значения, явная очистка согласий. `ERROR`: неразбираемое событие.

**Рекомендуемые алерты:** рост `ludwig_user_settings_value_unreadable_total` (значение от более новой сборки или
повреждённая строка); алерты dead-letter messaging по топику проекции; падение доли попаданий кэша; лаг проекции.

## Аудит

Категория `settings`, запись **внутри транзакции вызывающего**; политика отказа для `settings` по умолчанию
`FAIL_OPERATION`, поэтому неудачная запись аудита откатывает изменение, и вызывающий получает
`AuditWriteFailedException` → **503** (повтор корректен).

| Действие | Когда |
|---|---|
| `setting.set` | значение задано на любом уровне |
| `setting.reset` | значение сброшено |
| `setting.admin-read` | администратор прочитал чужие настройки |

Записывается: кто (`actor_subject`), для кого (`on_behalf_of`), определение, старое → новое, уровень, correlation id.
Значения определений `pii(true)` - `***REDACTED***` (также в логах, документах проблем и ответах администраторам);
отклонённые значения никогда не повторяются в ошибках валидации. Модуль поставляет `DeclaredSensitivityClassifier` для
платформенного редактирования. **Согласия доказываются журналом `user_consent`**, а не `audit_event`.

| Данные | Срок хранения | Очищается автоматически |
|---|---|---|
| `audit_event` (`category=settings`) | 7 лет | да, аудит-стартером |
| надгробия `user_setting_value` | 30 дней | при `retention.enabled=true` |
| `user_consent` | - | **никогда** (`SettingsRetentionService.purgeConsents` есть для оператора, действующего по письменной правовой политике) |

## Администрирование

Эндпоинты оператора находятся на административной поверхности платформы, `/admin/v1/{area}`, и существуют только там, где есть поверхность `security-spring-boot-starter`. Каждый защищён одной операцией; какие роли сервиса ею владеют, решает собственный `ludwig.admin.grants` сервиса (роли из резолвера полномочий, никогда не из claims токена), а операция, не выданная ни одной роли, запрещена всем. Вызов уровня `privileged` требует заголовка `Admin-Reason`, и каждый вызов записывается в аудит с категорией `admin` и идентификатором операции в качестве действия. `GET /admin/v1/me/operations` показывает, какие операции есть у вызывающего.

| Операция | Эндпоинты | Что разрешает |
|---|---|---|
| `settings.read` | `GET /admin/v1/settings/subjects/{subject}`<br>`GET /admin/v1/settings/subjects/{subject}/consents`<br>`GET /admin/v1/settings/subjects/{subject}/consents/asof` | Просмотр итоговых настроек и истории согласий другого субъекта. |
| `settings.operate` | `PUT /admin/v1/settings/scopes/{scopeType}/{scopeId}`<br>`POST /admin/v1/settings/backfill` | Изменение настроек на уровне пользователя, роли или арендатора и запуск повторной публикации. |
| `settings.privileged` | в этом выпуске нет | Операций пока нет; уровень зарезервирован, чтобы выдачи для этой области не менялись между выпусками. |

**Удалено:** `/admin/settings/**`, `ludwig.user-settings.access.admin-authority` и `ludwig.user-settings.web.admin-base-path`; `consents/as-of` теперь `consents/asof`. Подключается только при `ludwig.user-settings.web.enabled=true` в режиме владельца (для повторной публикации также `web.backfill-enabled=true`). Субъект другого арендатора - `404` (было `403`); эндпоинты самообслуживания сохраняют `403`.

Блок выдачи (имена ролей - собственные роли сервиса):

```yaml
ludwig:
  admin:
    grants:
      "[settings.read]": [ORDERS_ADMIN, ORDERS_SUPPORT]
      "[settings.operate]": [ORDERS_ADMIN]
      "[settings.privileged]": [ORDERS_ADMIN]
```

## Runbook

| Симптом | Вероятная причина | Что делать |
|---|---|---|
| Новый потребитель отдаёт значения по умолчанию пользователям, задавшим свои | Проекция наполнялась только изменениями | Выполнить backfill для настройки/тенанта (безопасно повторять) |
| Настройки «откатываются» после изменения | Неупорядоченная доставка должна отбрасываться; проверить, что проекция читает тот же топик и `changed_at` задан | Смотреть `ludwig_user_settings_projection_dropped_total` и лог listener-а |
| Изменения настроек падают с 503 | Не удаётся вставка в `audit_event` | Починить приёмник аудита (см. аудит-стартер); изменения откатились |
| `TenantUnresolvableException` | Вызывающий без тенанта в многотенантном развёртывании или задача использует перегрузки с принципалом | Использовать `SettingsSubject` с явным тенантом |
| `UnknownSettingException` | Определение не добавлено ни одним `SettingDefinitionSource` | Зарегистрировать его |
| Старт падает на определении | Конфликтующие дубликаты, неконвертируемый тип, плохое значение по умолчанию | Исправить объявление |
| Растёт `value.unreadable` | Кодировка от другой сборки | `SELECT tenant_id, scope_type, scope_id, setting_key, value_type FROM user_setting_value WHERE setting_key = '<key>';` согласовать конвертеры/типы |
| Изменённое значение тенанта не видно | Кэш | Перезагрузка его очищает; иначе ждать TTL |
| Пользователь просит удалить свои данные | - | Удалить его строки `user_setting_value`; согласия - по правовой политике хранения |

## Тестирование

Модульные (приоритет разрешения, проверка при старте, конвертация, объединение запросов кэша, политика доступа,
порядок проекции, пагинация и бюджет backfill, контекст, отвергающий сломанное определение) и интеграционные на
Testcontainers с PostgreSQL и брокером (режимы владельца и проекции, неупорядоченные и повторные события, вытеснение
после коммита, изоляция тенантов, неизменяемость согласий против триггера, `getAll` = ровно одно выражение, backfill
против настоящих строк outbox), плюс `ArchitectureTest`.

## Проектные решения

- **Типизированные определения в коде**, никогда EAV-схема, правимая во время работы.
- **Нет режима по умолчанию**: угадать владельца или проекцию - ошибка в любом случае.
- **`SettingsWriter` отсутствует в режиме проекции**, поэтому неверная сборка падает при старте.
- **Согласия - отдельный журнал только-добавления**, а не перезаписываемая настройка.
- **Уровень сообщается с каждым значением** - для «унаследовано от организации» и для поддержки.
- **Ни одно значение не фильтруется, не индексируется и не становится тегом метрики.**
