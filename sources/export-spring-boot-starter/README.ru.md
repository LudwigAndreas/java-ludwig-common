# export-spring-boot-starter

*[English](README.md) · **Русский***

Движок табличных бизнес-отчётов платформы: определения объявляются типизированным кодом и проверяются при старте,
параметры и формат выбирает пользователь при запуске, данные берутся из БД сервиса-владельца потоком с keyset-
пагинацией, обогащаются данными других сервисов и пишутся в XLSX или CSV в приёмник. Рассчитан на **1 000 000 строк ×
25 колонок, 8 из них - обогащение из до 4 сервисов-партнёров**, в фиксированном бюджете кучи, переживает смерть
выполняющего экземпляра и никогда молча не выдаёт неверный или неполный файл.

| | |
|---|---|
| Роль | starter |
| Зависит от (в репозитории) | `audit-core`, `db-core`, `hot-reload`, `job-core`, `object-storage` (опционально), `observability`, `odata-filter`, `outbox`, `rest-client`, `security`, `web-core` |
| Используется | `crud-service-example` |
| Поверхности | REST `/api/v1/reports/**`; Kafka/REST через outbox (`report.ready`); исходящий REST (обогащение); файловый или S3-приёмник |

## Использование

```xml
<dependency>
    <groupId>ru.ludwigandreas</groupId>
    <artifactId>export-spring-boot-starter</artifactId>
</dependency>
```

### Объявление отчёта

```java
public static final ReportDefinition<OrderReportParameters, OrderRow> ORDERS = ReportDefinition
        .<OrderReportParameters, OrderRow>of("catalog.orders", OrderReportParameters.class, OrderRow.class)
        .titleKey("report.orders.title")
        .source(orderRowSource)
        .stage(CUSTOMER_STAGE)
        .column(ORDER_NUMBER)
        .column(CUSTOMER_NAME)
        .column(TOTAL)
        .allowedFormats(Set.of(StandardReportFormats.XLSX, StandardReportFormats.CSV))
        .defaultFormat(StandardReportFormats.XLSX)
        .maxRows(2_000_000)
        .build();
```

Добавьте его через бин `ReportDefinitionSource`. Пользователь выбирает определение (или сохранённую конфигурацию),
задаёт параметры, выбирает подмножество разрешённых его ролям колонок и формат и получает файл.

**Определения - это код, а не строки таблицы.** Извлекатели - ссылки на методы (переименование поля - ошибка
компиляции, а не пустая колонка), параметры - record-ы с Bean Validation, источник - запрос QueryDSL над
сгенерированными Q-типами. То, что меняется по пользователю (параметры, подмножество колонок, формат), - данные в
`SavedReport`, проверяемые при записи **и** при загрузке. Новый отчёт требует деплоя сервиса-владельца; взамен ни
один отчёт не переживёт схему, которую читает, и ни один пользовательский запрос не попадёт в БД.

`crud-service-example` публикует `catalog.products` (`ProductRowSource`, `CatalogReports`, `SupplierEnricher`):
keyset-источник с проекцией в record, колонка только для `ROLE_CATALOG_ADMIN` (отсутствует в файле для остальных),
пакетная стадия обогащения через `@LudwigRestClient` с локализованным маркером для неизвестных ключей и `DEGRADE` при
сбое, а также область данных и фильтрация `@Filterable`, переиспользованные из сущности. `ProductReportIntegrationTest`
проходит весь путь.

### Конвейер

```
ReportRequest (ключ определения, параметры, подмножество колонок, $filter, сортировка, локаль, пояс, формат, опции)
      |
      v
RowSource<R>            поток QueryDSL с keyset-пагинацией; применён предикат области данных;
      |                 ORDER BY полный и заканчивается первичным ключом (никогда OFFSET)
      v
window(N) ─────────────────────────────────────────────────────┐
      |                                                         │ ограниченная память:
      v                                                         │ строки окна + их обогащение
EnrichmentExecutor      по окну: различные ключи по стадии,     │ + один кэш стилей
  ├─ Batched            кэш на запуск, параллельные вызовы      │
  ├─ PerItem            партнёров под ограничением, соединение  │
  └─ Dimension          результатов обратно                     │
      |                                                         │
      v  RenderedRow (типизированные CellValue) ────────────────┘
ReportWriter (XLSX | CSV | SPI)  -> временный файл, потоково, ограниченное окно сброса
      |
      v
ReportSink -> обновление записи запуска -> ссылка на скачивание и/или событие report.ready в outbox
```

| Решение | Почему |
|---|---|
| Keyset-пагинация, никогда `OFFSET` | `OFFSET` читает и отбрасывает `n` строк на страницу и нестабилен при параллельной записи (строки молча пропадают). Порядок должен быть полным; источник дописывает первичный ключ |
| Ничего не материализуется целиком | Ленивый `Stream<R>`, закрываемый на любом выходе; JPA-источники очищают `EntityManager` между страницами |
| Типизированные ячейки (`CellValue`: Text, Number, Money, Date, DateTime, Bool, Empty, Error) | Текстовые ячейки сортируют даты по алфавиту и ломают `SUM` |
| Отсутствие ≠ сбой | `MissingPolicy` для неизвестных партнёру ключей; `FailurePolicy` для отказавшего партнёра |
| Деградация никогда не молчит | Маркер в ячейках, деградировавшие стадии на запуске и листе метаданных, `X-Report-Degraded`, счётчик |
| Скрытые колонки отсутствуют, а не пусты | Пустая колонка раскрывает факт наличия данных |
| Явное противодавление | Чтение/обогащение в потоке-продюсере, запись в потоке вызывающего; между ними не более `handoff-queue-depth` окон |
| Параллелизм - в обогащении | Разветвление стадии ограничено семафором над общим пулом |

### Обогащение

По окну и стадии: различные ключи собираются один раз, кэш запуска отвечает, что может, остальное идёт партнёру.
2 000 строк про 200 клиентов = **один вызов**. Кэш несёт нагрузку (примерно в 100 раз меньше вызовов в расчётной
точке) и кэширует **отсутствие**. Живёт один запуск (отчёт - утверждение на момент времени). Стадии без зависимостей
обращаются параллельно; слияния применяются последовательно. Деградировавшая стадия в этом запуске больше не
вызывается. `DimensionTooLargeException` (справочник больше `max-dimension-entries`) обходит политику отказа - это
ошибка конфигурации.

| Ситуация | Строка получает |
|---|---|
| Ключ найден | Слитое значение |
| У строки нет ключа | Ничего; решает `NullPolicy` колонки |
| Неизвестный ключ, `PLACEHOLDER` | Ячейки стадии несут локализованный маркер |
| Неизвестный ключ, `FAIL_ROW` | Строка отброшена и учтена |
| Неизвестный ключ, `FAIL_REPORT` | Запуск падает с именем стадии (никогда ключа) |
| Партнёр отказал, `FAIL_REPORT` | Запуск падает |
| Партнёр отказал, `DEGRADE` | Ячейки несут `unavailable`; стадия указана на запуске |

### Идентичность вызовов партнёров

| | `SERVICE_ACCOUNT` (по умолчанию) | `REQUESTER` |
|---|---|---|
| Партнёр видит | Этот сервис | Того, кто запросил |
| Ограничение строк применяет | **Отчёт** | Партнёр, поверх |
| Работает для отложенных запусков, подписок, перехваченных попыток | Да | **Нет** (токен не хранится) |
| `auth.type` REST-клиента | `oauth2-client-credentials` (или basic, api-key, bearer, mTLS) | `oauth2-token-relay` |

По умолчанию - `ludwig.export.enrichment.call-as`, переопределение по стадии:

```java
EnrichmentStage.<OrderRow, String, Customer>of("customer", customerEnricher)
        .keyExtractor(OrderRow::customerId)
        .merge(OrderRow::withCustomer)
        .restClient("customers")
        .callAs(CallIdentity.REQUESTER)
        .build();
```

Идентичность применяет `auth.type` именованного клиента; старт отвергает `REQUESTER` с клиентом без ретрансляции
(партнёр ответил бы видением этого сервиса) и `SERVICE_ACCOUNT` с клиентом-ретранслятором (отложенные запуски падали
бы). Запуск `REQUESTER`, который был бы отложен, отклоняется при запросе с `identity-unavailable` (409); отложенная
попытка терминально падает с тем же кодом. Отката на сервисную учётную запись нет никогда.

### Web API (`ludwig.export.web.base-path`, по умолчанию `/api/v1/reports`)

| Эндпоинт | Действие |
|---|---|
| `POST /{definitionKey}/runs` | Запросить отчёт: **200**, если выполнен в потоке запроса (терминальный конверт), **202** с `Location`, если поставлен в очередь |
| `GET /runs/{id}` | Статус и прогресс; `Retry-After`, пока не завершён |
| `GET /runs/{id}/outputs/{format}` | Скачивание; вызывающий проверяется заново; `X-Report-Sha256` и `X-Report-Degraded` |
| `DELETE /runs/{id}` | Кооперативная отмена; **202** |
| `GET /definitions` | Что может запускать *этот вызывающий*, с *его* колонками |

Запуск, запрошенный другим, отвечает **404**, а не 403. Ответы несут конверт `operation` web-core рядом с
собственными полями модуля (`status` и `operation.status` проецируются из одной строки). Состояния:
`PENDING RUNNING SUCCEEDED FAILED CANCELLED EXPIRED`. Отмена проверяется раз на окно.

### Сохранённые конфигурации и подписки

- **Сохранённый отчёт** предзаполняет параметры, сужает колонки, выбирает формат; добавить то, чего нет в
  определении, он не может. Проверяется при записи и загрузке; устаревший падает громко (и скрывается из списков).
- **Подписка** (cron, арендуемая блокировка) выполняется от имени `run_as` с правами, определёнными заново при
  выполнении. Пропущенное окно **пропускается, а не воспроизводится**.

### `report.ready`

При успехе, в той же транзакции, что и `SUCCEEDED`, запуск публикует `report.ready` (`ReportReadyEvent`) через
outbox - **ссылку, никогда вложение**. Объявлено бином `OutboxEventTypes`.

### Приёмники

| `sink.type` | Класс | Когда |
|---|---|---|
| `filesystem` (по умолчанию) | `FilesystemReportSink` | Один экземпляр со смонтированным томом |
| `s3` | `S3ReportSink` | Больше одной реплики (делегирует [object-storage](../object-storage-spring-boot-starter); добавьте его и AWS SDK) |
| имя бина | ваш `ReportSink` | |

Ключи: `<key-prefix>/<run-id-prefix>/<run-id>/<file-name>`. `RetryingReportSink` оборачивает выбранный приёмник;
попыток - `sink.store-attempts`. У удалённых выходов сохраняется строка (`purged_at`).

### Форматы

- **XLSX**: потоковый POI с кэшем стилей по внешнему виду (ограничен десятками стилей; тест пишет 12 000 ячеек и
  проверяет < 30 стилей), типизированные ячейки, денежные форматы, закреплённый заголовок и автофильтр, строка итогов,
  лист метаданных (происхождение), очищенные и дедуплицированные имена листов, листы-продолжения
  (`Orders continued (2)`, заголовок повторяется) на пределе 1 048 576 строк, опциональный шаблон (имя в определении,
  ищется в `template-directory`, затем в classpath, перечитывается каждый запуск).
- **CSV**: профиль `excel` (UTF-8 + BOM, разделитель списка локали, CRLF) или `rfc4180`; защита от формул; без строки
  итогов (объявлено в `FormatCapabilities`).

### Точки расширения

| Шов | Форма |
|---|---|
| `RowSource<P, R>` | `open`, `sortableColumns`, `estimateRows`; keyset, ленивый, закрывается движком |
| `Enricher<K, V>` | **sealed**: `Batched`, `PerItem`, `Dimension` |
| `CellValue` | **sealed**: восемь форм |
| `ReportFormat` / `ReportWriterFactory` / `ReportWriter` | **открыты** - новый формат это бин (PDF/ODS не поставляются) |
| `ReportSink` | store / open / delete |
| `XlsxTemplateSource` | имя → байты книги |
| `CellRenderer<V>` | значение + `RenderContext` → `CellValue` |
| `ExportMetrics` | привязка метрик (no-op - настоящий бин) |
| `MessageKeyValidator` | как реестр проверяет бандлы |

Моменты времени отображаются в поясе вызывающего (предпочтения web-core, переносятся в `RenderContext`); явный
`timeZone` в запросе побеждает.

## Конфигурация

Пик памяти ≈ `window-size` строк + их обогащённые значения + `handoff-queue-depth` × `window-size` строк + окно сброса
писателя + кэш обогащения. **Настраивайте вместе.**

| Свойство | По умолчанию | Смысл |
|---|---|---|
| `ludwig.export.enabled` | `true` | Главный выключатель |
| `ludwig.export.instance` | идентичность job-core | Пишется в `claimed_by` |
| `ludwig.export.source-page-size` | `1000` | Строк на keyset-страницу |
| `ludwig.export.window-size` | `2000` | Строк, обогащаемых и пишущихся одной единицей; граница памяти и задержка отмены |
| `ludwig.export.handoff-queue-depth` | `4` | Окон между обогащением и записью (противодавление) |
| `ludwig.export.sync-threshold-rows` | `5000` | Оценки не больше этого выполняются в потоке запроса; `0` откладывает всё |
| `ludwig.export.wall-clock-budget` | `30m` | Максимальное время запуска |
| `ludwig.export.max-rows-per-run` | `5000000` | Предел парка (меньший из этого и `maxRows` определения) |
| `ludwig.export.enrichment.batch-size` | `200` | Ключей на пакетный вызов |
| `ludwig.export.enrichment.concurrency` | `4` | Вызовов в работе на стадию на окно |
| `ludwig.export.enrichment.cache-enabled` / `cache-size` | `true` / `50000` | Кэш запуска |
| `ludwig.export.enrichment.max-dimension-entries` | `100000` | Предел справочника |
| `ludwig.export.enrichment.call-as` | `SERVICE_ACCOUNT` | Идентичность партнёра по умолчанию |
| `ludwig.export.enrichment.missing` / `failure` | `PLACEHOLDER` / `FAIL_REPORT` | Политики по умолчанию |
| `ludwig.export.temp.directory` | временный каталог JVM | Частичные файлы; **задавайте в контейнерах** |
| `ludwig.export.temp.orphan-age` / `sweep-on-startup` | `6h` / `true` | Очистка осиротевших файлов |
| `ludwig.export.formats.enabled` | все | Allow-list форматов парка |
| `ludwig.export.formats.xlsx.flush-window` | `500` | Строк в памяти на лист |
| `ludwig.export.formats.xlsx.template-directory` | нет | Шаблоны книг |
| `ludwig.export.formats.xlsx.freeze-header` / `metadata-sheet` | `true` / `true` | |
| `ludwig.export.formats.csv.profile` / `allow-request-override` | `excel` / `true` | |
| `ludwig.export.sink.type` | `filesystem` | `filesystem`, `s3` или имя бина |
| `ludwig.export.sink.directory` | `${java.io.tmpdir}/ludwig-export` | Корень файлового приёмника |
| `ludwig.export.sink.bucket` / `key-prefix` | нет | **Bucket обязателен для `s3`** |
| `ludwig.export.sink.retention` | `7d` | Окно скачивания |
| `ludwig.export.sink.purge-interval` | `1h` | |
| `ludwig.export.sink.store-attempts` | `3` | |
| `ludwig.export.sink.download-link-ttl` | `15m` | |
| `ludwig.export.poller.enabled` | `true` | Выполнять отложенные запуски на этом экземпляре |
| `ludwig.export.poller.interval` | `5s` | |
| `ludwig.export.poller.claim-batch-size` | `1` | Запусков за опрос |
| `ludwig.export.poller.concurrency` | `2` | Одновременных запусков на экземпляр (каждый держит свой бюджет окна) |
| `ludwig.export.poller.lease-duration` / `heartbeat-interval` | `5m` / `1m` | |
| `ludwig.export.poller.drain-timeout` | `30s` | |
| `ludwig.export.poller.max-attempts` | `3` | |
| `ludwig.export.poller.retry-initial-delay` / `retry-max-delay` | `30s` / `10m` | |
| `ludwig.export.quota.concurrent-runs-per-user` | `3` | |
| `ludwig.export.quota.daily-runs-per-user` | `100` | Скользящие сутки |
| `ludwig.export.liquibase.enabled` | `true` | |
| `ludwig.export.metrics.enabled` | `true` | |
| `ludwig.export.web.enabled` / `base-path` / `retry-after` | `true` / `/api/v1/reports` / `2s` | |

**Старт отвергает** (всё сразу):

| Реестр (определения) | Конфигурация (`ExportConfigurationValidator`) |
|---|---|
| Повторяющиеся ключи; колонку, чья `requiredStage` не объявлена; сортировку по умолчанию, которую источник не умеет; сортируемые/фильтруемые колонки, которых нет в определении; тип параметров, не являющийся record и без конструктора без аргументов; разрешённый формат без писателя; многолистовость с плоским форматом при `REJECT`; `maxRows` больше предела формата без переноса; ключ заголовка/названия/маркера, отсутствующий в `en` или `ru`; неверное право; стадию, зависящую от необъявленной стадии или от себя | `source-page-size` > `window-size`; `enrichment.batch-size` > `window-size`; `cache-size` < `batch-size`; `sync-threshold-rows` > `max-rows-per-run`; `lease-duration` < 2 × `heartbeat-interval`; `poller.interval` ≥ `lease-duration`; `drain-timeout` ≥ `lease-duration`; `download-link-ttl` > `retention`; `purge-interval` > `retention`; неизвестный `sink.type`; `s3` без `bucket`; непригодный `temp.directory`; `formats.enabled` с неизвестным форматом или отключающий все форматы определения; стадию с ненастроенным REST-клиентом или с параллелизмом больше его пула; несоответствия идентичности/аутентификации (см. выше) |

## Развёртывание

- **Схема** (`db/changelog/export/export-changelog.xml`, собственный бин `SpringLiquibase`): `export_report_run`
  (частичный индекс захвата `(next_attempt_at, id) WHERE status = 'PENDING'`, индекс перехвата
  `lease_until WHERE status = 'RUNNING'`), `export_report_output` (индекс очистки `expires_at WHERE purged_at IS NULL`),
  `export_saved_report`, `export_report_subscription`.
- **Временный каталог** на записываемом томе, рассчитанном на одновременные запуски × форматы.
- **Приёмник**: постоянный том (файловый, одна реплика) или бакет (`s3`, любое число реплик; добавьте object-storage и
  AWS SDK).
- **Партнёры**: `@LudwigRestClient` на каждую стадию обогащения с пулом ≥ параллелизма стадии.
- **Маршрут outbox** для `report.ready`, если он нужен потребителям.
- **Куча**: расчётная точка выполнялась в `-Xmx512m`; закладывайте больше на каждый одновременный запуск.

## Наблюдаемость

| Метрика | Тип | Теги | Смысл |
|---|---|---|---|
| `ludwig.export.runs` | timer | `definition`, `outcome` | Длительность запуска по исходу |
| `ludwig.export.rows.written` | counter | `definition` | Записанные строки |
| `ludwig.export.output.bytes` | counter | `definition`, `format` | Произведённые байты |
| `ludwig.export.enrichment.calls` | timer | `definition`, `stage`, `outcome` | Вызовы партнёров |
| `ludwig.export.enrichment.cache.hits` / `.misses` | counter | `definition`, `stage` | Эффективность кэша запуска |
| `ludwig.export.enrichment.keys.missing` | counter | `definition`, `stage` | Ключи, неизвестные партнёру |
| `ludwig.export.enrichment.degraded` | counter | `definition`, `stage` | Деградировавшие стадии |
| `ludwig.export.rows.dropped` | counter | `definition`, `stage` | Строки, отброшенные `FAIL_ROW` |
| `ludwig.export.retention.reclaimed.bytes` | counter | | Удалённые байты |

Теги ограничены (ключ определения, формат, стадия); параметры - никогда.

**Логи** (`INFO`): зарегистрированные определения, проверенная конфигурация, итог запуска (строки, отброшенные,
выходы, длительность), повторы, перенос на новый лист, перехваченные запуски, итоги очистки и уборки, отправка
подписок. `WARN`: потерянная аренда, деградировавшая стадия, повторы сохранения, упавшие запуски, устаревший
сохранённый отчёт, проблемы шаблонов, сбой объявления `report.ready`. `ERROR`: необработанная ошибка запуска, сбой
отправки подписки, отчёт с ресурсом без `DataScopeMapping`.

**Рекомендуемые алерты:**

| Условие | Почему |
|---|---|
| Устойчивое `rate(ludwig_export_runs_seconds_count{outcome="FAILED"}[15m]) > 0` | Запуски падают |
| `increase(ludwig_export_enrichment_degraded_total[15m]) > 0` | Партнёр недоступен; файлы неполные |
| p95 `ludwig_export_runs_seconds` близко к `wall-clock-budget` | Запуски скоро упрутся в таймаут |
| Лог `Lost the lease on report run` | Аренда коротка или экземпляр голодает |

**Известные пробелы:** нет gauge для поставленных в очередь (`PENDING`) запусков и их возраста и нет метрик отправки
подписок; остановившийся поллер виден только SQL-запросом из runbook.

## Аудит

Категория `export`; каждый переход жизненного цикла создаёт `ExportAuditEvent`:

| Поле | Значение |
|---|---|
| action | `run.<status>` (`run.pending`, `run.running`, `run.succeeded`, `run.failed`, `run.cancelled`, `run.expired`) |
| actor | запросивший (или `run_as` подписки), `system` для системных переходов |
| resource | `report-run` / id запуска / ключ определения |
| outcome | `SUCCESS`, `FAILURE` или `PARTIAL`, если стадия обогащения деградировала |
| attributes | определение, формат, число строк, деградировавшие стадии, код ошибки - колонки с ПДн отредактированы |

Отчёты - самое широкое массовое чтение в парке; след включён всегда. `settings` и `export` - категории, которые
outbox-приёмник аудит-стартера по умолчанию отправляет в SIEM.

## Runbook

**Запуски копятся в `PENDING`.** `SELECT status, count(*) FROM export_report_run GROUP BY status;` затем
`SELECT id, attempts, next_attempt_at, failure_code FROM export_report_run WHERE status = 'PENDING' ORDER BY next_attempt_at LIMIT 20;`

| Что видно | Смысл | Что делать |
|---|---|---|
| `attempts = 0`, `next_attempt_at` в прошлом, `RUNNING` нет | Никто не захватывает | Проверить `ludwig.export.poller.enabled` на экземплярах и их datasource |
| `attempts = 0`, часть `RUNNING`, очередь медленно уменьшается | Очередь длиннее пропускной способности | Добавить экземпляры (безопасно: `SKIP LOCKED`); поднимать `poller.concurrency` только после проверки кучи |
| `attempts` растёт, `failure_code` задан | Падают и повторяются | Прочитать код (= то, что видит вызывающий). Отменить запуски с ошибками конфигурации/параметров |
| `next_attempt_at` далеко впереди | Вырос backoff | Ожидаемо; потолок `retry-max-delay` |

Не удаляйте строки `PENDING`; отменяйте (`DELETE /api/v1/reports/runs/{id}`).

**Запуски зависли в `RUNNING`.** `SELECT id, claimed_by, heartbeat_at, lease_until, now() FROM export_report_run WHERE status = 'RUNNING';`

| Что видно | Смысл | Что делать |
|---|---|---|
| `lease_until` впереди, `heartbeat_at` свежий | Работает | Ничего (миллион строк - это минуты) |
| `lease_until` в прошлом | Владелец умер | Следующий опрос перехватит (`Reclaimed ... report run(s)` на `INFO`); если нет - поллер не работает |
| `heartbeat_at` устарел, аренда впереди | Heartbeat голодает | Thread dump; планировщик модуля перегружен |
| Работает намного дольше `wall-clock-budget` | Не должно быть возможно | Thread dump, затем отмена; сообщить о дефекте |

Повтор начинается с нуля (запуск идемпотентен по хэшу запроса).

**Партнёр деградировал.** Смотреть `ludwig_export_enrichment_degraded_total` и `degraded_stages` недавних запусков.

| Что видно | Смысл | Что делать |
|---|---|---|
| `degraded` растёт, запуски `SUCCEEDED` | Политика стадии `DEGRADE` | Проблема партнёра; остальная часть файла верна |
| Запуски `FAILED` с `enrichment-failed` | Политика стадии `FAIL_REPORT` | Проблема партнёра; не меняйте политику, чтобы заглушить алерт |
| `keys.missing` растёт без `degraded` | Партнёр здоров, ключи неизвестны | Вопрос данных выше по цепочке |
| Одна стадия занимает большую часть времени | Ограничение пулом клиента | Поднять `max-per-route` клиента партнёра **и** `concurrency` стадии вместе |

**Заполняется временный каталог.** `du -sh` для `ludwig.export.temp.directory`.

| Что видно | Смысл | Что делать |
|---|---|---|
| Свежие файлы `ludwig-export-*` | Запуски в работе | Ничего |
| Накапливаются файлы старше `orphan-age` | Уборка не работает или не может удалить | Проверить лог уборки при старте и владельца файлов (файлы `rw-------`) |
| Рост без запусков | Туда пишет другой процесс | Модуль создаёт только `ludwig-export-*` |

Растущий **приёмник** значит, что очистка по сроку не работает: проверьте её `RunLock` в `job_run_lock` и что
`purge-interval` < `retention`.

**Никто не может скачать готовый файл.** `SELECT expires_at, purged_at, sha256 FROM export_report_output WHERE run_id = '<id>';`

| Что видно | Смысл |
|---|---|
| `purged_at` задан | Срок хранения истёк: `output-expired` |
| `expires_at` в прошлом, `purged_at` пуст | Истёк, ещё не убран; отказ |
| 404 вместо 403 | Вызывающий не запросивший и не админ (намеренно) |
| `X-Report-Sha256` не совпадает с копией получателя | Файл заменён или повреждён при передаче |

Коды проблем (`ludwig.export.error.*`): `unknown-definition` 404, `unknown-format` / `format-not-allowed` /
`format-disabled` / `unknown-column` / `unsortable-column` / `parameter-invalid` / `parameters-invalid` /
`not-filterable` / `invalid-format-option` / `unknown-format-option` / `required-value-missing` /
`multi-sheet-unsupported` 400, `column-forbidden` / `report-forbidden` 403, `identity-unavailable` 409,
`quota-exceeded` 429, ошибки запуска `row-limit-exceeded`, `time-budget-exceeded`, `access-revoked`,
`enrichment-failed`, `enrichment-key-missing`, `sink-unavailable`, `write-failed`; скачивания `output-not-ready`,
`output-expired`; `saved-report-stale`.

## Тестирование

Модульные и интеграционные тесты Testcontainers (PostgreSQL) для реестра, валидатора, движка, обоих писателей,
обогащения, персистентности и жизненного цикла запусков, интеграции безопасности, REST API, сохранённых отчётов и
подписок, плюс граничные правила ArchUnit. `crud-service-example` выполняет отчёт целиком против WireMock.

**Базовая производительность** (`ReportLoadTest`, помечен и исключён из сборки по умолчанию; команда - в POM модуля):
1 000 000 строк × 25 колонок, 8 обогащённых через 3 стадии, XLSX **и** CSV за один проход, `-Xmx512m`, обогащение
против внутрипроцессных заглушек, Apple Silicon / OpenJDK 26.0.2:

| | |
|---|---|
| Время | **21,7 s** (47 619 строк/с) |
| Пик кучи (выборка) | 168 MiB |
| Удержано после запуска | 21 MiB (проверяется < 128 MiB) |
| Полных сборок | 5 |
| XLSX / CSV | 93,7 MB / 284,2 MB |

Завершение в куче 512 MiB - это и есть проверка памяти (тест отказывается работать с кучей больше 640 MiB). В
продакшене доминирует реальная задержка партнёров.

## Проектные решения

- **Определения - код**; меняющиеся по пользователю части - проверяемые данные.
- **Права определяются заново при старте отложенного запуска**: отзыв действует и на отчёты.
- **Стадии с правами запросившего никогда не откатываются** на сервисную учётную запись.
- **Пропущенные окна подписок пропускаются**, а не воспроизводятся (нет шторма после сбоя).
- **Ссылки, а не вложения** в `report.ready`.
- **Шаблоны перечитываются на каждый запуск**, а не отслеживаются: запуск читает один раз и длится минуты.
- **Своего advice нет**: все ошибки - `LocalizedException`, которые отрисовывает web-core.
