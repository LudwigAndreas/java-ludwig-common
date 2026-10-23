# idempotency-spring-boot-starter

*[English](README.md) · **Русский***

Хранилище дедупликации работы платформы: захватывает ключ, переданный вызывающим, в рамках scope, чтобы
один и тот же запрос нельзя было выполнить дважды. Поверх хранилища: HTTP-фильтр, который **повторяет
исходный ответ** на повторный запрос, отпечаток запроса, чтобы переиспользованный ключ отклонялся, а не
отвечал чужим ответом, фильтр Kafka-записей, захватывающий ключ внутри транзакции listener-а, и пакетная
очистка по сроку хранения.

| | |
|---|---|
| Роль | starter |
| Зависит от (в репозитории) | [`audit-core`](../audit-core), [`db-core`](../db-core), [`job-core`](../job-core), [`web-core-spring-boot-starter`](../web-core-spring-boot-starter) |
| Используется | стартерами `file-action`, `grpc-server`, `messaging`; `notification-service` |
| Поверхности | REST (фильтр на подключённых эндпоинтах), Kafka (фильтр записей) |

Корректность заключена в одном запросе:

```sql
INSERT INTO idempotency_claim AS t (…)
VALUES (…)
ON CONFLICT (scope, idempotency_key) DO UPDATE SET
    request_id = CASE WHEN <reclaimable> THEN EXCLUDED.request_id ELSE t.request_id END,
    …                                     -- одно присваивание такой формы на колонку
RETURNING request_id, state, fingerprint, lease_expires_at, expires_at, response_*
```

- `DO UPDATE` (а не `DO NOTHING`) заставляет проигравшего в гонке **ждать блокировку строки победителя и
  читать закоммиченного победителя**: без исключения, отката и двойного выполнения. `DO NOTHING` не вернул
  бы строку, а повторный select в `READ COMMITTED` может не увидеть незакоммиченного победителя.
  Read-then-insert откатывает транзакцию, которая уже сделала работу.
- Принуждение обеспечивает уникальное ограничение `uk_idempotency_claim_key`.
- Ветка конфликта **перезахватывает** свободный claim (окно истекло, аренда истекла или `FAILED`) в том же
  запросе, поэтому TTL точен и не зависит от того, когда последний раз работала очистка.

SQL живёт в `ru.ludwigandreas.idempotency.sql` - документированное исключение модуля из правила
«только QueryDSL» (в JPQL нет `ON CONFLICT` / `RETURNING`), огороженное `SqlConfinementTest`.

## Использование

```xml
<dependency>
    <groupId>ru.ludwigandreas</groupId>
    <artifactId>idempotency-spring-boot-starter</artifactId>
</dependency>
```

### Режимы захвата

Выбор режима обязателен в каждом месте вызова; значения по умолчанию нет.

| | `TRANSACTIONAL` | `STANDALONE` |
|---|---|---|
| транзакция | вызывающего (отказ, если её нет) | собственная |
| жизненный цикл | коммитится вместе с работой; откат освобождает ключ | `IN_PROGRESS → COMPLETED \| FAILED`, с арендой |
| дубликату сообщается | «сделано, владелец - запрос X» | «сделано, вот ответ» или «выполняется» |
| мёртвый владелец | невозможен (не закоммичено) | аренда истекает, ключ снова можно захватить |
| для | потребителя, чья единица работы - одна транзакция | HTTP-фильтра; работы через несколько транзакций или с внешними вызовами |

`STANDALONE` нужна аренда: ключ, застрявший в `IN_PROGRESS` за мёртвым процессом, навсегда блокировал бы
повтор клиента. Claim в `FAILED` можно перезахватить сразу.

### HTTP

Выключен по умолчанию (`ludwig.idempotency.http.enabled`), поэтому добавление зависимости никогда не меняет
живой API. Подключайте эндпоинт аннотацией `@Idempotent` на обработчике или через `path-patterns` для
эндпоинтов, которыми сервис не владеет. Клиент передаёт `Idempotency-Key`.

| Ключ | Ответ |
|---|---|
| новый | Обработчик выполняется; ответ сохраняется |
| завершён | Сохранённый ответ **повторяется** (статус, тело, заголовки из allow-list) плюс `Idempotency-Replayed`; обработчик не выполняется |
| выполняется | `409 ludwig.idempotency.error.in-progress` с `Retry-After` |
| переиспользован для другого запроса | `422 ludwig.idempotency.error.fingerprint-mismatch`; первый ответ **никогда** не возвращается |
| завершён, но ответ слишком велик для хранения | `409 ludwig.idempotency.error.not-replayable`, без `Retry-After` (повтор не поможет) |
| отсутствует (при `key-required`) / слишком длинный | `400 key-required` / `400 key-too-long` |

- Не сохраняются: **обрезанное** тело и любой **4xx/5xx** - они помечают claim `FAILED` и освобождают ключ,
  чтобы временный сбой не стал постоянным, а исправленный запрос не отклонялся.
- Проблемы - документы RFC 9457 через `ProblemDetailFactory` и бандлы web-core (фильтр работает вне
  обработки исключений Spring MVC, поэтому вызывает те же бины).
- `methods` по умолчанию `POST`, `PATCH`. `PUT` и `DELETE` идемпотентны по RFC 9110; повтор ответа скрыл бы
  от второго вызывающего реальное изменение состояния.
- `replayed-headers` - allow-list (`Location`, `ETag`, `Content-Language`): повтор `Set-Cookie` отдал бы
  второму вызывающему сессию первого.

**Отпечаток** не даёт переиспользованному ключу вернуть ответ другого запроса:

- хэш, а не тело (нет таблицы payload-ов, которую надо редактировать и защищать);
- JSON канонизируется (ключи отсортированы, пробелы и `charset`/`boundary` игнорируются), поэтому
  пересериализованный повтор совпадает; **порядок массивов сохраняется** (он значим по спецификации);
- claim без сохранённого отпечатка никогда не даёт расхождения.

### Kafka

`IdempotentRecordFilterStrategy` захватывает ключ до выполнения тела listener-а, в режиме `TRANSACTIONAL`,
внутри транзакции контейнера. [messaging](../messaging-spring-boot-starter/README.ru.md) подключает его ко
всем строимым фабрикам при `ludwig.idempotency.kafka.enabled=true` и передаёт менеджер транзакций. Без
messaging:

```java
factory.setRecordFilterStrategy(idempotentRecordFilterStrategy);
factory.getContainerProperties().setTransactionManager(transactionManager);
```

- Ключ по умолчанию - координаты записи `topic:partition:offset` (идентифицирует доставку). Задайте
  `key-header`, если продюсеры переопубликуют одно логическое событие с новым offset; заголовок
  платформенного продюсера - `ludwig-idempotency-key`.
- Никогда не ключ сообщения записи - он группирует связанные записи и свёл бы все события одной сущности к
  первому.
- Не ставьте дедупликацию перед сходящейся проекцией (last-write-wins); она и так корректна при повторе.

### Передача ключа дальше

`IdempotencyHeaders.KEY` (`Idempotency-Key`) - имя заголовка при вызове другого сервиса, чтобы та сторона
могла дедуплицировать.

## Конфигурация

| Свойство | По умолчанию | Смысл |
|---|---|---|
| `ludwig.idempotency.enabled` | `true` | Главный выключатель |
| `ludwig.idempotency.backend` | `postgres` | `postgres` или `redis` (см. [Бэкенды](#бэкенды)) |
| `ludwig.idempotency.ttl` | `24h` | **Параметр корректности**: окно, в котором повтор распознаётся. Его сокращение превращает дубликаты в двойные выполнения |
| `ludwig.idempotency.lease` | `60s` | Сколько живёт выполняющийся standalone-claim без продления |
| `ludwig.idempotency.scopes[].scope` / `.ttl` | - | TTL по scope (например, Kafka-топику нужно больше его retention) |
| `ludwig.idempotency.http.enabled` | `false` | Включить HTTP-фильтр |
| `ludwig.idempotency.http.path-patterns` | `[]` | Дополнительные эндпоинты; пусто = только обработчики `@Idempotent` |
| `ludwig.idempotency.http.methods` | `POST`, `PATCH` | Методы, к которым применяется фильтр |
| `ludwig.idempotency.http.key-required` | `false` | Отклонять запросы без ключа |
| `ludwig.idempotency.http.scope-per-endpoint` | `true` | Один ключ на двух эндпоинтах - два claim-а |
| `ludwig.idempotency.http.fingerprint-requests` | `true` | Снимать отпечаток тела запроса |
| `ludwig.idempotency.http.max-fingerprinted-body` | `262144` | Сколько байт хэшируется |
| `ludwig.idempotency.http.max-stored-response` | `262144` | Большие ответы не сохраняются (→ `not-replayable`) |
| `ludwig.idempotency.http.replayed-headers` | `Location`, `ETag`, `Content-Language` | Allow-list повторяемых заголовков |
| `ludwig.idempotency.http.min-stored-status` / `max-stored-status` | `200` / `299` | Статусы, сохраняемые для повтора |
| `ludwig.idempotency.kafka.enabled` | `false` | Включить фильтр записей |
| `ludwig.idempotency.kafka.mode` | `TRANSACTIONAL` | Режим захвата для записей |
| `ludwig.idempotency.kafka.key-header` | не задан | Заголовок-ключ; не задан = координаты записи |
| `ludwig.idempotency.kafka.fingerprint-records` | `true` | Отклонять переиспользованный ключ с другим payload |
| `ludwig.idempotency.purge.enabled` | `true` | Запускать очистку |
| `ludwig.idempotency.purge.interval` | `15m` | Интервал очистки |
| `ludwig.idempotency.purge.initial-delay` | `5m` | Задержка перед первой очисткой |
| `ludwig.idempotency.purge.batch-size` | `1000` | Строк в пачке |
| `ludwig.idempotency.purge.max-batches-per-run` | `100` | Пачек за запуск |
| `ludwig.idempotency.purge.lock-name` | `ludwig-idempotency-purge` | Имя `RunLock` |
| `ludwig.idempotency.redis.key-prefix` | `ludwig:idempotency` | Префикс ключей Redis |
| `ludwig.idempotency.liquibase.enabled` | `true` | Применять changelog собственным бином |

### Бэкенды

| | PostgreSQL (по умолчанию) | Redis |
|---|---|---|
| `TRANSACTIONAL` | да | **нет** (исключение) |
| `STANDALONE` | да | да |
| очистка | задача этого модуля | истечение ключей Redis |
| перезахват брошенного claim-а | взаимоисключающий (блокировка строки) | best-effort (последний писатель выигрывает) |

Claim в Redis коммитится в другой системе, чем защищаемая работа; падение между ними оставляет ключ
захваченным для несделанной работы, в пределах аренды. Допустимо для HTTP-фильтра перед идемпотентным
обработчиком; **недопустимо для Kafka-потребителей**.

## Развёртывание

**Схема (PostgreSQL).** `db/changelog/idempotency/idempotency-changelog.xml`:

- `idempotency-0001` создаёт `idempotency_claim` (`scope` и `idempotency_key` уникальны вместе, `request_id`,
  `state`, `fingerprint`, `lease_expires_at`, `failure_reason`, `response_*`, `created_at`, `expires_at`) с
  индексом по `expires_at`.
- `idempotency-0002` копирует строки из старой таблицы `notification_idempotency`, если она есть (состояние
  `COMPLETED`, исходные `created_at`/`expires_at`, без отпечатка), пропуская ключи, уже захваченные в новой
  таблице. Это `runAlways` с предусловием `tableExists`, поэтому в других местах он ничего не делает.

**Redis** (только при `backend=redis`): доступный Redis через `spring.data.redis.*`.

**Очистка** работает на одной реплике под `RunLock` `ludwig-idempotency-purge` (нужна `job_run_lock` из
[`job-core`](../job-core)).

## Наблюдаемость

**Метрики:**

| Метрика | Тип | Теги | Смысл |
|---|---|---|---|
| `ludwig.idempotency.claims` | counter | `scope`, `outcome` (`CLAIMED`, `IN_PROGRESS`, `COMPLETED`) | Попытки захвата |
| `ludwig.idempotency.replays` | counter | `scope` | Повторённые сохранённые ответы |
| `ludwig.idempotency.claims.failed` | counter | `scope` | Claim-ы, освобождённые после неудачи |
| `ludwig.idempotency.fingerprint.mismatches` | counter | `scope` | **Клиент переиспользует один ключ для разных запросов** |
| `ludwig.idempotency.purged` | counter | - | Удалённые истёкшие claim-ы |

Тег только `scope`; ключ, id запроса и вызывающий не ограничены и тегами никогда не являются.

**Логи.** `WARN` для переиспользованного ключа (HTTP и Kafka), потерянной блокировки очистки, очистки,
оставившей истёкшие claim-ы («consider raising» размер пачки или частоту), нечитаемых сохранённых
заголовков, claim-а, который больше не удерживается при завершении. `INFO` для отброшенной повторной
доставки Kafka и итогов очистки.

**Рекомендуемые алерты:**

| Условие | Почему |
|---|---|
| `increase(ludwig_idempotency_fingerprint_mismatches_total[1h]) > 0` | Клиент сломан; он видит редкие 422, о которых никто не сообщит |
| Повторяющийся лог `Idempotency purge left ... expired claims` | Очистка не успевает; таблица растёт |

Повторы и дубликаты - нормальный трафик, а не повод для алерта.

**Известные пробелы:** нет gauge числа claim-ов в `IN_PROGRESS` и размера таблицы.

## Аудит

| Поле | Повтор | Расхождение отпечатка |
|---|---|---|
| category | `idempotency` | `idempotency` |
| action | `idempotency.replayed` | `idempotency.fingerprint-mismatch` |
| resource | `idempotency-key` / ключ | то же |
| outcome | `SUCCESS` | `DENIED` (отказ намеренный и правильный) |

Событий на каждый успешный захват нет (это была бы строка на каждый запрос). Ни одно событие не несёт тело
запроса или отпечатки: отпечаток позволил бы читателю следа проверить догадку о содержимом запроса.

## Runbook

| Симптом | Вероятная причина | Что делать |
|---|---|---|
| Клиенты долго получают `409 in-progress` | Владелец умер; claim ждёт истечения `lease` | Ждёт максимум `lease` (60s). Если дольше: `SELECT * FROM idempotency_claim WHERE scope = '<s>' AND idempotency_key = '<k>';` - проверить `lease_expires_at` |
| Клиент сообщает о `422 fingerprint-mismatch` | Клиент переиспользует ключи для разных запросов | Ошибка клиента; новый ключ на каждый логический запрос |
| Двойные побочные эффекты несмотря на фильтр | Эндпоинт не подключён, метод не в `methods`, TTL короче окна повторов клиента или окно падения на Redis | Проверить `@Idempotent` / `path-patterns`, `methods`, `ttl` |
| Kafka-сообщение не обработано, ошибок нет | Claim закоммичен вне транзакции listener-а | Использовать билдер messaging (он подключает менеджер транзакций) или задать его на контейнере |
| `idempotency_claim` растёт | Очистка выключена или блокировка у другого | Проверить `purge.enabled`, лог и `SELECT * FROM job_run_lock WHERE lock_name = 'ludwig-idempotency-purge';` |
| `TRANSACTIONAL claim must run inside the caller's transaction` | `TRANSACTIONAL` claim без транзакции | Вызывать внутри `@Transactional` или использовать `STANDALONE` |
| Освободить claim вручную (клиент должен выполнить заново) | - | `UPDATE idempotency_claim SET state = 'FAILED' WHERE scope = '<s>' AND idempotency_key = '<k>';` - следующий запрос перезахватит его. Только если известно, что работа не выполнялась |

## Тестирование

Интеграционные тесты на реальном PostgreSQL (Docker); однопоточные тесты здесь ничего не доказывают.

| Набор | Фиксирует |
|---|---|
| `IdempotencyStoreIT` | N потоков → один победитель, проигравшие получают id победителя; откат освобождает ключ; `TRANSACTIONAL` без транзакции отклоняется; переходы состояний; истечение аренды; граница TTL ±1s; scope как пространства имён; избирательная очистка |
| `IdempotencyHttpIT` | повторный `POST` повторяет `201`, тело и `Location`, а обработчик выполняется один раз; пересериализованный повтор совпадает; переиспользованный ключ → 422 без первого ответа; выполняющийся → 409 + `Retry-After`; неудачный запрос освобождает ключ; 5xx не сохраняется |
| `ConsumerDedupIT` | дубликат записи → один эффект; откатившийся listener получает запись снова; переиспользованный ключ продюсера с другим payload отклоняется |
| `RetentionPurgeIT` | многопакетная очистка щадит живые claim-ы; реплика без аренды ничего не удаляет |
| `NotificationMigrationIT` | старые строки переносятся с сохранением окон; идемпотентно; новые claim-ы сохраняются |
| `SqlConfinementTest` | SQL остаётся в `…sql`; нет `@RestControllerAdvice`; нет audit SPI; нет зависимости от rest-client или hot-reload |
| `RequestFingerprintTest` | порядок ключей / пробелы / charset не важны; порядок массивов важен |

Границы проверяются подвижными часами `Clock` (момент времени - параметр запроса, а не `now()`).

## Проектные решения

- **Механизмов «идемпотентности» семь; здесь живут два** (захват ключа; передача ключа дальше).
  Дедупликация публикации outbox, колонка ключа в запуске экспорта, ограничение `(bucket, key, etag)`
  file-ingest, классификация повторов по RFC 9110 и проекции last-write-wins - правильные локальные решения
  и остаются на своих местах.
- **Один модуль, а не core + starter**: ничему ниже `db-core` хранилище не нужно. Если оно понадобится
  rest-client или hot-reload, сначала выделите `idempotency-core`; `SqlConfinementTest` охраняет отсутствие
  этих рёбер.
- **Режим выбирается в месте вызова**; неверный режим - ошибка, проявляющаяся только при конкуренции.
- **`TransactionTemplate`, а не `@Transactional`**: режим - значение времени выполнения, а самовызов молча
  обошёл бы прокси.
