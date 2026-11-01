# Руководство администратора

**[English](admin-guide.md)** · **Русский**

Для тех, кто развёртывает и эксплуатирует сервисы, построенные на этой платформе. В README каждого модуля есть разделы **Конфигурация**, **Развёртывание**, **Наблюдаемость** и **Runbook**; это руководство описывает платформу в целом и служит указателем на документацию отдельных модулей.

## 1. Из чего состоит развёртывание

| Компонент | Кому необходим | Примечания |
|---|---|---|
| PostgreSQL | каждому сервису | отдельная база данных для каждого сервиса; Liquibase выполняет миграции при запуске |
| Kafka | сервисам с включённым обменом сообщениями | один или несколько именованных кластеров (`ludwig.kafka.clusters.<name>`); [messaging-db](../sources/messaging-db-spring-boot-starter/README.md) используется вместо кластера, которого пока нет |
| Провайдер OIDC | каждому сервису с `security-spring-boot-starter` | `OIDC_ISSUER_URI`; поток пользователей (`corp.identity.users`) передаёт данные в проекцию идентификации через `identity-source-kafka`, а провайдер с API пользователей - через `identity-source-directory` |
| Пограничный прокси (nginx/Envoy) | сервисам, работающим за ним | обменивает cookie сессии на JWT; передаёт клиентские сертификаты mTLS |
| SMTP-сервер | notification-service | |
| S3-совместимое хранилище | object-storage, file-ingest, export (если настроено) | учётные данные — только через цепочку провайдеров AWS SDK |
| Vault | необязательно | секреты в виде файлов через Vault Agent Injector (предпочтительно) или `ludwig.hotreload.vault.*` |
| Коллектор OTLP | трассировке | `MANAGEMENT_OTLP_TRACING_ENDPOINT` |
| Prometheus | метрикам | собирает данные с `/actuator/prometheus` |
| Redis | необязательно | только общий уровень кэширования для стартера кэша |

## 2. Развёртывание сервиса

1. **Образ.** Собирается с помощью Jib (`ludwig-service-parent`) или `Dockerfile` сервиса. Развёртывайте по **digest**, а не только по тегу: каждая ссылка на образ в этом репозитории имеет вид `name:tag@sha256:…`, иначе `scripts/check_image_pins.sh` завершает сборку с ошибкой. Digest релиза указывается в манифесте (в манифесте notification-service для этого есть заполнитель `REPLACE_WITH_RELEASE_DIGEST`).
2. **Манифест.** Возьмите за основу [манифест notification-service](../services/notification-service/deploy/k8s/notification-service.yaml):
    - liveness `/actuator/health/liveness` (только процесс), readiness `/actuator/health/readiness` (`db` плюс собственный индикатор очереди сервиса);
    - задайте лимит памяти, поскольку JVM рассчитывает размер кучи исходя из него и завершает работу при `OutOfMemoryError`;
    - настройте `PodDisruptionBudget`, который сохраняет достаточно реплик для заданий обслуживания с арендой блокировки;
    - секреты передавайте через `secretKeyRef`, никогда не помещайте их в ConfigMap;
    - используйте `/tmp` как `emptyDir` и файловую систему корневого раздела только для чтения.
3. **Раскатка.** Liquibase запускается при старте; каждый стартер проверяет конфигурацию при запуске и отказывается стартовать при небезопасном сочетании параметров. Поэтому некорректная конфигурация останавливает раскатку, а не приводит к неправильной работе. Следите за readiness, а не только за статусом pod.
4. **Откат.** Повторно разверните предыдущий digest образа. Миграции являются аддитивными, поэтому предыдущая версия работает с более новой схемой; если в модуле есть деструктивный changeset (например, удаление таблицы), это указано в разделе Deployment его README.
5. **Релизы** — это git-теги; см. [runbook.md §6](runbook.md#6-releasing). Развёртывание IFT пока не подключено к Jenkinsfile ([runbook.md §4.4](runbook.md#44-ift)).

## 3. Конфигурация и секреты

- Все свойства платформы находятся в `ludwig.<module>.*`; в README каждого модуля приведена полная таблица со значениями по умолчанию. Применяется гибкое связывание имён Spring: `ludwig.outbox.polling.enabled` соответствует `LUDWIG_OUTBOX_POLLING_ENABLED`.
- **Горячая перезагрузка** ([hot-reload](../sources/hot-reload-spring-boot-starter/README.md)): файлы из `ludwig.hotreload.files`, шаблоны FreeMarker и секреты Vault перезагружаются без перезапуска. Подключённые в Kubernetes ConfigMap и Secret становятся доступны pod в пределах периода синхронизации kubelet (примерно за минуту). Переменная окружения всегда имеет приоритет над перезагруженным значением.
- **Секреты:** учётные данные базы данных, клиентские секреты, ключи подписи и учётные данные SMTP должны поступать из хранилища секретов. gRPC-сервер отказывается запускаться без комплекта TLS; канал webhook — без секрета подписи.
- Свойства активации, которые включают функцию самим фактом своего наличия (например, `ludwig.hotreload.freemarker.template-directory`), задавайте как переменные окружения в манифесте, а не как пустые заполнители в `application.yml`.

## 4. База данных

Каждый стартер поставляет собственный changelog; сервис подключает его. Таблицы по модулям:

| Модуль | Таблицы | Хранение данных |
|---|---|---|
| outbox | `outbox_message`, `outbox_status_history` | **не ограничено: опубликованные строки никогда не удаляются** (§9) |
| job-core | `job_run_lock` | ограничено именами блокировок |
| idempotency | `idempotency_claim` | `ludwig.idempotency.ttl`, пакетная очистка |
| audit starter | `audit_event` | `ludwig.audit.retention.*`, по умолчанию `P7Y`, отдельно для каждой категории |
| identity-projection | `security_user`, `security_user_role`, `security_partner`, `security_partner_role`, `security_grant`, `identity_sweep_listed` | зеркально отражает данные провайдеров |
| identity-source-directory | `identity_directory_state` | водяная отметка и последние синхронизация и обход каждого каталога |
| messaging-db | `message_log`, `partition_head`, `consumer_offset` (схема `ludwig_messaging`) | `ludwig.messaging.db.retention`, по умолчанию 7 дней, независимо от того, обработано сообщение или нет |
| export | `export_report_run`, `export_report_output`, `export_saved_report`, `export_report_subscription` | срок хранения выходных данных зависит от приёмника |
| file-ingest | `file_ingest_run`, `file_ingest_quarantine` | **не ограничено** (§9) |
| file-action | `file_action_submission`, `file_action_row_reject` | для каждого действия, например `retention: P7D` |
| pat starter | `ludwig_pat` | очистка реализована, но не запланирована (§9) |
| reconciliation | `sync_*` (8 таблиц) | см. README соответствующего модуля |
| user-settings | `user_setting_value`, `user_consent`, `user_setting_audit` | согласия хранятся только с добавлением новых записей |
| notification-service | `notification_*` | семь временных окон; см. его README |

Блокировки, удерживаемые аварийно завершившимся pod, истекают автоматически (`ludwig.job-core.lock.default-lease`). Чтобы снять блокировку вручную, очистите владельца; **никогда не удаляйте строку**:

```sql
UPDATE job_run_lock SET owner = NULL, run_id = NULL, expires_at = now() WHERE lock_name = '<name>';
```

## 5. Kafka

| Топик | Производитель | Потребитель | Очередь недоставленных сообщений |
|---|---|---|---|
| `corp.identity.users` | провайдер OIDC | identity-source-kafka (в каждом сервисе, который подписан на него в `ludwig.identity.kafka.subscriptions.<source>`) | `corp.identity.users.dlt` |
| `catalog.product-events` | crud-service-example (outbox) | — | — |
| `platform.notifications.requests` | любой сервис | notification-service (по умолчанию выключен) | `platform.notifications.requests.dlt` |
| `platform.notifications.events` | notification-service (outbox) | — | — |

Авторитетный список для каждого сервиса содержится в его документе AsyncAPI в [docs/api/](api/README.md).

- Каждый потребитель, построенный на messaging starter, после исчерпания бюджета повторных попыток отправляет сообщение в `<topic>.dlt`; запись, которую не удалось десериализовать, попадает туда при первой попытке.
- Задержка потребителей и отсутствие сообщений (`ludwig.messaging.silent`) публикуются отдельно для каждого кластера и топика.
- **Повторная обработка сообщения из очереди недоставленных сообщений:** устраните причину, затем повторно опубликуйте запись DLT в исходный топик вместе с заголовками (заголовки конверта `ludwig-*` содержат идентификатор события, поэтому потребители с дедупликацией отбрасывают уже обработанные сообщения). См. [runbook messaging](../sources/messaging-spring-boot-starter/README.md#runbook).
- Несколько кластеров объявляются один раз в `ludwig.kafka.clusters`; маршруты outbox и слушатели указывают имя кластера.

## 6. Наблюдаемость

| Сигнал | Где доступен | Настраивается через |
|---|---|---|
| Логи | stdout, структурированный JSON (ECS / OTel / flat) вне профиля `local`; содержат сервис, версию, commit, trace, span и correlation ID | `ludwig.observability.logging.*` |
| Трассировки | OTLP | `MANAGEMENT_OTLP_TRACING_ENDPOINT`, `ludwig.observability.tracing.*` |
| Метрики | `/actuator/prometheus` | Micrometer; `ludwig.observability.metrics.*` |
| Идентификаторы сборки | `/actuator/info`, `/server/info`, каждая строка лога | упаковываются `ludwig-service-parent` |
| Уровни логирования | изменяются во время работы через горячую перезагрузку | |

**Имена метрик в Prometheus:** точки заменяются подчёркиваниями, к счётчикам добавляется суффикс `_total`, а таймеры предоставляют `_seconds_count`, `_seconds_sum` и `_seconds_bucket`. `ludwig.outbox.deadlettered` преобразуется в `ludwig_outbox_deadlettered_total`.

**Оповещения.** В README каждого модуля есть таблица «Recommended alerts» с готовыми выражениями PromQL. Начните со следующих:

| Оповещение | Модуль |
|---|---|
| `increase(ludwig_outbox_deadlettered_total[15m]) > 0` | [outbox](../sources/outbox-spring-boot-starter/README.md#observability) |
| рост задержки messaging или установленный `ludwig_messaging_silent` | [messaging](../sources/messaging-spring-boot-starter/README.md#observability) |
| `increase(ludwig_job_lock_lost_total[15m]) > 0` | [job-core](../sources/job-core/README.md#observability) |
| самая старая ожидающая отправки запись старше порога | [notification-service](../services/notification-service/README.md#metrics-and-the-two-that-matter) |
| `ludwig.ingest.missing` (ожидаемый файл не поступил) | [file-ingest](../sources/file-ingest-spring-boot-starter/README.md#observability) |
| задержка актуальности данных выше SLO | [reconciliation](../sources/reconciliation-spring-boot-starter/README.md#observability) |
| разомкнутый circuit breaker у именованного клиента | [rest-client](../sources/rest-client/README.md#observability), [grpc-client](../sources/grpc-client/README.md#observability) |
| доля HTTP 5xx | `http_server_requests_seconds_count{status=~"5.."}` в Spring Boot |

## 7. Аудит

- Каждый модуль записывает события через единый `AuditSink` ([audit-core](../sources/audit-core/README.md)). Без `audit-spring-boot-starter` события поступают в SLF4J sink (логгер `ru.ludwigandreas.audit`) и хранятся столько же, сколько логи.
- Со [стартером аудита](../sources/audit-spring-boot-starter/README.md) события записываются в `audit_event` в транзакции вызывающего кода, при необходимости отправляются в SIEM через outbox и удаляются согласно сроку хранения для категории (`ludwig.audit.retention.default-period`, по умолчанию семь лет; для отдельных категорий можно задать `ludwig.audit.retention.by-category.<category>`).
- Значения атрибутов, классифицированных как чувствительные, заменяются на `***REDACTED***` до передачи в любой sink.
- В таблице категорий audit-core указано, какой модуль отправляет каждую категорию и действие.

## 8. Actuator, операционные и административные эндпоинты

| Эндпоинт | Предоставляется | Доступ |
|---|---|---|
| `/actuator/health/liveness`, `/actuator/health/readiness` | observability starter + Boot | публичный (пробы) |
| `/actuator/info`, `/server/info` | observability starter | публичный |
| `/actuator/prometheus`, `/actuator/metrics` | Micrometer | только внутренняя сеть |
| `/admin/v1/**` | стартер безопасности + каждый модуль | аутентифицированные пользователи и сервисы, владеющие операцией; см. ниже |
| `/me/operations` | стартер безопасности | любой аутентифицированный вызывающий: операции, которыми он владеет |

**Административная поверхность.** Все административные эндпоинты находятся под `/admin/v1/`, и каждый защищён
одной операцией с чувствительностью `READ`, `CHANGE` или `PRIVILEGED`. Какие роли сервиса ею владеют, решает
собственный `ludwig.security.roles` сервиса (ниже) - значения по умолчанию нет, поэтому операция, которую не
перечисляет ни одна роль, запрещена всем, а строка журнала старта "Operations no role of this service holds"
перечисляет такие операции. Административные микросервисы получают роли из `ludwig.identity.service-roles`. Партнёры
отклоняются; вызову `PRIVILEGED` нужен заголовок `Operation-Reason` (метаданные gRPC `operation-reason`), и он не
принимает персональные токены доступа, если не задан
`ludwig.security.operations.allow-personal-access-tokens=true`; каждое изменение аудируется в категории `admin` с
идентификатором операции в качестве действия. `GET /me/operations` сообщает UI, какими операциями владеет
вызывающий и с какой областью; `GET /admin/v1/security/operations/{id}/holders` (`security.operation.read`)
перечисляет роли, владеющие операцией. Подробности:
[security](../sources/security-spring-boot-starter/README.ru.md#административная-поверхность). Операторских
эндпоинтов actuator у модулей больше нет: то, что делали `/actuator/fileingest` и `/actuator/reconciliation`, - в
областях `fileingest` и `reconciliation` ниже.

| Путь | Модуль | `READ` | `CHANGE` | `PRIVILEGED` |
|---|---|---|---|---|
| `/admin/v1/jobs` | job-core | `job.definition.read`, `job.lease.read` | `job.definition.run` | `job.lease.release` |
| `/admin/v1/replicas` | job-core | `replica.instance.read`, `replica.command.read` | - | - |
| `/admin/v1/caches` | job-core + cache | `cache.definition.read` | `cache.performance.evict` | `cache.security.evict` |
| `/admin/v1/hotreload` | hot-reload + job-core | `hotreload.source.read` | `hotreload.source.reload` | - |
| `/admin/v1/restclient` | rest-client + job-core | `restclient.breaker.read`, `restclient.client.read` | `restclient.token.reset` | `restclient.breaker.set` |
| `/admin/v1/outbox` | outbox | `outbox.message.read` | `outbox.message.retry` | `outbox.message.discard` |
| `/admin/v1/messaging` | messaging-db | `messaging.consumer.read` | `messaging.consumer.pause`, `messaging.consumer.resume` | `messaging.offset.move` |
| `/admin/v1/reconciliation` | reconciliation | `reconciliation.task.read` | `reconciliation.run.start`, `reconciliation.backfill.start`, `reconciliation.cursor.reset`, `reconciliation.scope.reset`, `reconciliation.quarantine.requeue`, `reconciliation.member.requeue`, `reconciliation.remotejob.requeue`, `reconciliation.discovery.start`, `reconciliation.walk.start`, `reconciliation.walk.preview`, `reconciliation.sweep.discard` | `reconciliation.remotejob.cancel`, `reconciliation.lease.release`, `reconciliation.sweep.confirm` |
| `/admin/v1/fileingest` | file-ingest | `fileingest.task.read` | `fileingest.run.start` | - |
| `/admin/v1/audit` | хранилище аудита | `audit.event.read` | - | - |
| `/admin/v1/identity` | проекция идентичности | `identity.partner.read`, `identity.grant.read`, `identity.authority.read`, `identity.source.read` | - | `identity.partner.onboard`, `identity.partner.replace`, `identity.partner.suspend`, `identity.partner.activate`, `identity.grant.create`, `identity.grant.revoke` |
| `/admin/v1/exports` | export | `export.run.read`, `export.subscription.read` | `export.run.cancel` | - |
| `/admin/v1/fileactions` | file-action | `fileaction.submission.read` | `fileaction.submission.cancel` | - |
| `/admin/v1/settings` | user-settings | `settings.subject.read`, `settings.consent.read` | `settings.scope.set`, `settings.backfill.start` | - |
| `/admin/v1/pat` | pat | `pat.token.read` | - | `pat.token.issue`, `pat.token.revoke` |
| `/admin/v1/security` | security | `security.operation.read` | - | - |

Полная таблица операций, эндпоинтов и описаний - в разделе «Администрирование» README каждого модуля.

### Роли и операции

Код называет операции, никогда не роли: роль - только элемент конфигурации. Каждой операцией сервиса - его
собственными бизнес-операциями и всеми административными операциями используемых модулей - владеют через одну
таблицу, `ludwig.security.roles.<ROLE>`, с `operations` (идентификатор операции и область) и, при необходимости,
`includes` (роли, операциями которых она тоже владеет). Область - `ALL` или измерения через `+`, каждое из которых
должно допускать строку: `OWN`, `TENANT`, `PARTNER`, `OBJECT` или добавленное модулем. Области ролей объединяются,
поэтому роль может только расширить выданное другой.

```yaml
ludwig:
  security:
    roles:
      NOTIFICATION_SUPPORT:
        operations:
          notification.delivery.read: TENANT     # a business operation: only the caller's tenant's rows
          notification.delivery.cancel: TENANT
          outbox.message.read: ALL               # an administrative one: an operation that touches no rows is ALL
          job.definition.read: ALL
          audit.event.read: ALL
      NOTIFICATION_ADMIN:
        includes: [NOTIFICATION_SUPPORT]         # every operation of the support role, then widened below
        operations:
          notification.delivery.read: ALL
          notification.delivery.retry: ALL
          outbox.message.retry: ALL
          outbox.message.discard: ALL
          job.definition.run: ALL
          job.lease.release: ALL
      SUPPLY_OPS:
        operations:
          reconciliation.task.read: OBJECT       # only the tasks granted to each person, see below
          reconciliation.run.start: OBJECT
      NOTIFICATION_SENDER:
        operations:
          notification.request.read: OWN         # a peer service reads back only what it submitted
```

Старт падает на записи с операцией, которой нет в каталоге сервиса (опечатка или область, которой у сервиса нет), на
измерении, которое ресурс операции не связывает, на измерении, отличном от `ALL` или `OBJECT`, у операции, не
затрагивающей строк, на шаблоне, `NONE`, а также на цикле `includes` или неопределённой роли. Имя роли, которого нет
в таблице, ошибкой не является: роли приходят от провайдера идентичности. Проверяются роли резолвера полномочий
(проекция идентичности, `ludwig.identity.service-roles`), никогда не claim токена.

**Администрирование через UI: claim `act`.** Когда люди администрируют сервисы через административный UI,
настройте провайдер идентичности так, чтобы вызовы UI выпускались обменом токенов по RFC 8693 и токен содержал
`act: {sub: admin-console}` (client id UI). Каждое событие аудита такого вызова записывает человека как действующее
лицо и `admin-console` как `via`, а журнал можно искать запросом `GET /admin/v1/audit/events?$filter=actorVia eq 'admin-console'`. Если
провайдер называет claim иначе, задайте `ludwig.security.jwt.actor-claim`. Токен, в котором claim есть, но не является
объектом с `sub`, отклоняется с `401`: если после обновления запросы начали падать и растёт
`ludwig_security_authentication_total{outcome="failure",reason="malformed-actor-claim"}`, проверьте это первым и задайте пустой `actor-claim`, если провайдер использует `act` для
другого. Чтобы люди приходили только через UI, задайте `ludwig.admin.human-callers.required-via: [admin-console]`;
прямой вызов человека тогда отклоняется с `403`, и отказ аудируется. Сервисные принципалы не затрагиваются никогда,
поэтому держите break-glass сервисный принципал с нужными операциями на случай недоступности UI и очистите список,
если людям нужно действовать напрямую.

**Фильтрация административных списков.** Журнал аудита, сообщения outbox, запуски сверки, запуски экспорта, отправки
file-action и PAT фильтруются только через OData `$filter`, например
`GET /admin/v1/outbox/messages?$filter=status eq 'DEAD_LETTER' and eventType eq 'OrderCreated'`, и листаются через
`cursor`/`size`. `$orderby`, `$top`, `$skip` и `$count` отклоняются, как и поле вне списка разрешённых для данного
списка (свободный текст, секреты и персональные данные в него не входят никогда). Фильтр никогда не расширяет видимое
вызывающему: ограничение по арендатору, 30 дней по умолчанию для аудита и задача из пути применяются под ним. README
каждого модуля перечисляет его фильтруемые поля. Прежние параметры `?status=`, `?owner=` и подобные удалены и при
передаче игнорируются.

**Согласование «четырёх глаз».** Чтобы привилегированные действия требовали второго администратора, перечислите их
операции, например `ludwig.admin.approval.required: ["outbox.message.discard", "identity.grant.create"]` (допускаются только
административные операции `PRIVILEGED`); сервису нужен
job-core, в таблице которого хранятся согласования. Вызов из списка отвечает `202` и ждёт в
`GET /admin/v1/approvals`, пока другой обладатель операции его одобрит (`POST /admin/v1/approvals/{id}/approve`),
отклонит или оно истечёт через `ludwig.admin.approval.ttl` (24 часа). Одобрение выполняет сохранённый запрос от имени
одобряющего. **Break-glass:** операция, не включённая в список, - путь для срочного действия, которое должен уметь
выполнить один человек; не включайте операцию, нужную во время инцидента (например, принудительное освобождение
аренды), или держите второго администратора на связи - заголовка для обхода нет.

**Ограничение по объектам.** Команду можно ограничить её объектами - пока это задачи сверки - ролью с областью
`OBJECT` на операциях области (`SUPPLY_OPS` выше) и выдачами субъектам по измерению `object` через
`POST /admin/v1/identity/grants`. Чтение вне области отвечает `404`, как для отсутствующего объекта; прочие вызовы -
`403`. Роль с областью `ALL` распространяется на все объекты. Настройка и пример:
[настройка областей данных](data-scope-configuration.ru.md#административные-объекты).

**Переход на этот выпуск.** В каждом сервисе: заменить `ludwig.security.data.policies` на
`ludwig.security.roles`, объявив каждую бизнес-операцию, которую покрывали политики, и выдать там каждую нужную
сервису административную операцию (строка журнала старта "Operations no role of this service holds" перечисляет, что
осталось). Приложение не стартует, пока задан `ludwig.security.data.policies`, `ludwig.security.data.default-access`,
`ludwig.security.data.strict-policy-tokens`, `ludwig.admin.grants` или `ludwig.admin.allow-personal-access-tokens`, и
называет ключ. Административным микросервисам добавить `ludwig.identity.service-roles`; убрать `reconciliation` и
`fileingest` из `management.endpoints.web.exposure.include`; удалить `ludwig.reconciliation.endpoint.*`,
`ludwig.ingest.endpoint.*`, `ludwig.user-settings.access.admin-authority` и
`ludwig.user-settings.web.admin-base-path`; перенести обладателей `pat:manage:any` и `ROLE_EXPORT_ADMIN` на роли с
`pat.token.*` и `export.run.*`; направить административные UI на `/admin/v1/` (`consents/as-of` настроек теперь
`consents/asof`), обнаружение операций - на `/me/operations`, а заголовок причины - на `Operation-Reason`. Изменения
схемы - два аддитивных changeset, `audit_event.actor_tenant` и `audit_event.actor_via`; откат выпуска - предыдущая
мажорная версия.

**Источники идентичности.** Проекция идентичности больше не читает Kafka сама. В каждом сервисе: добавить
`identity-source-kafka-spring-boot-starter`, объявить источник (`ludwig.identity.sources.oidc: {}` - `oidc` - это
`source_system` существующих строк), перенести `ludwig.identity.kafka.topic` в
`ludwig.identity.kafka.subscriptions.oidc.topic` (старый ключ роняет старт с указанием нового) и оставить
`ludwig.identity.kafka.group-id`, теперь обязательный. Провайдер, который отдаёт пользователей через API, наполняет
проекцию через `identity-source-directory-spring-boot-starter` и один бин `IdentityDirectory`;
`GET /admin/v1/identity/sources` (`identity.source.read`) показывает все источники, их каналы и состояние. Полное
перечисление отключает пользователей, которых источник больше не содержит, и прерывается без изменений при
превышении `ludwig.identity.sweep.max-disable-ratio` (10%).

`ludwig.security.public-paths` заменяет список путей по умолчанию, а не дополняет его. Добавляя путь, повторно укажите эндпоинты actuator, иначе для проб потребуется аутентификация.

## 9. Известные эксплуатационные пробелы

Проверено по коду; каждый пункт также указан в README соответствующего модуля в разделе «Known gaps». Пока проблемы не устранены, планируйте мониторинг и регламентное обслуживание с их учётом.

| Модуль | Пробел | Эксплуатационное последствие |
|---|---|---|
| job-core | нет метрик по отдельным заданиям (запуски, сбои, длительность, последний успех) | остановившийся обработчик в любом модуле незаметен; вместо этого настройте оповещения по собственной очереди модуля |
| outbox | нет метрики размера очереди и индикатора состояния; строки `PUBLISHED` никогда не удаляются | таблица растёт без ограничений; организуйте собственную очистку |
| file-action | метрик нет; коды ошибок используют `file-action.` вместо `ludwig.file-action.error.` | импорт можно отследить только по логам |
| object-storage | нет метрик и проверки состояния вызовов S3 | сбои хранилища проявляются только как ошибки вызывающих компонентов |
| identity-projection | изменения грантов и партнёров, сделанные в SQL, не аудируются | расхождения в данных авторизации остаются незаметными |
| messaging-db | нет метрик скорости записи, размера таблиц и индикатора состояния | отслеживайте размер таблиц на уровне базы данных |
| audit-core | нет метрики сбоев sink (только `WARN`) | настройте оповещение по записи в лог |
| audit starter | нет метрики самой старой неотправленной записи | задержка отправки в SIEM остаётся незаметной |
| idempotency | нет метрики количества заявок `IN_PROGRESS` | зависшие заявки обнаруживаются только запросом к БД |
| security, grpc-server | 401 / `UNAUTHENTICATED` не попадают в аудит | неудачная аутентификация видна только в логах и метриках |
| pat starter | `destroyExpiredSecrets`, `endElapsedRotationOverlaps`, `revokeInactive` и `purge` не запускаются по расписанию; нет метрик состояния токенов | таблица `ludwig_pat` растёт; истечение срока всё равно проверяется при верификации |
| export | нет метрики количества запусков `PENDING` и метрик подписок | |
| file-ingest | нет срока хранения запусков и карантина; карантин содержит исходные записи (возможно, персональные данные) | очищайте вручную согласно политике работы с данными |
| grpc-server | нет метрики глубины очереди executor | |
| crud-service-example | нет группы readiness (проверка готовности игнорирует базу данных) | скопируйте группу из notification-service |
| вся платформа | только notification-service предоставляет `HealthIndicator` | readiness в основном отражает только состояние `db` |

Мёртвая конфигурация (принимается, но ничего не делает): `odata.filter.web.problem-detail-advice-enabled`, `ludwig.reconciliation.tasks.<name>.audit.persist` (и таблица `sync_audit_record`), `ludwig.user-settings.retention.audit`. Мёртвая метрика: `ludwig.restclient.audit.failures` никогда не увеличивается.

## 10. Runbook для инцидентов

В README каждого модуля в конце эксплуатационной части есть таблица **Runbook** (симптом → вероятная причина → действие). Чаще всего нужны следующие:

| Ситуация | Runbook |
|---|---|
| События не доставляются, растёт очередь недоставленных сообщений | [outbox](../sources/outbox-spring-boot-starter/README.md#runbook) |
| Потребитель завис, растёт задержка или DLT | [messaging](../sources/messaging-spring-boot-starter/README.md#runbook) |
| Плановое задание не запускалось, блокировка удерживается | [job-core](../sources/job-core/README.md#runbook) |
| Массовые 401/403, токен отклоняется | [security](../sources/security-spring-boot-starter/README.md#runbook) |
| Данные пользователей или ролей устарели, обход прерван | [identity-projection](../sources/identity-projection-spring-boot-starter/README.ru.md#runbook), затем канал источника: [Kafka](../sources/identity-source-kafka-spring-boot-starter/README.ru.md#runbook) или [каталог](../sources/identity-source-directory-spring-boot-starter/README.ru.md#runbook) |
| Очередь уведомлений растёт, сообщения отправляются повторно | [notification-service](../services/notification-service/README.md#operational-runbook) |
| Файл не поступил или обработка файлов остановилась | [file-ingest](../sources/file-ingest-spring-boot-starter/README.md#runbook) |
| Вызовы партнёров завершаются ошибками, circuit breaker разомкнут | [rest-client](../sources/rest-client/README.md#runbook) |
| Изменение конфигурации не применилось | [hot-reload](../sources/hot-reload-spring-boot-starter/README.md#runbook) |
| Отчёт завис или завершился с ошибкой | [export](../sources/export-spring-boot-starter/README.md#runbook) |
