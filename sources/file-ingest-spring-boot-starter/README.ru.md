# file-ingest-spring-boot-starter

*[English](README.md) · **Русский***

Плановая пакетная загрузка файлов: читает большой объект из хранилища, разбирает его запись за записью, пишет записи в
БД и сообщает о завершении - **никогда не держа файл в памяти и никогда не теряя запись**. Сервис пишет
`RecordParser<R>` (байты → записи) и `RecordApplier<R>` (записи → строки staging); расписание, блокировка, обнаружение,
определение готовности, потоковое чтение диапазонами, контрольные точки, пачки, карантин, проверка баланса,
квитанции, архивирование, метрики, аудит и административная область `fileingest` - забота модуля.

| | |
|---|---|
| Роль | starter |
| Зависит от (в репозитории) | [`audit-core`](../audit-core), [`db-core`](../db-core), [`job-core`](../job-core), [`object-storage`](../object-storage-spring-boot-starter), [`outbox`](../outbox-spring-boot-starter) (во время выполнения опционально), [`web-core`](../web-core-spring-boot-starter) |
| Используется | в этом репозитории никем (сервисы, загружающие файлы партнёров) |
| Поверхности | объектное хранилище (чтение, квитанция, архив); административная область `/admin/v1/fileingest`; опционально событие outbox `ingest.completed` |

**Не reconciliation.** [`reconciliation`](../reconciliation-spring-boot-starter) управляется спросом (локальные строки
запрашивают внешнее состояние по ключу); файл же - источник истины, и его ключи неизвестны до чтения. Построчный учёт
reconciliation хорош для тысяч расходящихся записей и плох для десяти миллионов строк в день.

## Использование

```xml
<dependency>
    <groupId>ru.ludwigandreas</groupId>
    <artifactId>file-ingest-spring-boot-starter</artifactId>
</dependency>
```

Объектное хранилище обязательно; для S3 добавьте артефакты AWS SDK из
[object-storage](../object-storage-spring-boot-starter/README.ru.md#использование). Объявите бины парсера и
применителя для каждой настроенной задачи (валидатор отвергает задачу без бина и бин без задачи).

### Цикл

```
обнаружить объект  →  подтвердить готовность  →  захватить запуск (уникально по bucket+key+etag)
    │
    └─ цикл:  open(uri, Range(контрольная точка, EOF))
              → распаковать → разбить на записи
              → набрать пачку, ограниченную И числом записей, И байтами
              → ОДНА транзакция: { пачка в staging ; сдвиг контрольной точки ; строки карантина ; счётчики чтения }
              → продлить аренду блокировки
    │
    └─ финал: проверка баланса → один set-based MERGE staging → цель
              → run = SUCCEEDED (+ ingest.completed через outbox)  →  квитанция  →  архивирование источника
```

### Гарантии и как они обеспечиваются

| Гарантия | Механизм |
|---|---|
| **Ни дубликатов, ни пропусков при падении** | Пачка, контрольная точка, строки карантина и счётчики чтения коммитятся в **одной транзакции** (`BatchCommitter.commit`). «Данные, потом точка» даёт дубликаты; «точка, потом данные» - тихий пропуск. `CheckpointAtomicityIT` падает, если транзакцию разделить |
| **Ограниченная память** | Пачка сбрасывается по `max-records` **или** `max-bytes`. Запись больше `max-bytes` уходит в карантин как `OVERSIZED`. ArchUnit запрещает `readAllBytes`/`readAllLines`/`Files.readString`; `LargeFileHeapIT` загружает 200 MB под ограничением кучи |
| **Ровно один раз на содержимое** | Уникальное ограничение по `(bucket, key, etag)` в `file_ingest_run` (`versionId` предпочитается в версионируемом бакете). Исправленная перезаливка под тем же именем имеет новый etag и это новый запуск |
| **Никогда не читать недописанный файл** | `arrival.sentinel` (по умолчанию): ждать `{name}.done`. Или `arrival.stability-window`: одинаковые размер + etag в двух опросах. Если не задано ни то, ни другое, пишется предупреждение |
| **Цель не трогается без баланса** | До merge: `read == applied + quarantined + skipped`, `read == expected`, если sentinel объявляет число, и `COUNT(*)` staging = записанным строкам. Несошедшийся баланс валит запуск со всеми числами в сообщении |
| **Атомарное изменение цели** | Таблица staging, затем один set-based `MERGE` |
| **Одна реплика на задачу** | `RunLock`, **продлеваемый внутри цикла пачек**; неудачное продление немедленно останавливает запуск (цена - не больше одной пачки) |
| **Ограниченное время запуска** | `schedule.run-timeout` проверяется между пачками; контрольная точка сохраняется |

**Контрольные точки зависят от формата** (`Checkpoint` - sealed):

| Форма | Для | Цена продолжения |
|---|---|---|
| `ByteOffset` | построчные (CSV, NDJSON) | ranged GET со смещения |
| `RecordOrdinal` | XML, Parquet, фиксированные блоки, форматы с заголовком | перечитать с начала и пропустить |

Смещение в байтах считает **распакованные** байты, поэтому продолжение gzip-объекта перечитывает и отбрасывает (gzip не
умеет seek). Просите крупных партнёров о формате с произвольным доступом.

**Порядок побочных эффектов** фиксирован: сначала `SUCCEEDED` в БД, затем квитанция, затем архив. Падение после
статуса приводит к идемпотентному завершению при повторе. Сбой квитанции или архива не валит запуск.

**Путь записи.** Записи потоком идут в таблицу staging задачи в схеме **сервиса** (колонки знает сервис). Писатель
`auto` использует `COPY` PostgreSQL, если доступен, иначе пакетный JDBC `INSERT`. SQL и JDBC ограничены пакетом
`ru.ludwigandreas.ingest.bulk` (`SqlConfinementTest`); идентификаторы проверяются, значения всегда связываются.
Собственные таблицы модуля запрашиваются через QueryDSL. `truncate-staging-on-fresh-run` применяется **только** при
нулевой контрольной точке (очистка при продолжении потеряла бы работу, которую точка считает сделанной).

**Карантин.** Строка несёт сырую запись (обрезанную по `quarantine.max-record-length`), смещение в байтах, порядковый
номер записи, стадию (`PARSE` / `APPLY` / `OVERSIZED`), ошибку и id запуска.

- `fail-fast`: первая плохая запись валит запуск.
- `threshold` (по умолчанию): продолжать, пока доля ниже `max-ratio`; оценивается после минимум 100 записей. Ловит
  случай «файл разбирается, но каждая строка неверна» (новая колонка, другой разделитель).

**Квитанции** (по умолчанию выключены): `receipt.key-template` (например, `processed/{name}.receipt.json`) с id запуска,
uri и etag источника, числами read/applied/quarantined/skipped, байтами, временем - числа позволяют партнёру проверить
итог самому.

**Событие завершения** (`events.enabled`, требует outbox): `ingest.completed` (`IngestRunSummary`) в той же транзакции,
что и `SUCCEEDED`; id запуска - ключ упорядочивания и идемпотентности. Объявлено бином `OutboxEventTypes`.

**Состояния запуска** - `OperationStatus` web-core; модуль пишет только `RUNNING`, `SUCCEEDED` и `FAILED`.

**Масштабирование** одного объекта по репликам (разбиение по диапазонам байтов) не реализовано; модель контрольных
точек, чтение диапазонами и append-only staging сохраняют такую возможность (понадобится id разбиения в
`file_ingest_run` и сумма баланса по разбиениям).

## Конфигурация

```yaml
ludwig:
  ingest:
    enabled: true
    scheduler-enabled: true        # false на экземпляре, который только обслуживает эндпоинт
    drain-timeout: 30s
    liquibase:
      enabled: true
    events:
      enabled: false               # требует outbox-spring-boot-starter
    endpoint:
      enabled: true
      recent-runs: 20
      allow-manual-run: false
    tasks:
      partner-catalogue:
        source:    { uri: s3://partner-drop/catalogue/, pattern: "catalogue-*.csv.gz",
                     compression: auto, max-objects-per-pass: 1 }
        arrival:   { sentinel: "{name}.done", stability-window: 0s,
                     expected-count-field: recordCount }
        schedule:  { cron: "0 30 6 * * *", run-timeout: 2h }
        lock:      { lease: 5m, renew-at-remaining-fraction: 0.3 }
        batch:     { max-records: 5000, max-bytes: 32MB }
        quarantine: { policy: threshold, max-ratio: 0.005, max-record-length: 8KB }
        receipt:   { enabled: true, key-template: "processed/{name}.receipt.json" }
        archive:   { mode: move, prefix: "processed/" }
        alert:     { expected-by: "07:00", zone: Europe/Moscow }
        write:     { staging-writer: auto, jdbc-batch-size: 1000,
                     truncate-staging-on-fresh-run: true }
```

| Свойство | Смысл |
|---|---|
| `ludwig.ingest.scheduler-enabled` | Запускать расписания на этом экземпляре |
| `ludwig.ingest.drain-timeout` | Ожидание выполняющегося прохода при остановке |
| `tasks.<t>.source.uri` / `pattern` / `compression` / `max-objects-per-pass` | Что читать |
| `tasks.<t>.arrival.sentinel` / `stability-window` / `expected-count-field` | Определение готовности и объявленное число |
| `tasks.<t>.schedule.cron` / `run-timeout` | Когда и бюджет запуска |
| `tasks.<t>.lock.lease` / `renew-at-remaining-fraction` | Аренда и запас продления (доля, чтобы изменение аренды сохраняло запас) |
| `tasks.<t>.batch.max-records` / `max-bytes` | Границы пачки |
| `tasks.<t>.quarantine.policy` / `max-ratio` / `max-record-length` | Поведение карантина |
| `tasks.<t>.receipt.*`, `archive.mode` / `prefix` | Побочные эффекты после завершения |
| `tasks.<t>.alert.expected-by` / `zone` | Срок для `ludwig.ingest.missing` |
| `tasks.<t>.write.staging-writer` / `jdbc-batch-size` / `truncate-staging-on-fresh-run` | Путь записи в staging |

**Старт отвергает** (одним сообщением): задачу без бина и бин без задачи, неразбираемый cron, `run-timeout` короче
аренды, `max-record-length` больше `batch.max-bytes`, шаблон sentinel, заставляющий все объекты ждать один маркер,
неизвестный пояс, неразбираемый uri источника, события без outbox.

## Развёртывание

- **Схема** (`db/changelog/file-ingest/file-ingest-changelog.xml`, id `file-ingest-NNNN` / автор
  `ludwig-file-ingest`, собственный бин `SpringLiquibase`, `ludwig.ingest.liquibase.enabled`): `file_ingest_run`
  (`container` + `object_key` + `content_identity` с уникальным ограничением, статус, контрольная точка, счётчики,
  время, владелец блокировки, версия, флаги после завершения) и `file_ingest_quarantine`. Внешнего ключа между ними нет
  (разные решения о сроке хранения).
- **Таблицы staging** - в собственном changelog сервиса, по одной на задачу.
- **Доступ к бакету**: чтение/листинг префикса источника; put для квитанций; copy/delete для архивирования.
- **Договорённость с партнёром**: sentinel (`{name}.done`, опционально с числом записей) - самый надёжный сигнал
  готовности.

## Наблюдаемость

| Метрика | Тип | Теги | Смысл |
|---|---|---|---|
| `ludwig.ingest.runs` | timer | `task`, `status` (`succeeded`, `failed`) | Длительность и исход запуска |
| `ludwig.ingest.records` | counter | `task`, `outcome` (`read`, `applied`, `quarantined`, `skipped`) | Учёт записей |
| `ludwig.ingest.bytes.read` | counter | `task` | Прочитанные распакованные байты |
| `ludwig.ingest.batch.flush` | timer | `task` | Сброс пачки (включая контрольную точку) |
| `ludwig.ingest.merge` | timer | `task` | Merge staging → цель |
| `ludwig.ingest.skipped` | counter | `task`, `reason` | Уже обработанные объекты |
| **`ludwig.ingest.missing`** | gauge | `task` | **1, если к `alert.expected-by` сегодня нет запуска `SUCCEEDED`** |

`missing` - сигнал о файле, который так и не пришёл: нет запуска, нет ошибки, таблица тихо устаревает. Считаются только
запуски `SUCCEEDED`, и монитор работает на каждом экземпляре без блокировки (поэтому не останавливается вместе с
держателем блокировки).

**Административная область** `/admin/v1/fileingest`: недавние запуски со счётчиками, конвертом `operation` и
**контрольными точками** (движущаяся точка = большой файл; неподвижная = проблема); запуск задачи - операция
`fileingest.operate`.

**Логи.** `INFO`: конфигурация проверена, расписания запущены, выбран писатель staging, пропуск уже загруженного,
«ещё не читаю» (готовность), ручной запуск, квитанция записана, архив выполнен. `WARN`: нет завершённого запуска к
сроку, не настроен механизм готовности, сбой квитанции или архива. `ERROR`: запуск упал.

**Рекомендуемые алерты:**

| Условие | Почему |
|---|---|
| `ludwig_ingest_missing == 1` | Сегодняшний файл не загружен |
| `increase(ludwig_ingest_runs_seconds_count{status="failed"}[1d]) > 0` | Сбой баланса, порога карантина, таймаута или аренды |
| `increase(ludwig_ingest_records_total{outcome="quarantined"}[1d])` выше обычного | Дрейф формата партнёра |

**Известные пробелы:** нет срока хранения для `file_ingest_run` и `file_ingest_quarantine`; строки карантина хранят
сырой текст записей (возможно, персональные данные). Очищайте их по расписанию, подходящему для данных.

## Аудит

Категория `ingest`; ресурс `ingest-task` / имя задачи (задача переживает запуски; id запуска - атрибут).

| Действие | Исход |
|---|---|
| `ingest.claimed`, `ingest.resumed`, `ingest.batch-committed`, `ingest.merged`, `ingest.completed`, `ingest.skipped-already-processed` | `SUCCESS` |
| `ingest.quarantined` | `PARTIAL` |
| `ingest.failed`, `ingest.missing` | `FAILURE` |

«Что пошло не так за ночь» - запрос по колонке исхода.

## Администрирование

Эндпоинты оператора находятся на административной поверхности платформы, `/admin/v1/{area}`, и существуют только там, где есть поверхность `security-spring-boot-starter`. Каждый защищён одной операцией; какие роли сервиса ею владеют, решает собственный `ludwig.admin.grants` сервиса (роли из резолвера полномочий, никогда не из claims токена), а операция, не выданная ни одной роли, запрещена всем. Вызов уровня `privileged` требует заголовка `Admin-Reason`, и каждый вызов записывается в аудит с категорией `admin` и идентификатором операции в качестве действия. `GET /admin/v1/me/operations` показывает, какие операции есть у вызывающего.

| Операция | Эндпоинты | Что разрешает |
|---|---|---|
| `fileingest.read` | `GET /admin/v1/fileingest/tasks`<br>`GET /admin/v1/fileingest/tasks/{task}`<br>`GET /admin/v1/fileingest/tasks/{task}/runs/{runId}` | Просмотр задач загрузки и их запусков с контрольной точкой и счётчиками каждого. |
| `fileingest.operate` | `POST /admin/v1/fileingest/tasks/{task}/runs` | Запустить проход загрузки сейчас. |
| `fileingest.privileged` | в этом выпуске нет | Операций пока нет; уровень зарезервирован, чтобы выдачи для этой области не менялись между выпусками. |

**Удалено:** actuator-эндпоинт `fileingest` и `ludwig.ingest.endpoint.*` (включая `allow-manual-run`); запуск прогона - операция `fileingest.operate`. Запрос прогона во время выполняющегося - `409`.

Блок выдачи (имена ролей - собственные роли сервиса):

```yaml
ludwig:
  admin:
    grants:
      "[fileingest.read]": [ORDERS_ADMIN, ORDERS_SUPPORT]
      "[fileingest.operate]": [ORDERS_ADMIN]
      "[fileingest.privileged]": [ORDERS_ADMIN]
```

## Runbook

| Симптом | Вероятная причина | Что делать |
|---|---|---|
| `ludwig_ingest_missing == 1` | Файл не доставлен, нет sentinel, не совпал шаблон или запуск упал | Проверить в бакете объект и `{name}.done`; строка лога `is not reading ... yet` называет причину; проверить последний запуск в `GET /admin/v1/fileingest/tasks/{task}` |
| Запуск `FAILED`: несошедшийся баланс | Обрезанный файл (объявленное число отличается), писатель staging потерял строки или ошибка парсера | В сообщении все четыре числа; цель не тронута. Попросить партнёра полный файл; перезалить (новый etag) для повторного запуска |
| Запуск `FAILED`: порог карантина | Партнёр изменил формат | `SELECT stage, error, count(*) FROM file_ingest_quarantine WHERE run_id = '<id>' GROUP BY 1, 2;` исправить парсер или договориться с партнёром |
| `RUNNING` с неподвижной контрольной точкой | Зависший ввод-вывод или БД | `SELECT id, checkpoint_position, records_read, locked_by, started_at FROM file_ingest_run WHERE status = 'RUNNING';` thread dump; аренда истечёт, и следующий проход продолжит с контрольной точки |
| Запуск остановлен по `run-timeout` | Файл вырос или staging деградировал | Осознанно поднять `run-timeout`; следующий запуск продолжит |
| Исправленный файл не подхватывается | Тот же etag (файловое хранилище с сохранённым mtime) | Перезалить так, чтобы изменилась идентичность содержимого |
| Намеренно перезагрузить файл | - | Удалять или переименовывать строку идентичности только при полной уверенности: `SELECT id, status, content_identity FROM file_ingest_run WHERE task = '<task>' AND object_key = '<key>';` - лучше перезалить объект |
| Предупреждения квитанции/архива | Права на бакет | Исправить права; следующий проход идемпотентно повторит |

## Тестирование

`mvn test`: ограничение пачки по обоим пределам, арифметика баланса, порог карантина на границе и за ней, арифметика
контрольных точек, шаблоны путей, экранирование `COPY`, проверка идентификаторов, общие и модульные правила ArchUnit
(ограничение SQL/JDBC, запрет материализации, граница published-api, отсутствие `@RestControllerAdvice`).

`mvn verify` (Docker; PostgreSQL и LocalStack, закреплённые по digest, поднимаются один раз на набор):

| Тест | Доказывает |
|---|---|
| `LargeFileHeapIT` | 200 MB загружаются под измеренным потолком кучи |
| `CheckpointAtomicityIT` | пачка и контрольная точка откатываются вместе (падает, если разделить транзакцию) |
| `CheckpointResumeIT` | убитый запуск продолжается ровно один раз, передаёт меньше всего объекта, ranged GET с закоммиченного смещения |
| `FileIngestLifecycleIT` | карантин с порядковым номером и смещением; падение по порогу не трогает цель; повторное предложение пропускается; то же имя с новым etag = новый запуск; каждый успех сбалансирован |
| `SentinelArrivalIT` | без sentinel - не читается; sentinel не загружается; несовпадение числа валит запуск, не трогая цель |
| `ConcurrentReplicaIT` | две реплики → один запуск, один набор строк staging |
| `SchedulingAndMissingFileIT` | расписания стартуют; `missing` становится 1 после срока и возвращается в 0 |
| `RunTimeoutIT` | запуск после `run-timeout` останавливается между пачками, сохраняя контрольную точку |
| `ReceiptAndArchiveIT` | числа в квитанции, архив, падение между `SUCCEEDED` и архивом завершается идемпотентно |

## Проектные решения

- **Контрольная точка и данные в одной транзакции** - весь аргумент «никогда не терять данные»; не разносите их при
  рефакторинге.
- **Идентичность по содержимому, обеспеченная ограничением**, а не флагом.
- **Баланс до merge**: проверка после merge лишь сообщала бы об ущербе.
- **Sentinel по умолчанию**: вывод о готовности слабее утверждения партнёра.
- **Автоконфигурация в `ru.ludwigandreas.ingest.autoconfigure`**, отдельно от `config` (свойства и валидатор), чтобы
  избежать цикла пакетов.
- **Исключение для SQL ограничено одним пакетом** - для `COPY` и set-based merge, которые QueryDSL не выражает.
