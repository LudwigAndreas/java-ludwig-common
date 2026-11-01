# identity-projection-spring-boot-starter

*[English](README.md) · **Русский***

Локальная проекция пользователей и ролей, известных провайдерам идентичности сервиса, с возможностью запросов. Её
наполняет **один нейтральный к провайдеру поток изменений** из любого числа объявленных **источников**; рядом -
реализации трёх SPI [`security-spring-boot-starter`](../security-spring-boot-starter) поверх базы данных:
пользователи, роли, реестр партнёров и ограниченные по времени выдачи областей данных в PostgreSQL; упорядоченное
идемпотентное применение; владение источником и сопоставление ролей; полные обходы с защитным порогом; вытеснение
кэша прав после коммита; и проекция подтверждённых контактов по выбору - для единственного сервиса, который пишет
людям.

| | |
|---|---|
| Роль | starter |
| Зависит от (в репозитории) | [`db-core`](../db-core), [`security-spring-boot-starter`](../security-spring-boot-starter) |
| Используется | `crud-service-example`, `notification-service` |
| Каналы | [`identity-source-kafka`](../identity-source-kafka-spring-boot-starter) (push), [`identity-source-directory`](../identity-source-directory-spring-boot-starter) (pull) или собственный код сервиса |

| SPI | Реализация | Источник |
|---|---|---|
| `AuthorityResolver` | `DatabaseAuthorityResolver` | `security_user` (каналы), `security_partner` (таблица), `ludwig.identity.service-roles` (конфигурация) |
| `DataScopeProvider` | `DatabaseDataScopeProvider` | `security_grant` - явные выдачи, при необходимости с истечением |
| `PartnerIdentityResolver` | `DatabasePartnerIdentityResolver` | `security_partner` - подключение и приостановка партнёров без выпуска |

Локальная проекция избавляет от сетевого вызова провайдера при каждом решении об авторизации и делает «всех агентов
этого арендатора» или «кто ещё держит эту роль» запросом. Проекция ничего не знает о провайдерах: провайдеру нужен
канал, а не форк этого модуля.

## Использование

```xml
<dependency>
    <groupId>ru.ludwigandreas</groupId>
    <artifactId>identity-projection-spring-boot-starter</artifactId>
</dependency>
<!-- и канал для вашего провайдера, например: -->
<dependency>
    <groupId>ru.ludwigandreas</groupId>
    <artifactId>identity-source-kafka-spring-boot-starter</artifactId>
</dependency>
```

```yaml
ludwig:
  identity:
    sources:
      oidc: {}          # каждый источник, который принимает сервис, объявляется; канал с другим роняет старт
```

Запасные реализации стартера безопасности уступают место (`@ConditionalOnMissingBean`); больше ничего связывать не
нужно.

### Источники и сопоставление ролей

**Источник** - один провайдер идентичности. Каждое изменение называет свой источник, а пользователь принадлежит
источнику, который первым его спроецировал (`security_user.source_system`): изменение для этого субъекта из другого
источника отклоняется, учитывается (`outcome="foreign-source"`) и пишется в журнал с уровнем error, но никогда не
применяется. Сервис с двумя провайдерами - сотрудники и клиенты, например - объявляет оба.

```yaml
ludwig:
  identity:
    sources:
      staff:
        role-mapping:                         # роль или группа провайдера -> роли платформы
          okta-catalog-admins: [CATALOG_ADMIN, ORDER_AGENT]
          okta-support: [ORDER_SUPPORT]
        unmapped-roles: DROP                  # KEEP (по умолчанию) сохраняет роль, которой нет в сопоставлении
      customers: {}
```

Имена ролей сравниваются без префикса `ROLE_`; хранится сопоставленный набор. Имя источника - строчные латинские
буквы, цифры и дефисы. Объявленный источник без канала один раз записывается в журнал при старте и допустим
(административный сервис, читающий таблицы; источник, который только засеивается локально).

### Как написать канал

Всё попадает в проекцию через `IdentityProjection`:

```java
@RestController
class DirectoryWebhook {

    private final IdentityProjection projection;

    DirectoryWebhook(IdentityProjection projection) {
        this.projection = projection;
    }

    @PostMapping("/hooks/directory")
    void changed(@RequestBody ProviderUser user) {
        projection.apply(new IdentityChange.Upsert("staff", user.id(), user.updatedAt(), user.etag(),
                new UserProfile(user.name(), user.orgId(), user.groups(), null)));
    }
}
```

- **`Upsert`** несёт профиль. Набор ролей, если он есть, - **полный** набор, никогда не дельта (пустой отзывает все
  роли); любое поле, оставленное null, включая набор ролей, сохраняет хранимое значение, потому что провайдеры
  присылают неполные профили.
- **`Disable`** и **`Remove`** ставят статус `DISABLED` / `REMOVED`; строка остаётся, поэтому записи аудита и ссылки
  `created_by` продолжают разрешаться.
- **`occurredAt` - ключ упорядочивания**: изменение не новее хранимого пропускается как `STALE` (нормально при
  повторе и перестановке), поэтому повтор никогда не возвращает отозванную роль.
- `applyAll(list)` применяет пакет в одной транзакции, присоединяясь к активной, - так pull-канал может
  зафиксировать страницу вместе со своей водяной отметкой. Каждый вызов возвращает `ApplyOutcome` на каждое изменение.
- Зарегистрируйте бин `IdentityFeed` (`source()`, `kind()`, `status()`), чтобы старт проверил источник, а список
  источников показал канал.

### Полные обходы

Канал, который может перечислить источник полностью, выполняет обход: `beginSweep(source)`, `listed(subjects)` на
каждую страницу, затем `complete()`. Каждый активный пользователь этого источника, которого обход не перечислил,
отключается - так исправляется удаление, событие о котором потерялось. Обход, который отключил бы больше
`ludwig.identity.sweep.max-disable-ratio` (`0.1`) активных пользователей источника, **прерывается без изменений**,
пишется в журнал с уровнем error и учитывается, потому что пустой или усечённый список не должен заблокировать
организацию. Пользователя, отключённого обходом, восстанавливает его следующий upsert, каким бы ни был его
`occurredAt`: решение основывалось на отсутствии, а не на изменении в источнике. Перечисленные субъекты складываются в
`identity_sweep_listed`, поэтому каталог в миллион пользователей не стоит памяти.

### Контактные данные (по выбору)

Контактные поля сохраняются **только** при `ludwig.identity.contact.enabled=true` и очищаются в строке пользователя
при следующем его изменении, если флаг выключен. Включайте только в сервисе, который отправляет людям сообщения
(проекция реплицируется в каждый сервис с модулем, и каждая копия - ещё одно место, куда должен дойти запрос на
удаление). Индексов по контактам нет (поиск идёт по subject). `emailVerified` / `phoneVerified` допускают null: null
означает «провайдер не сообщил», что отличается от «не подтверждён».

### Выдачи и роли

| | Область роли (`ludwig.security.roles`) | Строка выдачи (`security_grant`) |
|---|---|---|
| Говорит | «агенты видят свои дела» | «Анна замещает Берлин, пока Боб в отпуске» |
| Это | правило системы | данные о человеке |
| Меняется | pull request-ом и деплоем | действием администратора |
| Истекает | никогда | `expires_at`, проверяется в запросе |

Выдача может только **расширить** разрешённое ролями; сужение делается удалением ролей. Ограничение по времени
проверяется в `WHERE`, поэтому ничего не нужно потом подчищать.

### Соседние сервисы

```yaml
ludwig:
  identity:
    service-roles:
      "[spiffe://mesh/ns/orders/sa/orders]":
        - ROLE_CATALOG_READER
```

Конфигурация, а не таблица: кто кого может вызывать - топология развёртывания, проверяемая вместе с кодом вызова.

### Задержка отзыва

```
изменение в источнике -> канал -> коммит проекции -> вытеснение кэша прав (после коммита) -> следующий запрос видит
```

Вытеснение - `cache.evictAfterCommit(ref)` из [cache](../cache-spring-boot-starter). TTL кэша прав
(`ludwig.cache.caches.authorities.ttl`, по умолчанию 30s) - страховка на случай *пропущенного* изменения.

## Конфигурация

| Свойство | По умолчанию | Значение |
|---|---|---|
| `ludwig.identity.enabled` | `true` | Главный выключатель |
| `ludwig.identity.sources.<name>.role-mapping` | `{}` | Роль или группа провайдера → роли платформы |
| `ludwig.identity.sources.<name>.unmapped-roles` | `KEEP` | `KEEP` или `DROP` для роли, которой нет в сопоставлении |
| `ludwig.identity.sweep.max-disable-ratio` | `0.1` | Наибольшая доля активных пользователей источника, которую может отключить один обход |
| `ludwig.identity.grants-enabled` | `true` | Регистрировать `DataScopeProvider` на таблице выдач |
| `ludwig.identity.partner-registry-enabled` | `true` | Регистрировать `PartnerIdentityResolver` на таблице |
| `ludwig.identity.liquibase.enabled` | `true` | `false`, если changelog подключается из вашего собственного мастер-файла |
| `ludwig.identity.contact.enabled` | `false` | Проецировать подтверждённые контакты |
| `ludwig.identity.service-roles` | `{}` | Идентификатор нагрузки → роли |

**Удалено:** `ludwig.identity.kafka.topic` роняет старт с указанием
`ludwig.identity.kafka.subscriptions.<source>.topic` в [`identity-source-kafka`](../identity-source-kafka-spring-boot-starter);
потребителя в проекции больше нет.

## Развёртывание

- **Схема**: `db/changelog/identity/identity-changelog.xml` (идентификаторы changeset с пространством имён),
  отдельно или через включение.

  | Таблица | Содержит | Владелец |
  |---|---|---|
  | `security_user` (+ `security_user_role`) | проекция; PK = subject источника (`ExternalEntity`), `source_system` = его источник | источники, через каналы |
  | `identity_sweep_listed` | субъекты, отложенные идущим обходом; пуста между обходами | проекция |
  | `security_partner` (+ `security_partner_role`) | реестр партнёров: код, идентификаторы сертификатов, статус, роли | администраторы (`AuditedEntity`) |
  | `security_grant` | явные выдачи областей данных, при необходимости с истечением | администраторы (`AuditedEntity`) |

- **Защита данных**: `security_user` содержит персональные данные; включите её в процедуры удаления. С включёнными
  контактами в ней хранятся адреса.

## Наблюдаемость

| Метрика | Теги | Значение |
|---|---|---|
| `ludwig_identity_changes_total` | `source`, `type` (`upsert`, `disable`, `remove`), `outcome` (`applied`, `stale`, `foreign-source`, `discarded`) | Каждое изменение, доставленное каналом, по тому, что с ним сделано |
| `ludwig_identity_sweeps_total` | `source`, `outcome` (`completed`, `aborted`) | Полные перечисления |
| `ludwig_identity_sweep_disabled_total` | `source` | Пользователи, отключённые, потому что перечисление их больше не содержало |

Метрики конкретного канала (отставание потребителя, запуски синхронизации) - в модулях каналов.

**Журнал.** `ERROR Refusing a ... change for subject ... from source ...: the subject belongs to source ...`;
`ERROR Aborted the sweep of identity source ...`; `WARN Discarding a ... change ... with no subject`. Профили
никогда не пишутся в журнал (данные каталога о человеке).

**Рекомендуемые оповещения:** любой `outcome="foreign-source"` (два провайдера выдают один субъект); любой
прерванный обход; рост `outcome="discarded"`.

## Аудит

Проекция не пишет событий аудита: она отражает каталоги провайдеров, у которых есть собственный журнал.

Изменения `security_partner` и `security_grant` через административные эндпоинты (`/admin/v1/identity/`)
записываются в журнал аудита (категория `admin`) в той же транзакции, поэтому у «кто, что и когда выдал» есть
история, а не только последний `updated_by`. У изменения, сделанного вручную в SQL, остаются только отметки.

## Администрирование

Эндпоинты оператора находятся на административной поверхности платформы, `/admin/v1/{area}`, и существуют только там, где есть поверхность `security-spring-boot-starter`. Каждый защищён одной операцией; какие роли сервиса ею владеют и с какой областью, решает собственный `ludwig.security.roles` сервиса (роли из резолвера полномочий, никогда не из claims токена), а операция, которой не владеет ни одна роль, запрещена всем. Операция `PRIVILEGED` требует заголовка `Operation-Reason` и не принимает персональный токен доступа, и каждый вызов записывается в аудит с категорией `admin` и идентификатором операции в качестве действия. `GET /me/operations` показывает, какими операциями владеет вызывающий, а `GET /admin/v1/security/operations/{id}/holders` - какие роли владеют операцией.

| Операция | Чувствительность | Эндпоинты | Что разрешает |
|---|---|---|---|
| `identity.partner.read` | `READ` | `GET /admin/v1/identity/partners`<br>`GET /admin/v1/identity/partners/{code}` | Просмотр зарегистрированных партнёров: код, название, статус, идентификаторы сертификатов и роли. |
| `identity.partner.onboard` | `PRIVILEGED` | `POST /admin/v1/identity/partners` | Подключить партнёра. |
| `identity.partner.replace` | `PRIVILEGED` | `PUT /admin/v1/identity/partners/{code}` | Заменить название, идентификаторы сертификатов и роли партнёра. |
| `identity.partner.suspend` | `PRIVILEGED` | `POST /admin/v1/identity/partners/{code}/suspend` | Приостановить партнёра, чтобы он больше не мог аутентифицироваться. |
| `identity.partner.activate` | `PRIVILEGED` | `POST /admin/v1/identity/partners/{code}/activate` | Возобновить приостановленного партнёра. |
| `identity.grant.read` | `READ` | `GET /admin/v1/identity/grants` | Просмотр выдач областей данных субъектам. |
| `identity.grant.create` | `PRIVILEGED` | `POST /admin/v1/identity/grants` | Выдать субъекту область данных, при необходимости до срока. |
| `identity.grant.revoke` | `PRIVILEGED` | `POST /admin/v1/identity/grants/{id}/revoke` | Отозвать выдачу области данных субъекту, сохранив её историю. |
| `identity.authority.read` | `READ` | `GET /admin/v1/identity/subjects/{subject}/authorities` | Просмотр того, на что субъект имеет право сейчас: роли, атрибуты и действующие выдачи. |
| `identity.source.read` | `READ` | `GET /admin/v1/identity/sources` | Просмотр источников идентичности: их каналов, количества пользователей по статусам и собственного состояния каждого канала, например водяной отметки или последней синхронизации. |

`PUT .../partners/{code}` требует `If-Match` и отвечает `412` на устаревшую версию. Вызывающий типа `USER` ограничен субъектами своего арендатора (субъект другого арендатора - `404`); реестр партнёров арендатором не ограничен.

Блок ролей (имена ролей - собственные роли сервиса; `ALL` - область):

```yaml
ludwig:
  security:
    roles:
      ORDERS_SUPPORT:
        operations:
          identity.partner.read: ALL
          identity.grant.read: ALL
          identity.authority.read: ALL
      ORDERS_ADMIN:
        includes: [ORDERS_SUPPORT]
        operations:
          identity.partner.onboard: ALL
          identity.partner.replace: ALL
          identity.partner.suspend: ALL
          identity.partner.activate: ALL
          identity.grant.create: ALL
          identity.grant.revoke: ALL
```

Блок ролей (имена ролей - собственные роли сервиса; `ALL` - область):

```yaml
ludwig:
  security:
    roles:
      ORDERS_SUPPORT:
        operations:
          identity.partner.read: ALL
          identity.grant.read: ALL
          identity.authority.read: ALL
          identity.source.read: ALL
      ORDERS_ADMIN:
        includes: [ORDERS_SUPPORT]
        operations:
          identity.partner.onboard: ALL
          identity.partner.replace: ALL
          identity.partner.suspend: ALL
          identity.partner.activate: ALL
          identity.grant.create: ALL
          identity.grant.revoke: ALL
```

## Runbook

| Симптом | Вероятная причина | Что делать |
|---|---|---|
| У нового пользователя нет ролей | Изменение ещё не доставлено, или сопоставление ролей источника их отбрасывает | Проверьте канал (`GET /admin/v1/identity/sources`); `SELECT id, source_system, status, source_timestamp FROM security_user WHERE id = '<sub>';`; проверьте `role-mapping` и `unmapped-roles` |
| Роль, отозванная у провайдера, ещё работает | Изменение не доставлено, или в пределах TTL кэша прав | Проверьте канал; подождите ≤ TTL |
| Отозванная роль вернулась после повтора | Не должно происходить (защита порядка) | Сравните `source_timestamp` с `occurredAt` изменения; сообщите об ошибке |
| Учтено `foreign-source` | Два источника выдают один и тот же субъект | Исправьте конфигурацию провайдера; проекция никогда не объединяет субъекты |
| Обход прерван | Перечисление было пустым или усечённым, либо это реальное массовое увольнение | Проверьте провайдера; для реального увольнения поднимите `max-disable-ratio` на один запуск |
| Выдача не действует | Истекла (`expires_at`), не тот ресурс/действие или `grants-enabled=false` | `SELECT * FROM security_grant WHERE subject = '<sub>';` |
| Партнёр отклонён | Нет строки `security_partner`, приостановлен или сменился идентификатор сертификата | Обновите строку (статус, идентификаторы) |

## Тестирование

`IdentityProjectionServiceTest` (применение, порядок, владение, удаление как статус, очистка контактов),
`RoleMappingTest`, `IdentitySourceValidationTest`, `RemovedIdentityKeysTest`, `IdentityMetricsTest`, а против
PostgreSQL - `IdentitySweepIntegrationTest` (отключение множеством, порог, изоляция источников, восстановление) и
`IdentityAdminIntegrationTest` (область `identity`, список источников).

## Проектные решения

- **Проекция, а не удалённый вызов или claims токена**: локальные соединения, никакой доступности третьей стороны на
  горячем пути, отзыв за секунды.
- **Один путь записи, много каналов**: порядок, устаревание, владение, сопоставление ролей, политика контактов и
  вытеснение живут в `IdentityProjection`, поэтому каждый провайдер получает их одинаково, а новый провайдер - это
  канал, а не форк.
- **Полные наборы ролей и упорядочивание по `occurredAt`**: повторы сходятся и никогда не выдают права заново.
- **Обходы исправляют, порог защищает**: у одного потока событий нет пути исправления; одно перечисление может
  ошибаться.
- **Субъект принадлежит одному источнику**: молчаливое объединение субъектов двух провайдеров позволило бы одному
  человеку получить роли другого.
- **По умолчанию хранится только то, что нужно авторизации**; контакты - по выбору.
- **Роли сервисов в конфигурации**, проверяются вместе с кодом.
