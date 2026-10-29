# Руководство администратора

**[English](admin-guide.md)** · **Русский**

Для тех, кто развёртывает и эксплуатирует сервисы, построенные на этой платформе. В README каждого модуля есть разделы **Конфигурация**, **Развёртывание**, **Наблюдаемость** и **Runbook**; это руководство описывает платформу в целом и служит указателем на документацию отдельных модулей.

## 1. Из чего состоит развёртывание

| Компонент | Кому необходим | Примечания |
|---|---|---|
| PostgreSQL | каждому сервису | отдельная база данных для каждого сервиса; Liquibase выполняет миграции при запуске |
| Kafka | сервисам с включённым обменом сообщениями | один или несколько именованных кластеров (`ludwig.kafka.clusters.<name>`); [messaging-db](../sources/messaging-db-spring-boot-starter/README.md) используется вместо кластера, которого пока нет |
| Провайдер OIDC | каждому сервису с `security-spring-boot-starter` | `OIDC_ISSUER_URI`; поток пользователей (`corp.identity.users`) передаёт данные в проекцию идентификации |
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
| identity-projection | `security_user`, `security_user_role`, `security_partner`, `security_partner_role`, `security_grant` | зеркально отражает данные провайдера |
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
| `corp.identity.users` | провайдер OIDC | identity-projection (в каждом сервисе, где он включён) | `corp.identity.users.dlt` |
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
| `/admin/v1/**` | стартер безопасности + каждый модуль | аутентифицированные пользователи и сервисы с выданной операцией; см. ниже |

**Административная поверхность.** Все административные эндпоинты находятся под `/admin/v1/`. Модули объявляют
операции `<area>.<tier>`; каждый сервис выдаёт их своим ролям в `ludwig.admin.grants` - выдачи по умолчанию нет,
поэтому обновлённый сервис отклоняет все административные вызовы, пока блока нет, а журнал старта перечисляет
невыданные операции. Административные микросервисы получают роли из `ludwig.identity.service-roles`. Партнёры и
персональные токены доступа отклоняются; вызову `privileged` нужен заголовок `Admin-Reason`; каждое изменение
аудируется в категории `admin`. `GET /admin/v1/me/operations` сообщает UI, что есть у вызывающего. Подробности:
[security](../sources/security-spring-boot-starter/README.ru.md#административная-поверхность). Операторских эндпоинтов actuator у
модулей больше нет: то, что делали `/actuator/fileingest` и `/actuator/reconciliation`, - в областях `fileingest` и
`reconciliation` ниже.

| Область | Модуль | Чтение | Управление | Привилегированное |
|---|---|---|---|---|
| `jobs` | job-core | задачи, аренды запусков | запустить задачу сейчас | принудительно освободить аренду |
| `replicas` | job-core | живые реплики, подтверждения команды по репликам | - (команды используют операции своих модулей) | - |
| `caches` | job-core + cache | кэши с назначением и TTL | очистить кэш производительности на всех репликах | очистить кэш безопасности на всех репликах |
| `outbox` | outbox | сообщения (без содержимого), сводка | повторить одно или многие | отбросить |
| `messaging` | messaging-db | отставание потребителей | - | - |
| `reconciliation` | reconciliation | задачи, области, карантин | запуски, backfill, возвраты в очередь, обходы | отмена задания у партнёра, освобождение аренды, подтверждение очистки |
| `fileingest` | file-ingest | задачи, запуски, контрольные точки | запустить прогон | - |
| `audit` | хранилище аудита | поиск по журналу (окно 30 дней) | - | - |
| `identity` | проекция идентичности | партнёры, выдачи, полномочия субъекта | - | изменения партнёров и выдач |
| `exports` | export | запуски, подписки (без результата) | отменить запуск | - |
| `fileactions` | file-action | отправки (без файла и строк) | отменить отправку | - |
| `settings` | user-settings | настройки и согласия субъекта | запись на уровне области, повторная публикация | - |
| `pat` | pat | метаданные токенов | - | выпуск для другого владельца, отзыв любого |

Полная таблица операций и эндпоинтов - в разделе «Администрирование» README каждого модуля. Сервис выдаёт только те
области, что у него есть: ключ с отсутствующей областью не даёт сервису стартовать:

```yaml
ludwig:
  admin:
    grants:
      "[jobs.read]": [NOTIFICATION_ADMIN, NOTIFICATION_SUPPORT]
      "[jobs.operate]": [NOTIFICATION_ADMIN]
      "[jobs.privileged]": [NOTIFICATION_ADMIN]
      # ... по строке на каждую операцию каждой имеющейся области
```

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
операции, например `ludwig.admin.approval.required: ["outbox.privileged", "identity.privileged"]`; сервису нужен
job-core, в таблице которого хранятся согласования. Вызов из списка отвечает `202` и ждёт в
`GET /admin/v1/approvals`, пока другой обладатель операции его одобрит (`POST /admin/v1/approvals/{id}/approve`),
отклонит или оно истечёт через `ludwig.admin.approval.ttl` (24 часа). Одобрение выполняет сохранённый запрос от имени
одобряющего. **Break-glass:** операция, не включённая в список, - путь для срочного действия, которое должен уметь
выполнить один человек; не включайте операцию, нужную во время инцидента (например, принудительное освобождение
аренды), или держите второго администратора на связи - заголовка для обхода нет.

**Ограничение по объектам.** Команду можно ограничить её объектами - пока это задачи сверки - политикой областей
данных для `admin.<area>` (роли `ALL` или `NONE`) и выдачами субъектам по измерению `object` через
`POST /admin/v1/identity/grants`. Чтение вне области отвечает `404`, как для отсутствующего объекта. Настройка и
пример: [настройка областей данных](data-scope-configuration.ru.md#административные-объекты).

**Переход на этот выпуск.** В каждом сервисе: добавить блок `ludwig.admin.grants` (строка журнала старта
"Administrative operations no role of this service holds" перечисляет, что осталось); административным микросервисам
добавить `ludwig.identity.service-roles`; убрать `reconciliation` и `fileingest` из
`management.endpoints.web.exposure.include`; удалить `ludwig.reconciliation.endpoint.*`, `ludwig.ingest.endpoint.*`,
`ludwig.user-settings.access.admin-authority` и `ludwig.user-settings.web.admin-base-path`; перенести обладателей
`pat:manage:any` и `ROLE_EXPORT_ADMIN` на выдачи `pat.*` и `exports.*`; направить административные UI на `/admin/v1/`
(`consents/as-of` настроек теперь `consents/asof`). Изменения схемы - два аддитивных changeset,
`audit_event.actor_tenant` и `audit_event.actor_via`; откат выпуска - предыдущая мажорная версия.

`ludwig.security.public-paths` заменяет список путей по умолчанию, а не дополняет его. Добавляя путь, повторно укажите эндпоинты actuator, иначе для проб потребуется аутентификация.

## 9. Известные эксплуатационные пробелы

Проверено по коду; каждый пункт также указан в README соответствующего модуля в разделе «Known gaps». Пока проблемы не устранены, планируйте мониторинг и регламентное обслуживание с их учётом.

| Модуль | Пробел | Эксплуатационное последствие |
|---|---|---|
| job-core | нет метрик по отдельным заданиям (запуски, сбои, длительность, последний успех) | остановившийся обработчик в любом модуле незаметен; вместо этого настройте оповещения по собственной очереди модуля |
| outbox | нет метрики размера очереди и индикатора состояния; строки `PUBLISHED` никогда не удаляются | таблица растёт без ограничений; организуйте собственную очистку |
| file-action | метрик нет; коды ошибок используют `file-action.` вместо `ludwig.file-action.error.` | импорт можно отследить только по логам |
| object-storage | нет метрик и проверки состояния вызовов S3 | сбои хранилища проявляются только как ошибки вызывающих компонентов |
| identity-projection | отброшенные события отражаются только в логах; нет метрик проекции; изменения грантов и партнёров не аудируются | расхождения в данных авторизации остаются незаметными |
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
| Данные пользователей или ролей устарели | [identity-projection](../sources/identity-projection-spring-boot-starter/README.md#runbook) |
| Очередь уведомлений растёт, сообщения отправляются повторно | [notification-service](../services/notification-service/README.md#operational-runbook) |
| Файл не поступил или обработка файлов остановилась | [file-ingest](../sources/file-ingest-spring-boot-starter/README.md#runbook) |
| Вызовы партнёров завершаются ошибками, circuit breaker разомкнут | [rest-client](../sources/rest-client/README.md#runbook) |
| Изменение конфигурации не применилось | [hot-reload](../sources/hot-reload-spring-boot-starter/README.md#runbook) |
| Отчёт завис или завершился с ошибкой | [export](../sources/export-spring-boot-starter/README.md#runbook) |
