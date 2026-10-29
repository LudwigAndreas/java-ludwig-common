# file-action-spring-boot-starter

*[English](README.md) · **Русский***

Интерактивные действия над таблицами: человек загружает XLSX или CSV, и происходит бизнес-действие - четыреста строк
становятся четырьмястами заказами или одним заказом на четыреста позиций. Сервис пишет `RowBinding`, обработчик и
блок YAML; модуль отвечает за допуск, бюджет размера, определение формата по содержимому, сохранение до разбора,
захват идемпотентности, шов антивирусной проверки, ограниченный SAX-чтец, жизненный цикл (включая подтверждение),
политику фиксации, отчёт об отклонениях, конверт операции, HTTP-поверхность, шаблон, срок хранения и аудит.

| | |
|---|---|
| Роль | starter |
| Зависит от (в репозитории) | `audit-core`, `db-core`, `idempotency`, `job-core`, `object-storage`, `observability`, `outbox`, `security`, `web-core` |
| Используется | `crud-service-example` |
| Поверхности | REST `/api/v1/file-actions/**` (multipart); объектное хранилище |

**Не file-ingest.** [`file-ingest`](../file-ingest-spring-boot-starter) - плановая, партнёрская и продолжаемая загрузка
(контрольные точки по байтам, staging, set-based merge). Этот модуль интерактивен: ничего не продолжается с середины
файла (человек отправляет заново), строки становятся доменными вызовами, а ошибки - фразами с листом, строкой и
заголовком колонки на языке читателя. Граница - потолок (по умолчанию 25 MiB и 100 000 строк): выше - file-ingest.
Общего `file-format-core` нет, пока второму модулю не понадобится читать CSV или XLSX.

## Использование

```xml
<dependency>
    <groupId>ru.ludwigandreas</groupId>
    <artifactId>file-action-spring-boot-starter</artifactId>
</dependency>
```

```java
@FileAction("order-import")
public class OrderImportHandler implements RowHandler<OrderLine> {

    private static final RowBinding<OrderLine> ORDER_LINES = RowBinding.of(OrderLine.class)
            .sheet("Orders")
            .column("sku", "SKU").aliases("Article")
            .column("quantity", "Qty")
            .column("price", "Price")
            .column("dueDate", "Due")
            .column("comment", "Comment").optional()
            .build();

    @Override
    public RowBinding<OrderLine> binding() {
        return ORDER_LINES;
    }

    @Override
    public RowOutcome apply(OrderLine row, FileActionContext context) {
        if (!catalogue.exists(row.sku())) {
            return RowOutcome.rejected("orders.import.unknown-sku", row.sku());
        }
        orders.create(row, context.submissionId());
        return RowOutcome.applied();
    }
}

public record OrderLine(@NotBlank String sku, @Positive Integer quantity,
                        @PositiveOrZero BigDecimal price, LocalDate dueDate, String comment) {
}
```

```yaml
ludwig:
  file-action:
    storage:
      uploads: s3://orders/file-action/uploads
      artifacts: s3://orders/file-action/artifacts
    scanning:
      mode: required
    actions:
      order-import:
        commit-policy: PER_ROW
        mode: CONFIRM
        execution: INLINE
        required-authority: ORDER_IMPORT
```

Эндпоинты появляются, как только действие настроено. Привязка - это код: имена полей проверяются по компонентам
record-а при инициализации класса, типы берутся из record-а, ограничения - Bean Validation.

### Основной путь

```
POST /api/v1/file-actions/order-import   (multipart)
      1. admit      потолок размера проверяется ВО ВРЕМЯ чтения тела
      2. spool      один проход во временный файл через дайджест SHA-256
      3. sniff      магические байты; заявленный content type - лишь подсказка
      4. store      ObjectStore.put по хэшу                <- всегда до любого разбора
      5. claim      IdempotencyStore по хэшу + действию + вызывающему
      6. record     строка submission                       <- до начала чтения
      7. scan       шов FileScanner
      8. dispatch   INLINE: прочитать, связать, проверить, применить сейчас -> 200, терминальный конверт
                    DEFERRED: 202 + Operation-Location; воркер захватывает под арендой
```

Байты всегда сохраняются первыми: несохранённый вход нельзя повторить, процитировать в отчёте об ошибках,
использовать в споре или пережить эвикцию пода посреди разбора. `INLINE` и `DEFERRED` - свойство с одинаковой формой
ответа, поэтому переключение не требует изменений клиента; действие `INLINE` выше `inline.max-rows` не стартует.

### Жизненный цикл

```
                        UPLOADED
          +----------------+----------------+
      REJECTED         VALIDATED         APPLYING          (mode: DIRECT пропускает VALIDATED)
                           | (confirm)      |
                        EXPIRED           APPLIED
                     (истёк TTL)

  CANCELLED  из VALIDATED сразу; из UPLOADED или APPLYING на следующей контрольной точке
```

**Отмена завершается состоянием `CANCELLED`** (конверт `CANCELLED`), никогда `APPLIED`. Отправка в `VALIDATED`
отменяется сразу (`200`); читаемая или применяемая останавливается на следующей контрольной точке - до начала
применения или между пакетами - и отмена отвечает `202`. Строки, зафиксированные до остановки, сохраняются и
учитываются в `rowsApplied`; пакет в работе никогда не прерывается. Остановка аудируется как `file-action.cancelled`.
Отмена и подтверждение, одновременно пришедшие для одной проверенной отправки, не могут выиграть обе.

`FileActionState` - доменный жизненный цикл в `OperationResponse.detail()` (`UPLOADED` и `VALIDATED` оба
`PENDING`; разница - кнопка «Подтвердить»). Режимы: `CONFIRM` (по умолчанию - предпросмотр, затем применение),
`DIRECT` (машинные вызывающие, обратимые действия), `VALIDATE_ONLY` (никогда не применяет). **Подтверждение применяет
строки, показанные пользователю** (артефакт связанных строк, записанный при валидации), а не перечитанную книгу
(`ConfirmAppliesPreviewedRowsIT`).

### Формы обработчиков и политика фиксации

```
sealed interface FileActionHandler<R>
   |- RowHandler<R>        применить строку      400 строк -> 400 заказов   частичный успех осмыслен
   `- DocumentHandler<R>   применить всё         400 строк -> 1 заказ       частичный успех бессмыслен
```

У `commit-policy` **нет значения по умолчанию** - без неё действие не стартует:

| Политика | Одна отклонённая строка | Подходит для |
|---|---|---|
| `ALL_OR_NOTHING` | ничего не применяется | проводки, инвентаризации |
| `PER_BATCH` | откатывается эта пачка, предыдущие остаются | массовое обновление цен |
| `PER_ROW` | отклоняется эта строка, остальные применяются | импорт заказов, списки контактов |

`DocumentHandler` обязан использовать `ALL_OR_NOTHING`. `reject-threshold` (по умолчанию `0.1`) отклоняет всю отправку,
если отклонена большая доля строк («совсем не тот файл»); намеренно пропущенные строки не считаются.

### Куда помещать правило проверки

| | Выполняется | Называет строку | Для |
|---|---|---|---|
| **Связывание** - преобразование, Bean Validation, компактный конструктор record-а | раз на строку при чтении | да (лист, отображаемая строка, заголовок) | любого правила, предмет которого - одна строка |
| **Обработчик** | внутри транзакции применения | `RowHandler`: да; `DocumentHandler`: **нет** | правил между строками |

1. **Правило строки без обращений** - на record-е: ограничение поля (указывает на ячейку), ограничение класса или
   компактный конструктор (указывают на строку). Собираются все проблемы строки; Bean Validation выполняется только
   для построенного record-а; строка с проблемами никогда не доходит до обработчика.
2. **Правило строки с обращением** («такой SKU существует») - Spring-бин `ConstraintValidator`. Один запрос на строку,
   поэтому поставьте за ним кэш с назначением `PERFORMANCE` из [cache](../cache-spring-boot-starter). Он должен быть
   **без состояния** (валидаторы - общие синглтоны), только на чтение, а сообщение интерполируется в локали проверки
   (держите его в `ValidationMessages[_ru].properties`).
3. **Правило между строками** («строки дают сумму», «нет повторяющихся SKU») - `DocumentHandler`:

```java
@FileAction("order-import")
public class OrderImportHandler implements DocumentHandler<OrderLine> {

    @Override
    public RowBinding<OrderLine> binding() {
        return OrderBindings.ORDER_LINES;
    }

    @Override
    public RowOutcome apply(Stream<OrderLine> rows, FileActionContext context) {
        Order order = Order.draft(context.submissionId());
        Map<String, Integer> quantityBySku = new LinkedHashMap<>();

        rows.forEach(line -> {                       // свернуть один раз, никогда не collect
            quantityBySku.merge(line.sku(), line.quantity(), Integer::sum);
            order.addLine(line);
        });

        // проверки между строками ДО возврата для dry run, чтобы предпросмотр CONFIRM их показал
        if (order.lineCount() == 0) {
            return RowOutcome.rejected("order.import.no-lines");
        }
        if (quantityBySku.size() != order.lineCount()) {
            return RowOutcome.rejected("order.import.duplicate-sku", firstDuplicate(quantityBySku));
        }
        if (context.dryRun()) {
            return RowOutcome.applied();             // проверено; ничего не записано
        }
        orders.save(order);
        return RowOutcome.applied();
    }
}
```

`DocumentHandler` обязан: прочитать поток один раз; выполнить проверки между строками до возврата для `dryRun()`;
ничего не менять при `dryRun()`; класть идентифицирующие значения в аргументы отклонения; не вызывать партнёров внутри
транзакции (использовать outbox); возвращать отклонение, а не бросать исключение, для проблем документа.

**Действие-документ должно задавать `reject-threshold: 0`.** Строки, не прошедшие связывание, не доходят до
обработчика; при `0.1` по умолчанию 30 плохих строк из 400 молча отбрасываются, и заказ создаётся на 370 позиций.
Валидатор этого не проверяет (`docs/harness-enforcement.md`).

```yaml
ludwig:
  file-action:
    actions:
      order-import:
        commit-policy: ALL_OR_NOTHING
        mode: CONFIRM
        reject-threshold: 0
        execution: DEFERRED            # большой документ: одна долгая транзакция вне запроса
        error-report: ANNOTATED_WORKBOOK
```

### Чтение недоверенной таблицы

| Угроза | Защита |
|---|---|
| Взрыв памяти | Только SAX (`XSSFReader` + `XSSFSheetXMLHandler`); `PoiConfinementTest` запрещает DOM-чтецы |
| Zip-бомбы | Минимальный коэффициент сжатия, пределы числа записей и общего размера распаковки по central directory ZIP (на действие); JVM-глобальный `ZipSecureFile` POI как страховка |
| Таблица shared strings | Проверяется отдельно по каталогу (это реальный потолок памяти) |
| Внешние ссылки | `/xl/externalLinks` отклоняется |
| Старый `.xls` | Распознаётся и отклоняется с «сохраните как .xlsx» |
| Переименованные файлы | Формат по магическим байтам |
| Формулы в сгенерированных файлах | Значения, начинающиеся с `=`, `+`, `-`, `@`, нейтрализуются |
| XML-сущности | DTD и внешние сущности выключены для `xl/workbook.xml` |

**Локаль.** Ячейки преобразуются в локали и поясе **отправителя** (хранятся в submission): `1,234` зависит от локали;
разделитель групп Excel в локалях с десятичной запятой - неразрывный пробел; ячейки дат - числа (нет путаницы
день/месяц); эпоха 1904 (`date1904="1"`, Excel для Mac) учитывается; короткие шаблоны с двузначным годом расширяются.

### Отклонения

Проблемы уровня файла - документы RFC 9457 через web-core. Отклонения строк - **артефакт**:

- `file_action_row_reject` хранит **ограниченную выборку** (по умолчанию 100); `rowsRejected` и `rejectsStored`
  говорят UI, что есть ещё;
- `GET .../rejects` (постранично) и `error-report: ANNOTATED_WORKBOOK` - лист пользователя с колонкой `Problems`
  (для CSV - CSV-отчёт);
- адресация - лист, **отображаемая строка с 1** и **имя заголовка**; хранятся код и аргументы, отрисовываются на
  языке читателя.

### HTTP-поверхность (фиксированный базовый путь)

```
POST   /api/v1/file-actions/{action}                    multipart -> 200 | 202  SubmissionResponse
GET    /api/v1/file-actions/{action}/{id}               -> SubmissionResponse (+ Retry-After, пока выполняется)
POST   /api/v1/file-actions/{action}/{id}/confirm       -> 200 | 202
POST   /api/v1/file-actions/{action}/{id}/cancel        -> 202 (кооперативно) | 200 (VALIDATED или уже завершённая)
GET    /api/v1/file-actions/{action}/{id}/rejects       -> PageResponse<RowRejectResponse>
GET    /api/v1/file-actions/{action}/{id}/error-report  -> аннотированная книга или CSV отклонений
GET    /api/v1/file-actions/{action}/template           -> пустая книга, сгенерированная из привязки
```

`SubmissionResponse` вкладывает платформенный `OperationResponse` плюс счётчики строк, отклонений и признак
необходимости подтверждения. Ключ идемпотентности: `sha256(content) + action + caller` через `IdempotencyStore`
(двойной клик и повтор POST получают конверт первой отправки). `IdempotencyFilter` не используется (он буферизует тело
в памяти).

`RuleGroup.UPLOADS` в architecture-rules валит сборку, если `MultipartFile` упоминает любой другой модуль.

## Конфигурация

```yaml
ludwig:
  file-action:
    enabled: true
    scanning:
      mode: required                 # required | optional | disabled - закрыт при отказе
    storage:
      uploads: s3://orders/file-action/uploads        # без значения по умолчанию
      artifacts: s3://orders/file-action/artifacts    # без значения по умолчанию
      retention: P7D
      retention-interval: PT1H
      retention-batch-size: 100
      retention-lease: PT5M
    inline:
      max-rows: 5000
    deferred:
      lease: PT5M
      batch-size: 5
      max-attempts: 3
      interval: PT10S
      initial-delay: PT15S
      drain-timeout: PT30S
    defaults:                        # каждый ключ переопределяется для действия
      max-size: 25MB
      max-rows: 5000                 # = inline.max-rows, так как execution по умолчанию INLINE
      max-rows-limit: 100000         # потолок модуля; выше - file-ingest
      formats: [xlsx, csv]
      batch-size: 500
      reject-sample: 100
      reject-threshold: 0.1
      confirm-ttl: PT30M
      error-report: ANNOTATED_WORKBOOK
      archive:
        max-entries: 1000
        min-inflate-ratio: 0.01
        max-uncompressed: 512MB
        max-shared-strings: 16MB
        max-cell-characters: 32000
        read-timeout: PT2M
    actions:
      order-import:
        commit-policy: PER_ROW       # без значения по умолчанию
        mode: CONFIRM                # DIRECT | CONFIRM | VALIDATE_ONLY
        execution: INLINE            # INLINE | DEFERRED
        required-authority: ORDER_IMPORT
```

У `storage.uploads` / `storage.artifacts` нет значений по умолчанию: единственный возможный - локальный каталог, то
есть в продакшене эфемерный диск пода.

**Старт отвергает** (все проблемы, каждая с действием, свойством и допустимыми значениями): действие без бина
`@FileAction` или бин без конфигурации; отсутствие `commit-policy`; построчную политику у `DocumentHandler`;
`scanning.mode: required` без бина `FileScanner`; действие `INLINE` выше потолка inline или любое действие выше
`max-rows-limit`; `required-authority` без security-стартера; `reject-threshold` вне 0..1 или неположительный
`batch-size`; код проблемы, отсутствующий в любой из поставляемых локалей; бин `@FileAction`, не являющийся
`FileActionHandler`.

## Развёртывание

- **Схема** (`db/changelog/file-action/file-action-changelog.xml`): `file_action_submission` (очередь, с арендой и
  `@Version`) и `file_action_row_reject`.
- **Бакеты/префиксы** для загрузок и артефактов через [object-storage](../object-storage-spring-boot-starter).
- **Бин `FileScanner`** (ICAP, `clamd`, облачный API) - модуль поставляет SPI без реализации; или явно
  `scanning.mode: disabled`.
- **Пределы multipart**: `spring.servlet.multipart.max-file-size` / `max-request-size` не меньше `max-size`; пределы
  тела на ingress аналогично.
- **Отложенный воркер**: без `RunLock` (экземпляры делят очередь); **срок хранения** - под `RunLock`, сначала удаляет
  объекты, затем помечает строки `EXPIRED`.

## Наблюдаемость

**Логи.** `INFO` итоги очистки по сроку. `WARN`: отправка не обработана; отправка упала `max-attempts` раз и больше не
повторяется; не удалось построить отчёт об отклонениях; сбои очистки временных файлов; объекты, не удалённые при
очистке (осиротели).

**Известные пробелы:** модуль **не публикует метрик** - нет счётчиков отправок, применённых/отклонённых строк, отказов
антивируса, глубины отложенной очереди и очистки, нет таймеров чтения/применения. Эксплуатация опирается на логи,
аудит и SQL. Состояние отложенной очереди видно только через
`SELECT state, count(*) FROM file_action_submission GROUP BY state;`.

## Аудит

Категория `file-action`:

| Действие | Когда |
|---|---|
| `file-action.submitted` | Загрузка принята и сохранена |
| `file-action.duplicate` | Повтор того же содержимого + действия + вызывающего |
| `file-action.scanned` | Вердикт антивируса |
| `file-action.rejected` | Отказ на уровне файла или по порогу |
| `file-action.validated` | Проверка завершена (предпросмотр готов) |
| `file-action.confirmed` | Пользователь подтвердил |
| `file-action.applied` | Строки применены |
| `file-action.cancellation-requested` | Запрошена отмена |
| `file-action.cancelled` | Отправка остановлена и завершилась `CANCELLED` |
| `file-action.expired` | Очистка удалила артефакты (actor `system`) |

Записывается: имя файла, хэш содержимого, размер, счётчики, ответ антивируса, исход - **никогда ячейки файла**.

## Администрирование

Эндпоинты оператора находятся на административной поверхности платформы, `/admin/v1/{area}`, и существуют только там, где есть поверхность `security-spring-boot-starter`. Каждый защищён одной операцией; какие роли сервиса ею владеют, решает собственный `ludwig.admin.grants` сервиса (роли из резолвера полномочий, никогда не из claims токена), а операция, не выданная ни одной роли, запрещена всем. Вызов уровня `privileged` требует заголовка `Admin-Reason`, и каждый вызов записывается в аудит с категорией `admin` и идентификатором операции в качестве действия. `GET /admin/v1/me/operations` показывает, какие операции есть у вызывающего.

| Операция | Эндпоинты | Что разрешает |
|---|---|---|
| `fileactions.read` | `GET /admin/v1/fileactions/submissions`<br>`GET /admin/v1/fileactions/submissions/{id}` | Просмотр загрузок файловых действий всех пользователей, без самого файла и его строк. |
| `fileactions.operate` | `POST /admin/v1/fileactions/submissions/{id}/cancel` | Отменить загрузку файлового действия любого пользователя. |
| `fileactions.privileged` | в этом выпуске нет | Операций пока нет; уровень зарезервирован, чтобы выдачи для этой области не менялись между выпусками. |

Ответы не содержат ни файла, ни содержимого строк, ни ссылки на отчёт об ошибках. Отмена - та же отмена, что у отправителя: `202`, пока остановка ожидается, либо `200` с конвертом, если отправка завершена, в том числе если отмена сама завершила её `CANCELLED`, как отправку в `VALIDATED`.

**Фильтрация `GET /admin/v1/fileactions/submissions`.** Список принимает OData `$filter` и никаких других параметров фильтрации; он листается через `cursor` и `size` в фиксированном порядке от новых к старым, поэтому `$orderby`, `$top`, `$skip` и `$count` - `400`, как и поле, не перечисленное здесь. Фильтр применяется поверх собственных правил области и может их только сузить. Фильтруемые поля: `action`, `state` (`UPLOADED`, `VALIDATED`, `APPLYING`, `APPLIED`, `REJECTED`, `EXPIRED`, `CANCELLED`), `submittedBy`, `sourceFormat`, `failureCode`, `rowsRejected`, `submittedAt`, `startedAt`, `finishedAt`. Не фильтруются: расположение объекта, заявленное имя файла, аргументы ошибки. Пример: `$filter=action eq 'product-import' and rowsRejected gt 0`.

Блок выдачи (имена ролей - собственные роли сервиса):

```yaml
ludwig:
  admin:
    grants:
      "[fileactions.read]": [ORDERS_ADMIN, ORDERS_SUPPORT]
      "[fileactions.operate]": [ORDERS_ADMIN]
      "[fileactions.privileged]": [ORDERS_ADMIN]
```

## Runbook

| Симптом | Вероятная причина | Что делать |
|---|---|---|
| Старт падает со списком проблем file-action | Нет `commit-policy`, сканера, бина или несогласованные пределы | Исправить каждое названное свойство |
| Загрузка отклонена `too-large` / `too-many-rows` | Выше `max-size` / `max-rows` | Для массовых загрузок - file-ingest, или осознанно поднять для действия |
| `suspicious-archive` / `external-references` / `legacy-xls` / `unsupported-format` | Защиты выше | Попросить пользователя пересохранить как обычный `.xlsx` |
| `missing-column` / `no-header-row` / `sheet-not-found` | Несоответствие шаблону | Направить пользователя к `GET .../template` |
| `too-many-rejects` | Не тот файл или плохие данные | Пользователь скачивает `error-report`, исправляет, отправляет заново |
| `confirm-window-closed` | Истёк `confirm-ttl` | Отправить заново |
| `scan-rejected` | Антивирус пометил файл (файл удалён) | Проверка безопасности, если неожиданно |
| Отложенные отправки не продвигаются | Воркер остановлен или исчерпаны попытки | `SELECT id, action, state, attempts, failure_code, lease_expires_at FROM file_action_submission WHERE state NOT IN ('APPLIED','REJECTED','EXPIRED') ORDER BY created_at;` и предупреждение `has failed N times` |
| Действие-документ создало неполные агрегаты | `reject-threshold` не 0 | Задать `reject-threshold: 0` для действий `DocumentHandler` |

Коды проблем (префикс `file-action.`): `too-large`, `too-many-rows`, `unsupported-format`, `legacy-xls`, `unreadable`,
`suspicious-archive`, `external-references`, `missing-column`, `no-header-row`, `sheet-not-found`, `empty`,
`scan-rejected`, `too-many-rejects`, `wrong-state`, `not-found`, `confirm-window-closed`, `not-confirmable`,
`forbidden`, `unknown-action`; коды строк `cell-not-coercible`, `cell-required`, `ragged-row`, `constraint-violated`.

## Тестирование

Модульные тесты (политики фиксации проверяются по commit/rollback, преобразование, локали, эпохи, проверка архивов),
интеграционные тесты (Docker), включая `ConfirmAppliesPreviewedRowsIT` и `LargeWorkbookHeapIT` (все 100 000 строк
доставлены), и `LargeWorkbookHeapMeasurementIT` (тег `measurement`, запускается явно):

```bash
mvn -pl :file-action-spring-boot-starter verify \
    -Dfile-action.test.excluded.groups= -Dit.test=LargeWorkbookHeapMeasurementIT \
    -DargLine="-Xmx256m"
```

Он проверяет, что удерживаемая куча не пропорциональна файлу и что DOM-чтение удерживает минимум в 5 раз больше
(доказывая, что бюджет способен упасть). `PoiConfinementTest`, `NoSqlStringsTest` (здесь нет исключения для SQL).

**Нельзя проверить автоматически, поэтому записано** (также в `docs/harness-enforcement.md`): `RowHandler` должен быть
идемпотентен по строке (перехват аренды может повторно применить пачку); `DocumentHandler` не должен вызывать
партнёров внутри транзакции; подходит ли объявленная `commit-policy` предметной области; действительно ли `FileScanner`
проверяет файлы.

## Проектные решения

- **Сохранить до разбора**, всегда.
- **Подтверждение по умолчанию**: у неверного применения нет отмены.
- **Нет политики фиксации по умолчанию**: любое значение по умолчанию молча неверно для кого-то.
- **Отклонения - артефакт**, ограниченный и адресованный по имени заголовка, а не 400 с тысячами записей.
- **Антивирус закрыт при отказе.**
- **Строка submission - это очередь** (нет брокера, который мог бы с ней разойтись).
- **Срок хранения - задача модуля**, а не правило жизненного цикла бакета (которое оставило бы `SUCCEEDED` со ссылкой
  в никуда).
- **Здесь нет**: продолжаемых контрольных точек, staging/merge, `.xls`/`.ods`/HTML/PDF, реализации антивируса, UI,
  переиспользования писателя export.
