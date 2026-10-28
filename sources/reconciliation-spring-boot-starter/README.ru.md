# reconciliation-spring-boot-starter

*[English](README.md) · **Русский***

Поддерживает в синхронном состоянии локальные записи, зеркалирующие внешнее состояние, - входящий двойник
[outbox](../outbox-spring-boot-starter). Сервис пишет запрос спроса, fetcher и reconciler вместо самодельного цикла
`@Scheduled`; планирование, выбор лидера, staging, повторы с backoff и карантином, защита от устаревшей записи,
идемпотентность по хэшу payload, квоты и rate limit партнёров, асинхронные задания партнёров, зеркалирование коллекций
(задачи перечисления), SLO-метрика свежести, аудит и эндпоинты оператора административной области `reconciliation` - забота модуля.

| | |
|---|---|
| Роль | starter |
| Зависит от (в репозитории) | [`audit-core`](../audit-core), [`db-core`](../db-core), [`job-core`](../job-core), [`observability`](../observability-spring-boot-starter), [`rest-client`](../rest-client-spring-boot-starter) |
| Используется | в этом репозитории никем (сервисы, зеркалирующие состояние партнёров) |
| Поверхности | исходящий REST (fetcher-ы); административная область `/admin/v1/reconciliation` |

| # | Стадия | Владелец |
|---|---|---|
| 1 | Расписание и выбор лидера | этот модуль |
| 2 | **Спрос** - каким локальным записям нужно внешнее состояние | вы пишете запрос |
| 3 | **Получение** - по одному / пачками / постранично / асинхронное задание | вы пишете fetcher (единственное реальное различие) |
| 4 | **Согласование** - идемпотентно применить, не затирая более новое | вы пишете reconciler; модуль даёт ограждения |
| 5 | Повторы, backoff, карантин, метрики, аудит | этот модуль |

**Не для файла-источника истины** - используйте [file-ingest](../file-ingest-spring-boot-starter). `SyncTask`
управляется спросом; запись партнёра без локальной строки отбрасывается. **Коллекцию, которую перечисляет партнёр**
(задачи запроса, записи источника), зеркалирует [задача перечисления](#задачи-перечисления), создающая строки для
новых элементов.

## Использование

```xml
<dependency>
    <groupId>ru.ludwigandreas</groupId>
    <artifactId>reconciliation-spring-boot-starter</artifactId>
</dependency>
```

```java
@ReconciliationTask("billing-status")
public class BillingStatusTask implements SyncTask<Order, String, BillingRecord> {

    private final OrderRepository orders;
    private final BillingClient billing;          // интерфейс @LudwigRestClient

    @Override public String name() { return "billing-status"; }

    @Override public DemandProvider<Order, String> demand() {
        return new DemandProvider<>() {
            @Override public List<Order> demand(DemandRequest request) {
                return orders.findByStatusIn(NOT_SETTLED, request.maxRecords());
            }
            @Override public Optional<Order> byKey(String key) {
                return orders.findByBillingId(key);
            }
        };
    }

    @Override public Function<Order, String> localKey() { return Order::getBillingId; }
    @Override public KeyCodec<String> keyCodec()        { return KeyCodec.ofString(); }
    @Override public Class<BillingRecord> externalType() { return BillingRecord.class; }

    @Override public Fetcher<String, BillingRecord> fetcher() {
        return (Fetcher.Batched<String, BillingRecord>) billing::statusesFor;
    }

    @Override public ExternalStamp stampOf(BillingRecord record) {
        return ExternalStamp.ofTimestamp(record.changedAt());
    }

    @Override public Reconciler<Order, BillingRecord> reconciler() {
        return (order, record, context) -> {
            if (record.status().equals(order.getStatus())) {
                return ReconcileResult.unchanged();
            }
            order.setStatus(record.status());
            orders.save(order);
            return ReconcileResult.applied("status -> " + record.status());
        };
    }
}
```

```yaml
ludwig:
  reconciliation:
    tasks:
      billing-status:
        fetch:    { shape: batched, batch-size: 200, max-concurrency: 4 }
        schedule: { fixed-delay: 30s, initial-delay: 10s, run-timeout: 5m }
        demand:   { max-records-per-run: 5000, freshness-target: 2m }
        rest-client: billing
```

Ни `@EnableScheduling`, ни библиотеки блокировок, ни цикла повторов.

### Формы получения

```
Demand(Set<K>) ──┬─ PerItem   → параллельное разветвление, ограниченное max-concurrency
                 ├─ Batched   → chunk(batch-size) → разветвление
                 ├─ Paged     → обход страниц, контрольная точка курсора, соединение со спросом
                 └─ JobFetcher→ submit / poll / collect через несколько тиков
                              ↓
                    Stream<FetchOutcome<K,O>>    // Found | NotFound | Failed
                              ↓
                    staging (по умолчанию) или прямое применение, по элементу, по транзакции
```

`Fetcher` - sealed: расширение - выбором формы. **`NotFound` - не ошибка** (обычно постоянное состояние); пакетный
fetcher сообщает о нём, не включая ключ в возвращаемую карту.

| `not-found` | Эффект |
|---|---|
| `ignore` (по умолчанию) | Учитывается в `reconciliation.records.not_found`, ничего не пишется |
| `mark-missing` | Ставится строка без payload; выполняется `Reconciler.reconcileMissing` |
| `fail` | Повторяемая ошибка - только для партнёров, отвечающих 404 на «ещё не готово» |

### Применение через staging (по умолчанию)

`sync_inbox_record` (одна таблица, ключ `task_name`) хранит ответ партнёра; проход применения захватывает строки через
`FOR UPDATE SKIP LOCKED` и применяет каждую **в своей транзакции**. Это даёт повтор по элементу без повторного вызова
партнёра, воспроизводимую запись ответа партнёра, dead-letter по записи и независимый от получения ритм применения.

| Вид строки | Пишется, когда | Потребляется |
|---|---|---|
| `RECORD` | партнёр вернул состояние | проходом применения |
| `MISSING` | ключ неизвестен, `mark-missing` | проходом применения (`reconcileMissing`) |
| `FETCH_FAILURE` | вызов упал | проходом **получения** как билет backoff (поэтому падающий ключ откладывается и уходит в карантин, а не запрашивается вечно) |

`mode: direct` применяет сразу без строки: дешевле и хуже при любом отказе (повторы снова идут к партнёру; нет
короткого замыкания по идемпотентности и защиты от устаревшей записи - reconciler сам проверяет порядок).

### Ограждения

| Проблема | Механизм |
|---|---|
| Двойной опрос в HA | Аренда `RunLock` из job-core на задачу |
| Пересекающиеся запуски | Защита от повторного входа, `run-timeout` как TTL аренды, реклеймер зависших записей |
| **Устаревшие записи** | Внешнее состояние старше (по метке партнёра) уже применённого для ключа отвергается. Токены версий сравниваются только на равенство, не на порядок |
| Идемпотентное применение | Хэш payload, нормализованного сортировкой ключей объектов (порядок массивов сохраняется); тот же хэш → `Unchanged` без записи, без изменения `updated_at`, без строки аудита |
| Изоляция по элементу | Одна транзакция на запись в отдельном бине |
| «Ядовитые» записи | Попытки, экспоненциальный backoff с jitter, `QUARANTINED`, переочередь оператором |
| Дельта-опрос | Курсор и watermark `sync_task_state` по задаче и уровню; задачи `incremental` получают `updated-since` |
| Уровни приоритета | Проход `hot` и обход `cold` с разными расписаниями |
| Противодавление | `max-concurrency`, квоты и rate limit партнёров, bulkhead REST-клиента |
| Остановка | Дренаж `SmartLifecycle` |

### Квоты и rate limit (на партнёра)

```yaml
ludwig:
  reconciliation:
    quotas:
      partner-exports:
        max-concurrent: 5
        lease-ttl: 15m
        heartbeat-interval: 2m
        acquire-timeout: 0s
        fifo: true
        reclaim: verify-remote
        max-lifetime: 6h
    rate-limits:
      partner-api: { permits: 100, per: 1m }
```

**Квота** - кластерный арендуемый счётный семафор (`sync_quota_lease`, `sync_quota_waiter`), захватываемый под
транзакционной advisory-блокировкой PostgreSQL. Правила: **heartbeat, никогда не доверять живости** (падение не должно
навсегда уменьшать квоту); **проверка перед перехватом** (`verify-remote` сначала опрашивает задание; `on-expiry` может
превысить лимит партнёра); **сначала аренда квоты, потом разрешение rate limit**. Квоты **справедливы по FIFO**
(быстрая задача не заморит медленную). `acquire-timeout: 0s` возвращает управление сразу. **Rate limit - на
экземпляр**: делите скорость партнёра на число реплик.

### Асинхронные задания партнёра

```
PENDING_SUBMIT → SUBMITTED → RUNNING → SUCCEEDED → COLLECTING → COLLECTED
       └────────────────────────────────────────────────────────────→ FAILED | EXPIRED | CANCELLED
       └──→ ORPHANED
```

```yaml
partner-bulk-status:
  fetch: { shape: async-job, submit-batch-size: 500 }
  quota: partner-exports
  rate-limit: partner-api
  rest-client: partner
  job:
    submit:  { fixed-delay: 1m, max-concurrent-submits: 2 }
    poll:    { fixed-delay: 30s, backoff-multiplier: 1.5, max-interval: 5m, honour-retry-after: true }
    collect: { fixed-delay: 1m, max-concurrency: 1 }
    max-lifetime: 6h
    on-ambiguous-submit: assume-submitted
```

- Три планировщика (submit, poll, collect) со своим ритмом и параллелизмом.
- **Порядок записи фиксирован**: вставить `sync_remote_job` как `PENDING_SUBMIT` с ключом идемпотентности и взять
  аренду квоты (коммит) → submit → записать `SUBMITTED` и handle (коммит). Падение после submit оставляет улику, а не
  дубликат.
- **Неоднозначный submit** (`PENDING_SUBMIT` дольше grace period): если fetcher реализует `listActive()`, принять
  задание с ключом идемпотентности этой строки; иначе применить `on-ambiguous-submit` - `assume-submitted` (пометить
  `ORPHANED`, держать слот до `max-lifetime`, никогда не переотправлять; для всего платного) или `resubmit` (только
  дешёвые идемпотентные отправки). Обязателен для `async-job`.
- **Принять, а не перезапустить**: после рестарта следующий поллер принимает задание и продолжает heartbeat
  существующей аренды.
- **Истечение**: после `max-lifetime` задание отменяется у партнёра, где возможно, помечается `EXPIRED`, аренда
  освобождается.

Отображение на `OperationStatus` web-core (у модуля нет REST-поверхности и зависимости от web-core):
`PENDING_SUBMIT`→`PENDING`; `SUBMITTED`/`RUNNING`/`SUCCEEDED`/`COLLECTING`→`RUNNING` («успех» партнёра - не
«результат доступен»); `COLLECTED`→`SUCCEEDED`; `FAILED`, `EXPIRED`, `CANCELLED` - сами в себя; `ORPHANED`→`FAILED` с
`ORPHANED` в `detail`.

### Задачи перечисления

Зеркалируют *всё, что партнёр перечисляет в области* - включая элементы без локальной строки и переставшие
перечисляться.

| | `SyncTask` | `EnumerationTask` |
|---|---|---|
| Начинается с | локальных записей, которым нужно состояние | локальной строки **области** и перечня партнёра |
| Запись партнёра без локальной строки | отбрасывается | передаётся вам для создания |
| Состояние страниц | один курсор на задачу и уровень | курсор, watermark и поколение **на область** |
| «Исчезло у партнёра» | `NotFound` для запрошенного ключа | отсутствие в завершённом полном обходе |

```java
@ReconciliationEnumeration("jira-issues")
public class JiraIssuesTask implements EnumerationTask<SavedSearch, UUID, JiraIssue, String> {

    @Override public String name() { return "jira-issues"; }

    @Override public ScopeSource<SavedSearch, UUID> scopes() {
        return new ScopeSource<>() {
            @Override public List<SavedSearch> page(UUID after, int limit) { return searches.pageAfter(after, limit); }
            @Override public Optional<SavedSearch> byKey(UUID key)        { return searches.findById(key); }
            @Override public UUID keyOf(SavedSearch search)               { return search.getId(); }
        };
    }
    @Override public KeyCodec<UUID> scopeKeyCodec()          { return KeyCodec.ofUuid(); }
    @Override public Function<JiraIssue, String> memberKey() { return JiraIssue::key; }
    @Override public KeyCodec<String> memberKeyCodec()       { return KeyCodec.ofString(); }

    @Override public MemberFeed<SavedSearch, JiraIssue> feed() {
        return new MemberFeed<>() {
            @Override public PageResult<JiraIssue> fetchPage(SavedSearch search, String cursor, FeedRequest request) {
                return jira.search(search.jql(), request.changedSinceOrEmpty(), cursor, request.pageSize());
            }
            @Override public boolean supportsChangedSince() { return true; }
        };
    }

    @Override public ExternalStamp stampOf(JiraIssue issue) { return ExternalStamp.ofTimestamp(issue.updated()); }

    @Override public MemberReconciler<SavedSearch, JiraIssue, String> reconciler() {
        return new MemberReconciler<>() {
            @Override public MemberResult apply(SavedSearch search, JiraIssue issue, MemberContext context) {
                issues.upsertAndLink(issue, search);          // искать по ключу ВКЛЮЧАЯ удалённые строки
                return MemberResult.applied("synced " + issue.key());
            }
            @Override public LeftResult left(SavedSearch search, String issueKey, MemberContext context) {
                issues.unlink(issueKey, search);              // ваше решение: отвязать, пометить, удалить
                return LeftResult.HANDLED;
            }
        };
    }
}
```

```yaml
ludwig:
  reconciliation:
    enumerations:
      jira-issues:
        schedule:  { fixed-delay: 1m, run-timeout: 15m }
        discovery: { fixed-delay: 5m }
        refresh:   { full-interval: 6h, incremental-interval: 5m }
        walk:      { page-size: 100, max-concurrency: 2, max-scopes-per-run: 50 }
        sweep:     { confirm-after-misses: 1, max-fraction: 0.5 }
        rate-limit: jira
        rest-client: jira
```

- **discover** регистрирует новые области; область, чья локальная строка исчезла, забывается вместе с членством (без
  вызовов `left` - это ваш каскад).
- **walk** берёт области, которым пора (никогда не обходившиеся - первыми), от их контрольной точки. **Полный** при
  первом обходе, после `refresh.full-interval` или если перечень не фильтруется по изменениям; иначе **инкрементальный**
  от watermark области.
- **Страница - это транзакция** (членство, ваши записи, курсор). Упавшая страница переигрывается по элементам;
  падающий элемент откладывается и уходит в карантин; обход продолжается.
- **Неизменённые элементы не доходят до вашего кода** (`sync_scope_member` хранит последний хэш payload).
- **Удаление сообщается, а не выполняется**: после завершённого полного обхода неперечисленные элементы один раз уходят
  в `left(...)`, и движок удаляет только свою строку членства. Вернувшийся элемент приходит с
  `context.isNew() == true` - ваш `apply` должен находить и мягко удалённые строки (`@SQLRestriction("deleted = false")`
  их скрывает; используйте обычную колонку-флаг или поиск, видящий их). Необработанный `left` учитывается
  (`members.left_unhandled`).
- **Три защиты удаления**: доказательство (только завершённый полный обход без упавших элементов), правдоподобие
  (удаление больше `sweep.max-fraction` откладывается до оператора), дрейф (`confirm-after-misses` подряд). Область из
  одного элемента всегда выше доли по умолчанию; для крошечных областей - `max-fraction: 1` (и без защиты от сбоя).

## Конфигурация

Префикс `ludwig.reconciliation`. **Приоритет по полям**: задача → `defaults` → встроенное значение (реализовано в
`TaskSettingsResolver`, потому что связывание Spring блоки не сливает). Cron и fixed-delay наследуются парой.
Перечисления так же используют `enumeration-defaults`.

**Задачи** (`tasks.<name>.*` или `defaults.*`):

| Свойство | По умолчанию | Смысл |
|---|---|---|
| `enabled` | `true` | Выключенные задачи всё равно проверяются |
| `mode` | `staged` | `staged` / `direct` |
| `not-found` | `ignore` | `ignore` / `mark-missing` / `fail` |
| `fetch.shape` | - | **Обязательно**; должно совпадать с интерфейсом fetcher-а |
| `fetch.batch-size` | `100` | `batched` |
| `fetch.page-size` | `500` | `paged` и сбор заданий |
| `fetch.max-concurrency` | `4` | |
| `fetch.checkpoint` | `true` | Сохранять курсор страниц |
| `fetch.submit-batch-size` | `500` | Ключей на отправляемое задание |
| `schedule.fixed-delay` \| `schedule.cron` | `1m` | Проход hot |
| `schedule.initial-delay` | = fixed-delay | |
| `schedule.run-timeout` | `15m` | TTL аренды запуска; должен превышать бюджет повторов |
| `cold-schedule.*` | нет | Обход cold (нет = без уровней) |
| `demand.max-records-per-run` | `5000` | |
| `demand.freshness-target` | `15m` | Знаменатель gauge свежести |
| `demand.incremental` | `false` | Передавать watermark как `updated-since` |
| `apply.fixed-delay` / `batch-size` / `drain-timeout` | `10s` / `200` / `20s` | Проход применения |
| `retry.max-attempts` / `initial-interval` / `multiplier` / `max-interval` / `jitter` | `8` / `2s` / `2.0` / `10m` / `0.3` | |
| `audit.enabled` | `true` | |
| `rest-client` / `quota` / `rate-limit` | нет | Именованные ресурсы, проверяются на существование |
| `job.*` | - | Планировщики заданий, `max-lifetime`, `on-ambiguous-submit` (обязателен для `async-job`) |

**Перечисления** (`enumerations.<name>.*` или `enumeration-defaults.*`): `enabled` (`true`), `schedule.fixed-delay` |
`schedule.cron` (`1m`), `schedule.run-timeout` (`15m`), `discovery.fixed-delay` (`5m`), `discovery.page-size`
(`1000`), `refresh.full-interval` (`6h` - дольше всего удаление может остаться незамеченным),
`refresh.incremental-interval` (нет; требует `supportsChangedSince()`), `walk.page-size` (`100`),
`walk.max-concurrency` (`2`), `walk.max-scopes-per-run` (`50`), `sweep.confirm-after-misses` (`1`),
`sweep.max-fraction` (`0.5`), `retry.*`, `audit.enabled`, `rest-client`, `rate-limit`.

**Верхний уровень**: `ludwig.reconciliation.enabled`, `quotas.*`, `rate-limits.*`, `liquibase.enabled` (`true`).

`tasks.<name>.audit.persist` всё ещё связывается, но ни на что не влияет (след идёт через платформенный `AuditSink`).

| Перечитывается (следующий проход) | Нужен перезапуск |
|---|---|
| `retry.*`, `demand.max-records-per-run`, `demand.freshness-target`, `fetch.batch-size`, `fetch.page-size`, `fetch.max-concurrency`, `not-found`, `apply.batch-size`, `rate-limits.*`; у перечислений `walk`, `sweep`, `refresh`, `retry` | `fetch.shape`, `schedule.*`, `cold-schedule.*`, `apply.fixed-delay`, `mode`, `quotas.*`, у перечислений `schedule.*`, `discovery.fixed-delay`, добавление/удаление задач, перечислений, квот и rate limit |

**Старт отвергает** (всё сразу, каждое сообщение говорит, что сломается): неизвестные квота/rate limit/REST-клиент;
`async-job` без блока `job` или `on-ambiguous-submit`; `run-timeout` короче бюджета повторов; `lease-ttl` < 2 ×
`heartbeat-interval` или `max-lifetime` < `lease-ttl`; бин без конфигурации и наоборот; несовпадение аннотации и
`name()` или повтор имён; fetcher, противоречащий `fetch.shape`; имя перечисления, совпадающее с задачей;
инкрементальное обновление без фильтрации по изменениям; `sweep.max-fraction` вне (0, 1] или
`confirm-after-misses` < 1.

## Развёртывание

**Схема (PostgreSQL)**: `db/changelog/reconciliation/reconciliation-changelog.xml` (собственный бин `SpringLiquibase`;
`ludwig.reconciliation.liquibase.enabled=false`, чтобы подключить самим). Частичные индексы держат запросы захвата
пропорциональными бэклогу.

| Таблица | Хранит |
|---|---|
| `sync_inbox_record` | Записи staging, маркеры отсутствия, билеты ошибок получения |
| `sync_task_state` | Курсор и watermark по задаче и уровню |
| `sync_remote_job` | Асинхронные задания |
| `sync_scope_state` | По области перечисления: курсор, watermark, поколение, срок |
| `sync_scope_member` | Элементы областей, последний обход, хэш payload |
| `sync_quota_lease` / `sync_quota_waiter` | Слоты квот и FIFO-очередь |
| `sync_audit_record` | Создаётся changelog-ом; не пишется (след - `audit_event`) |
| `job_run_lock` | Из job-core: аренды запусков задач |

**Администрирование**: выдайте `reconciliation.*` операторским ролям сервиса в `ludwig.admin.grants`;
`reconciliation.privileged` - только там, где разрушительные действия действительно нужны. **Партнёры**: `@LudwigRestClient` на каждый fetcher; rate limit ÷ реплики.

## Наблюдаемость

Префикс метрик `reconciliation.` (теги `task`, и `quota`, где уместно).

| Метрика | Тип | Смысл |
|---|---|---|
| `reconciliation.freshness.lag` | gauge | **SLO-метрика**: секунды сверх `freshness-target` задачи (худшее из возраста самой старой записи в конвейере и времени с последнего завершённого запуска) |
| `reconciliation.run` | timer | Длительность запуска, `tier`, `outcome` |
| `reconciliation.records.fetched` | counter | Записи, возвращённые партнёром |
| `reconciliation.records.settled` | counter | `outcome`: applied, unchanged, rejected, deferred, missing, failed, quarantined |
| `reconciliation.records.not_found` | counter | `policy` |
| `reconciliation.records.quarantined` | gauge | Глубина карантина |
| `reconciliation.staged.depth` / `staged.oldest.age` | gauge | Бэклог применения |
| `reconciliation.fetch.duration` / `apply.duration` | timer | На вызов (`shape`) / на запись |
| `reconciliation.quota.in_flight` / `limit` / `saturation` | gauge | Занятость слотов |
| `reconciliation.quota.oldest_waiter.age` | gauge | Верно ли размерена квота |
| `reconciliation.quota.acquire.wait` | timer | `acquired` |
| `reconciliation.quota.lease.reclaimed` | counter | `policy`. **Алерт на устойчивое ненулевое значение** |
| `reconciliation.job.duration` / `job.polls` / `job.settled` | timer / summary / counter | Терминальное состояние |
| `reconciliation.job.ambiguous_submit` | counter | `adopted`, `orphaned`, `resubmitted` |
| `reconciliation.scope.freshness.lag` | gauge | **SLO перечислений**: самая просроченная область (от последнего перечисления) |
| `reconciliation.scope.walk` | timer | `kind`, `outcome` |
| `reconciliation.members.settled` | counter | `outcome`, включая `left` |
| `reconciliation.members.left_unhandled` | counter | Удаления, которые никто не обрабатывает |
| `reconciliation.members.quarantined` | gauge | |
| `reconciliation.sweep.held` | gauge | Отложенные удаления. **Алерт на устойчивое ненулевое значение** |

Gauge вычисляются из supplier-ов (остановившаяся задача не может продолжать сообщать здоровое значение). У каждого
запуска есть run id на строках staging и событиях аудита и correlation id, который уходит в исходящие вызовы.

**Рекомендуемые алерты:** `reconciliation_freshness_lag > 0` дольше, чем допускает задача;
`reconciliation_scope_freshness_lag > 0` аналогично; рост `reconciliation_quota_lease_reclaimed_total`;
`reconciliation_sweep_held > 0` в течение часа; `reconciliation_records_quarantined` больше нуля и растёт.

## Аудит

Категория `reconciliation`; ресурс `sync-task` / имя задачи; вид события (`RUN`, `RECORD`, `JOB`, `LEASE`,
`OPERATOR`) - атрибут. Записывается: начало/окончание/ошибка запуска, переходы исходов записей, переходы заданий,
захват/освобождение/перехват аренд, обходы перечислений, уход элементов, карантин, отложенные удаления и **каждое
действие оператора с вызывающим принципалом**. Исход выводится из имени события (`.failed`, `.timed-out`,
`.reclaimed` → `FAILURE`; `.quarantined`, `.skipped` → `PARTIAL`).

## Администрирование

Эндпоинты оператора находятся на административной поверхности платформы, `/admin/v1/{area}`, и существуют только там, где есть поверхность `security-spring-boot-starter`. Каждый защищён одной операцией; какие роли сервиса ею владеют, решает собственный `ludwig.admin.grants` сервиса (роли из резолвера полномочий, никогда не из claims токена), а операция, не выданная ни одной роли, запрещена всем. Вызов уровня `privileged` требует заголовка `Admin-Reason`, и каждый вызов записывается в аудит с категорией `admin` и идентификатором операции в качестве действия. `GET /admin/v1/me/operations` показывает, какие операции есть у вызывающего.

| Операция | Эндпоинты | Что разрешает |
|---|---|---|
| `reconciliation.read` | `GET /admin/v1/reconciliation/tasks`<br>`GET /admin/v1/reconciliation/tasks/{task}`<br>`GET /admin/v1/reconciliation/tasks/{task}/scopes/{scope}` | Просмотр задач сверки, перечислений, областей, квот и того, что застряло. |
| `reconciliation.operate` | `POST /admin/v1/reconciliation/tasks/{task}/runs`<br>`POST /admin/v1/reconciliation/tasks/{task}/backfills`<br>`POST /admin/v1/reconciliation/tasks/{task}/cursor/reset`<br>`POST /admin/v1/reconciliation/tasks/{task}/quarantine/requeue`<br>`POST /admin/v1/reconciliation/tasks/{task}/remotejobs/{jobId}/requeue`<br>`POST /admin/v1/reconciliation/tasks/{task}/discover`<br>`POST /admin/v1/reconciliation/tasks/{task}/members/requeue`<br>`POST /admin/v1/reconciliation/tasks/{task}/scopes/{scope}/walk`<br>`POST /admin/v1/reconciliation/tasks/{task}/scopes/{scope}/preview`<br>`POST /admin/v1/reconciliation/tasks/{task}/scopes/{scope}/reset`<br>`POST /admin/v1/reconciliation/tasks/{task}/scopes/{scope}/sweep/discard` | Запуск, дозагрузка, повторная постановка и сброс задач сверки и областей перечислений. |
| `reconciliation.privileged` | `POST /admin/v1/reconciliation/tasks/{task}/remotejobs/{jobId}/cancel`<br>`POST /admin/v1/reconciliation/leases/{leaseId}/release`<br>`POST /admin/v1/reconciliation/tasks/{task}/scopes/{scope}/sweep/confirm` | Отмена удалённого задания, освобождение аренды квоты или подтверждение удержанной зачистки - действия, после которых эта система и партнёр могут разойтись. |

**Удалено:** actuator-эндпоинт `reconciliation` и `ludwig.reconciliation.endpoint.*`, включая `allow-destructive-operations` и параметр `confirm`. Разрешены ли разрушающие действия, теперь определяется тем, выдана ли какой-либо роли сервиса операция `reconciliation.privileged`; `reconciliation` в списке экспозиции actuator ничего не открывает.

Блок выдачи (имена ролей - собственные роли сервиса):

```yaml
ludwig:
  admin:
    grants:
      "[reconciliation.read]": [ORDERS_ADMIN, ORDERS_SUPPORT]
      "[reconciliation.operate]": [ORDERS_ADMIN]
      "[reconciliation.privileged]": [ORDERS_ADMIN]
```

## Runbook

Действия оператора, все под `/admin/v1/reconciliation` (операции - в разделе [Администрирование](#администрирование)):

| Действие | Эндпоинт | Эффект |
|---|---|---|
| запуск | `POST /tasks/{task}/runs` `{tier}` | Выполнить проход сейчас; `409`, если уже идёт |
| пробный запуск | `POST /tasks/{task}/runs` `{tier, dryRun: true}` | Получить и отчитаться по исходам, **ничего не записывая** |
| backfill | `POST /tasks/{task}/backfills` `{keys}` | Получить указанные ключи в обход спроса; ограждения сохраняются; watermark не сдвигается |
| сброс курсора | `POST /tasks/{task}/cursor/reset` `{tier}` | Сбросить курсор и watermark |
| возврат карантина | `POST /tasks/{task}/quarantine/requeue` | Вернуть в очередь со **сброшенными** счётчиками попыток |
| возврат задания | `POST /tasks/{task}/remotejobs/{jobId}/requeue` | Вернуть в очередь собранные заданием записи |
| отмена задания | `POST /tasks/{task}/remotejobs/{jobId}/cancel` | **Privileged**: завершить задание; обслуживание отменит у партнёра и освободит слот |
| освобождение аренды | `POST /leases/{leaseId}/release` | **Privileged**: принудительно освободить слот квоты (может превысить лимит партнёра) |

Перечисления: `POST /tasks/{enumeration}/discover`, `POST /tasks/{enumeration}/members/requeue`, а по области
`POST /tasks/{enumeration}/scopes/{scope}/walk` (`{kind}`), `.../preview` (ничего не пишет), `.../reset`,
`.../sweep/discard`, `.../sweep/confirm` (**privileged**). Ключи областей со `/` кодируйте процентами. Действию уровня
privileged нужен заголовок `Admin-Reason`.

| Ситуация | Что делать |
|---|---|
| Партнёр недоступен | Ничего не теряется; ошибки откладываются. Следить за `freshness.lag`. После восстановления - `requeue-quarantined` для ключей, исчерпавших бюджет. Не перезапускать поды (состояние backoff в БД) |
| У квоты ноль свободных слотов | Смотреть держателей в обзоре. С `jobId`: задания идут - сравнить возраст с `max-lifetime`. Без них или с мёртвыми владельцами: перехват не работает или не может проверить (лог `could not verify lease`). `release-lease` - крайняя мера |
| Задание `ORPHANED` | Спросить партнёра, выполнялось ли оно. Нет: `cancel-job`. Да: оставить; `max-lifetime` его завершит |
| Записи в карантине | `GET .../{task}` показывает образцы с `lastError`. Карантин `FETCH_FAILURE` = вызов постоянно падал; `RECORD` = reconciler постоянно бросал. Исправить, затем `requeue-quarantined` |
| `freshness.lag` растёт, ошибок нет | Запрос спроса перестал совпадать: `records.fetched` ноль при успешных запусках |
| Удаление отложено (`sweep.held`) | Посмотреть область. Реальное удаление: `confirm-sweep`. Сбой/права: `discard-sweep` и исправить; следующий обход будет полным. Применение тем временем продолжается |
| Элементы в карантине | Образцы в `GET .../{enumeration}`; исправить; `requeue-members` (затронутые области получат полный обход) |

## Тестирование

Модульные (связывание и приоритет по полям, каждый случай валидатора, backoff и jitter, нормализация payload, отказ
устаревшим меткам, политики `NotFound`, нарезка спроса, обход курсора, планирование обходов), ArchUnit (SPI не зависит
от реализации, нигде нет `@Scheduled`) и интеграционные на Testcontainers PostgreSQL (схема против JPA с `validate`;
ровно один запуск под арендой; изоляция по элементу; повтор → карантин → переочередь; продолжение по страницам;
монотонность watermark; квоты на двух экземплярах; heartbeat; убитый держатель аренды, когда `verify-remote` не
освобождает слот работающего задания; FIFO-справедливость; падение между submit и commit без повторной отправки;
принятие через `listActive()`; истечение с удалённой отменой; у перечислений - первый обход, короткое замыкание по
неизменности, инкрементальный и полный обходы, элементы нескольких областей, прерванные обходы, карантин элементов, все
три защиты удаления, деревья, действия оператора).

### Перенос самодельного цикла

1. `findBy…` становится `DemandProvider.demand`, плюс перезагрузка `byKey`.
2. Вызов клиента становится `Fetcher` (`Batched`, если у партнёра есть пакетный эндпоинт, иначе `PerItem`).
3. Тело применения становится `Reconciler`; возвращайте `Unchanged`, если ничего не изменилось.
4. Удалите `try/catch`, `@Scheduled` и `@SchedulerLock`.
5. Реализуйте `stampOf`, если партнёр публикует метку изменения (превращает ответы не по порядку в отвергнутые записи).
6. Объявите `freshness-target`.

## Проектные решения

- **Защита от устаревшей записи в движке**, а не в каждом reconciler-е: эта ошибка не даёт ни исключения, ни лога, ни
  метрики.
- **Квоты кластерные**, rate limit - на экземпляр (обращение к БД на каждый вызов стоило бы больше, чем экономит).
- **Фиксированный порядок записи асинхронных заданий**; строка `PENDING_SUBMIT` - улика, делающая восстановление
  возможным.
- **Удаление сообщается, но не выполняется** над вашими таблицами, за тремя защитами.
- **Всё - `@ConditionalOnMissingBean` по умолчанию** (`ReconciliationMetrics`, `PayloadCodec`, `DemandKeys`,
  `CorrelationIdSource`, `DatabaseQuota`, `RateLimitRegistry`, сервисы движка); добавление интеграции никогда не правит
  этот стартер.
