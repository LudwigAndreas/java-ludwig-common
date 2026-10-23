# web-core-spring-boot-starter

*[English](README.md) · **Русский***

REST-основа каждого сервиса: единый локализованный конвейер [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457)
`ProblemDetail`, в который модули **вносят вклад** (ни один модуль не поставляет свой
`@RestControllerAdvice`), транспортно-нейтральные бизнес-исключения, определение локали по
`Accept-Language`, подключённое к Bean Validation, предпочтения вызывающего (язык и часовой пояс),
постраничный конверт ответа и контракт долгих операций. Обычный `@RestController` отвечает на любую
ошибку - бизнес, валидация, фреймворк, безопасность, необработанная - одним документом проблемы на языке
вызывающего.

| | |
|---|---|
| Роль | starter |
| Зависит от (в репозитории) | ничего |
| Используется | почти всеми стартерами и обоими сервисами (см. `PROJECT_INDEX.md`) |
| Поверхности | REST (отрисовка ошибок, конверты ответов) |

| Пакет | Содержимое |
|---|---|
| `problem` | `LocalizedException`, `ProblemStatus`, `ProblemDefinition`, `ProblemCodes`, `Violation`; `ProblemMessages`, `ProblemMessageBundle`, `ExceptionProblemMapper`, `ProblemMapperRegistry`, `ProblemDetailFactory` - без зависимости от MVC |
| `problem.mapper` | Поставляемые мапперы, каждый условен на класс, который отображает |
| `web` | `ApiExceptionHandler`, `PageResponse` |
| `preference` | `UserPreferences`, `UserPreferenceSource`, `UserPreferenceFormatter` |
| `operation` | `OperationStatus`, `OperationResponse`, `OperationProgress`, `OperationResult`, `OperationFailure`, `Cancellation`, `OperationHeaders`, `OperationLocationHeader`, `OperationResponses` |
| `trace` | `TraceIdProvider`, `MdcTraceIdProvider` |
| `config` | Автоконфигурации, `WebCoreProperties` |

## Использование

```xml
<dependency>
    <groupId>ru.ludwigandreas</groupId>
    <artifactId>web-core-spring-boot-starter</artifactId>
</dependency>
```

Объявите, что означает ошибка, в сервисном слое, без HTTP:

```java
public class ProductNotFoundException extends LocalizedException {
    public ProductNotFoundException(UUID id) {
        super(ProblemStatus.NOT_FOUND, "error.product.not-found", id);
    }
}
```

Текст в `src/main/resources/i18n/messages[_ru].properties`:

```properties
error.product.not-found.title=Product not found
error.product.not-found=No product exists with id {0}.
```

Бросьте его:

```console
$ curl -H 'Accept-Language: ru' localhost:8080/api/v1/products/8f3a...
HTTP/1.1 404
Content-Type: application/problem+json
Content-Language: ru

{
  "type": "urn:ludwig:problem:error.product.not-found",
  "title": "Товар не найден",
  "detail": "Товар с идентификатором 8f3a... не существует.",
  "status": 404,
  "instance": "/api/v1/products/8f3a...",
  "code": "error.product.not-found",
  "timestamp": "2026-09-15T18:22:07.411Z",
  "traceId": "0af7651916cd43dd8448eb211c80319c"
}
```

### Как работает конвейер

Модуль сообщает только, что **означает** его ошибка (статус, код, аргументы, доп. свойства), через бин
`ExceptionProblemMapper`, а текст поставляет бином `ProblemMessageBundle`. Отрисовывает всё один advice.
Собственный `MessageSource` приложения опрашивается раньше бандлов модулей, поэтому любую формулировку можно
переопределить тем же ключом локально.

```
 модуль: ExceptionProblemMapper ─┐                    ┌─> ProblemMessages (сначала бандл приложения, затем модулей)
 модуль: ProblemMessageBundle  ──┼─> ProblemMapperRegistry (первое совпадение)
 прил.:  LocalizedException    ──┘                    └─> ProblemDetailFactory ─> ApiExceptionHandler
```

### Что отвечается из коробки

| Ошибка | Статус | Код |
|---|---|---|
| `LocalizedException` | как объявлено | как объявлено |
| отклонён `@Valid @RequestBody` | 400 | `ludwig.web.error.validation` + `violations` |
| ограничение на `@RequestParam` / `@PathVariable` | 400 | `ludwig.web.error.validation` + `violations` |
| `ConstraintViolationException` из `@Validated` бина | 400 | `ludwig.web.error.validation` + `violations` |
| неразбираемое тело | 400 | `ludwig.web.error.malformed-request` |
| отсутствующий или неконвертируемый параметр | 400 | `ludwig.web.error.bad-request` + `parameter` |
| неверный метод | 405 | `ludwig.web.error.method-not-allowed` (сохраняет `Allow`) |
| неверный `Content-Type` | 415 | `ludwig.web.error.unsupported-media-type` |
| ничего не подходит под `Accept` | 406 | `ludwig.web.error.not-acceptable` |
| неизвестный путь | 404 | `ludwig.web.error.not-found` |
| `AccessDeniedException` внутри dispatch | 403 | `ludwig.web.error.forbidden` |
| `AuthenticationException` внутри dispatch | 401 | `ludwig.web.error.unauthorized` |
| `DataIntegrityViolationException` | 409 | `ludwig.web.error.conflict` |
| `OptimisticLockingFailureException` | 409 | `ludwig.web.error.concurrent-modification` |
| `PessimisticLockingFailureException` | 423 | `ludwig.web.error.concurrent-modification` |
| `QueryTimeoutException` | 503 | `ludwig.web.error.upstream-unavailable` |
| слишком большая загрузка | 413 | `ludwig.web.error.payload-too-large` |
| всё необработанное | 500 | `ludwig.web.error.internal` |

`@PreAuthorize` и data guards бросают внутри MVC dispatch, после цепочки фильтров безопасности, поэтому без
этого отображения были бы 500. Каждый ответ имеет `Content-Type: application/problem+json` и
`Content-Language`, называющий фактически использованный язык.

### Расширение

Отобразить исключение:

```java
@Bean
ExceptionProblemMapper quotaExceededMapper() {
    return ExceptionProblemMapper.forType(
            QuotaExceededException.class,
            e -> ProblemDefinition.of(ProblemStatus.TOO_MANY_REQUESTS, "error.quota.exceeded", e.limit())
                    .withProperty("limit", e.limit()));
}
```

- Мапперы опрашиваются в порядке `Ordered`; побеждает первое совпадение. Мапперы библиотек используют
  `ExceptionProblemMapper.DEFAULT_MODULE_ORDER`, поэтому маппер приложения (порядок 0) их переопределяет.
- Реестр проходит по цепочке причин (обёртки JPA, транзакций и прокси), сначала неглубокие, поэтому
  намеренный 409 остаётся 409, когда фреймворк его оборачивает.

Внести текст из модуля:

```java
@Bean
ProblemMessageBundle myModuleProblemMessages() {
    return ProblemMessageBundle.of("i18n/ludwig-mymodule-messages");
}
```

Сообщать о доменных нарушениях в той же форме, что Bean Validation:

```java
throw new InvalidProductException()
        .withProperty("violations", List.of(
                Violation.of("price", messages.get(...), "PriceBelowCost")));
```

Модули, вносящие вклад в конвейер: `audit`, `db-core`, `export`, `file-action`, `file-ingest`,
`grpc-server`, `idempotency`, `messaging`, `object-storage`, `odata-filter`, `pat`, `security`,
`user-settings` и `notification-service`.

### Локализация

Три бина, все `@ConditionalOnMissingBean`, объявлены до `MessageSourceAutoConfiguration` и
`ValidationAutoConfiguration` Boot:

- `MessageSource` над `ludwig.web.i18n.basenames`, UTF-8, без отката на локаль сервера;
- `AcceptHeaderLocaleResolver`, ограниченный `supported-locales`, поэтому неподдерживаемый язык получает язык
  по умолчанию целиком, а не наполовину переведённый ответ;
- `LocalValidatorFactoryBean`, привязанный к этому `MessageSource`, поэтому сообщения ограничений тоже
  локализованы.

Держите бандлы синхронными между локалями; `checkstyle-rules` проверяет согласованность ключей.

### Предпочтения вызывающего

`UserPreferences` - единственный в платформе ответ на «на каком языке и в каком часовом поясе читает этот
вызывающий»; он публикует пояс в `LocaleContextHolder` (поэтому `@JsonFormat`, `@DateTimeFormat` и аргументы
сообщений используют пояс вызывающего, а не контейнера).

В MapStruct-маппере укажите форматтер, и преобразования выбираются по типам:

```java
@Mapper(componentModel = "spring", uses = UserPreferenceFormatter.class)
public interface ProductMapper {
    ProductResponse toResponse(Product product);   // Instant createdAt -> OffsetDateTime в поясе вызывающего
}
```

У `UserPreferenceFormatter` ровно один метод на пару источник/цель (`Instant → OffsetDateTime`,
`Instant → LocalDate`), поэтому квалификаторы MapStruct не нужны.

В остальном коде: `UserPreferences.current()` (никогда не null, не бросает; консервативное значение) или
`currentIfResolved()` (пусто, если предпочтение никто не выразил). Производные значения - методы:
`numberFormat()`, `firstDayOfWeek()`, `dateTimeFormatter(FormatStyle)`.

Разрешение - по каждому измерению независимо (побеждает первый ответивший источник):

| Порядок | Источник | Откуда ответ |
|---|---|---|
| 100 | `StoredUserPreferenceSource` ([user-settings](../user-settings-spring-boot-starter)) | сохранённые `user.locale` / `user.timezone`, только если заданы на уровне конкретнее `PLATFORM` |
| 200 | `RequestHeaderPreferenceSource` | `Accept-Language`; заголовок пояса (по умолчанию `X-Time-Zone`) |
| последний | `ConfiguredPreferenceSource` | `ludwig.web.i18n.default-locale`, `ludwig.web.preferences.default-zone` |

Сохранённый выбор сильнее заголовка запроса (заголовок браузера - подсказка; сохранённое значение - выбор
пользователя). В заголовке принимаются и `Europe/Moscow`, и `+03:00`; непригодное значение игнорируется, а не
отклоняется. Тег вида `ru-RU` сохраняется, если его язык поддерживается (региональные данные определяют
первый день недели и разделители), поэтому `Content-Language` может быть `ru-RU`.

Вне потока запроса:

```java
UserPreferences captured = UserPreferences.current();          // поток запроса
try (UserPreferences.Scope scope = captured.bind()) {          // рабочий поток
    render(captured);
}
```

Это только **окружающий** контекст текущего вызывающего. «Каковы предпочтения пользователя X» - это
`SettingsLookup.getAll(subject)` в user-settings.

### Постраничные ответы

```java
@GetMapping
public PageResponse<ProductResponse> search(ProductQuery query) {
    return PageResponse.of(productService.search(query), mapper::toResponse);
}
```

```json
{ "content": [...], "page": 1, "size": 20, "offset": 25, "totalElements": 137, "totalPages": 7 }
```

- **`offset` авторитетен**; `page` вычисляется и теряет точность при невыровненных смещениях (OData `$skip`).
- `totalElements` / `totalPages` **отсутствуют** (не null и не 0), если вызывающий отказался от подсчёта
  (`$count=false`). Для результатов не-`Page` используйте `PageResponse.of(content, offset, size, totalElements)`.
- `Page` Spring Data напрямую никогда не возвращается: его JSON-форма - деталь библиотеки персистентности.

### Долгие операции

**Контракт, а не фреймворк**: каждый модуль хранит свою таблицу запусков и отображает её на этот конверт на
своей границе.

```
PENDING -> RUNNING -> SUCCEEDED | FAILED | CANCELLED
SUCCEEDED -> EXPIRED            (срок хранения удалил результат; запись остаётся)
```

`EXPIRED` и `CANCELLED` - не ошибки: ветвитесь по `status.isFailure()`, никогда по `!= SUCCEEDED`. Более
богатые доменные состояния (например, `COLLECTING` в reconciliation) отображаются на эти и остаются видимыми в
`OperationResponse.detail()`.

```java
@PostMapping
ResponseEntity<RunResponse> submit(@Valid @RequestBody RunRequest body) {
    Run run = service.submit(body);
    RunResponse response = response(run);
    return run.isQueued()
            ? OperationResponses.accepted(response.operation(), response,
                    statusUri(run.getId()), OperationLocationHeader.LOCATION)
            : OperationResponses.completed(response.operation(), response);
}
```

| Эндпоинт | Ответ |
|---|---|
| submit, в очереди | `202` + `Location` (операция и есть ресурс) или `Operation-Location` (результат в другом месте) - выбирает вызывающий код |
| submit, уже выполнено | `200` с терминальным конвертом |
| poll (`OperationResponses.poll`) | `200`; `Retry-After`, пока не терминально. Отклоняет нетерминальный ответ без `Retry-After`, успех без `result`, ошибку без `failure` |
| cancel (`cancellationRequested`) | `202` (кооперативная отмена); для завершённой операции - её терминальный конверт, а не 409 |

`OperationProgress.total` допускает null (потоковый продюсер его не знает); null отсутствует в JSON. Эндпоинт
submit должен принимать `Idempotency-Key`: дубликат получает `409` + `Retry-After`, никогда второй `202`.

### Сосуществование с собственным advice

Общий advice регистрируется с `Ordered.LOWEST_PRECEDENCE`. Spring использует первый advice, который вообще
может обработать исключение, поэтому где-либо кроме последнего места он поглощал бы исключения вашего advice.
Advice сервиса без `@Order` всё равно идёт раньше. `ludwig.web.problem.enabled=false` убирает advice и
оставляет внедряемыми `ProblemDetailFactory`, `ProblemMessages` и цепочку мапперов.

### Использование вне веба

`LocalizedException`, `ProblemStatus`, `ProblemMessages` и цепочка мапперов не зависят от MVC; MVC-половина
условна на сервлетное веб-приложение. Пакетные задачи и Kafka-потребители отрисовывают те же ошибки в ответы,
логи или dead letter.

## Конфигурация

| Свойство | По умолчанию | Смысл |
|---|---|---|
| `ludwig.web.enabled` | `true` | Главный выключатель |
| `ludwig.web.problem.enabled` | `true` | Регистрировать общий advice |
| `ludwig.web.problem.type-prefix` | `urn:ludwig:problem:` | Префикс URI `type`; направьте на документацию, чтобы он разыменовывался |
| `ludwig.web.problem.include-instance` | `true` | `instance` = URI запроса |
| `ludwig.web.problem.include-timestamp` | `true` | Добавлять `timestamp` |
| `ludwig.web.problem.include-trace-id` | `true` | Публиковать trace id в теле |
| `ludwig.web.problem.trace-id-mdc-key` | `traceId` | Ключ MDC, откуда он читается |
| `ludwig.web.problem.trace-id-property` | `traceId` | Имя члена в теле |
| `ludwig.web.problem.include-rejected-value` | `false` | Повторять отправленные значения в `violations` - может раскрыть секреты |
| `ludwig.web.problem.include-exception-message` | `false` | Добавлять `debug` в проблемы 5xx - раскрывает внутренности |
| `ludwig.web.problem.advice-order` | последний | Порядок общего advice |
| `ludwig.web.i18n.enabled` | `true` | Настраивать `MessageSource`, `LocaleResolver`, валидатор |
| `ludwig.web.i18n.basenames` | `classpath:i18n/messages` | Бандлы приложения |
| `ludwig.web.i18n.encoding` | `UTF-8` | Кодировка бандлов |
| `ludwig.web.i18n.supported-locales` | `en` | Языки ответов API |
| `ludwig.web.i18n.default-locale` | `en` | Язык по умолчанию |
| `ludwig.web.i18n.fallback-to-system-locale` | `false` | Разрешить локаль хоста |
| `ludwig.web.i18n.cache-duration` | `-1` (навсегда) | Положительное значение - перечитывать изменённые бандлы |
| `ludwig.web.i18n.configure-validator` | `true` | Привязать Bean Validation к `MessageSource` |
| `ludwig.web.preferences.enabled` | `true` | Разрешать предпочтения вызывающего; off = обычный `AcceptHeaderLocaleResolver` |
| `ludwig.web.preferences.default-zone` | `UTC` | Пояс, если никто не ответил (никогда не пояс контейнера) |
| `ludwig.web.preferences.time-zone-header` | `X-Time-Zone` | Заголовок с поясом вызывающего; пусто = не использовать |
| `ludwig.web.preferences.accept-language` | `true` | Учитывать `Accept-Language` |

## Развёртывание

Развёртывать нечего. Сборка должна компилировать с `-parameters` (задано в корневом POM и
`ludwig-service-parent`); без него нарушение параметра называет вход `arg0`.

## Наблюдаемость

**Логи.** `ERROR Request failed with <status> [<code>]` для 5xx с исключением (клиент видит только trace id).
`INFO` при старте с источниками предпочтений и значениями по умолчанию.

**Трассировка.** Trace id (MDC `traceId`, заполняет observability) кладётся в тело каждой проблемы, поэтому
«сообщите trace id» - выполнимая инструкция для пользователя.

**Метрики.** Собственных нет; метрики HTTP-сервера дают [observability](../observability-spring-boot-starter)
и Spring Boot.

## Аудит

Не применяется. Решения безопасности аудирует security-стартер; этот модуль только отрисовывает ответы.

## Runbook

| Симптом | Вероятная причина | Что делать |
|---|---|---|
| Пользователь сообщает об ошибке | - | Попросить `traceId` из тела; искать по нему в логах и трассах |
| Текст ошибки на английском для русского вызывающего | Нет ключа в бандле `_ru` или `ru` нет в `supported-locales` | Добавить ключ; проверить `ludwig.web.i18n.supported-locales` |
| Ожидаемый 4xx приходит как 500 | Исключение не отображено и не `LocalizedException` | Добавить `ExceptionProblemMapper` или наследовать `LocalizedException` |
| Собственный `@RestControllerAdvice` не вызывается | Его приоритет ниже другого advice, обрабатывающего `Exception` | Задать явный `@Order` |
| В сообщениях валидации видны ключи `{...}` | `configure-validator=false` или ключа нет | Включить обратно / добавить ключ |
| Даты в UTC вместо пояса пользователя | Маппер не использует `UserPreferenceFormatter` или код использует `ZoneOffset.UTC` | Использовать форматтер / `UserPreferences.current()` |

## Тестирование

Модульные и slice-тесты на каждое отображение из таблицы выше, порядок разрешения бандлов, порядок
разрешения предпочтений, инварианты ответов операций и сериализацию `PageResponse`. В сервисе проверяйте
ответы об ошибках через MockMvc по `code` и `status`, а не по тексту.

## Проектные решения

- **Один advice, вклад через мапперы.** Отдельные advice модулей дают разные формы и не переводятся приложением.
- **Тела 401/403 общие.** Сообщение *почему* доступ запрещён превращает эндпоинт в оракул перебора; подробности
  идут в аудит. У 401, отрисованного здесь, нет `WWW-Authenticate` (без браузерного запроса учётных данных). Со
  security-стартером коды - `ludwig.security.error.*`, такие же, как у цепочки фильтров.
- **Никаких секретов в телах проблем**: отклонённые значения и сообщения исключений выключены по умолчанию и
  являются явными переключателями развёртывания.
- **Предпочтения - два измерения.** Остальное вычисляется, поэтому сохранённые значения не противоречат друг другу.
- **Принуждение ArchUnit** (`RuleGroup.PRESENTATION`): `noAmbientDefaultLocaleOrZone` (никаких
  `Locale.getDefault()`, `ZoneId.systemDefault()`, ...) и `noSecondCallerPreferenceType`. Маппер с
  `ZoneOffset.UTC` - вопрос ревью, а не правило.
- **Нет общей таблицы операций.** Таблица запусков каждого модуля подходит под его запросы.
