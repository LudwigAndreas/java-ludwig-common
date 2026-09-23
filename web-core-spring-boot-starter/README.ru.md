# web-core-spring-boot-starter

*[English](README.md) · **Русский***

REST-фундамент, на котором стоит каждый сервис этого репозитория: один локализованный конвейер
`ProblemDetail` по [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457) (ранее RFC 7807), в который
каждый модуль **вносит вклад**, вместо того чтобы поставлять собственный `@RestControllerAdvice`;
транспортно-нейтральные бизнес-исключения; разрешение локали запроса, связанное с Bean Validation; и
конверт постраничного ответа, не протекающий JSON-формой Spring Data.

Добавьте зависимость — и обычный `@RestController`-сервис отвечает на любой отказ (бизнес-,
валидационный, фреймворочный, security, необработанный) одним документом проблемы на языке
вызывающего, без собственных advice, `MessageSource` и резолвера локали.

## Быстрый старт

```xml
<dependency>
    <groupId>ru.ludwigandreas</groupId>
    <artifactId>web-core-spring-boot-starter</artifactId>
    <version>1.0.0</version>
</dependency>
```

Объявите, что отказ **означает**, в сервисном слое, без единого упоминания HTTP:

```java
public class ProductNotFoundException extends LocalizedException {

    public ProductNotFoundException(UUID id) {
        super(ProblemStatus.NOT_FOUND, "error.product.not-found", id);
    }
}
```

Положите его текст в `src/main/resources/i18n/messages[_ru].properties`:

```properties
error.product.not-found.title=Product not found
error.product.not-found=No product exists with id {0}.
```

Бросьте его. Больше подключать нечего:

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

## Проблема, ради устранения которой стартер и существует

Каждый модуль этого репозитория, способный упасть так, что это видит клиент, раньше поставлял
собственный `@RestControllerAdvice`. Выглядит как хорошая инкапсуляция — и не переживает встречи со
вторым модулем. Ломаются две вещи, и они усиливают друг друга.

**Язык.** Advice из библиотеки может отдавать только тот текст, с которым была скомпилирована. Advice
OData-стартера отвечала собственным английским текстом своих исключений, написанным для разработчика:
*«Property 'supplierCost' cannot be used in $filter/$orderby: not annotated @Filterable»* — потому что
единственный bundle, способный это перевести, должен был бы лежать в приложении. Так сервис,
отвечавший на все прочие ошибки на языке вызывающего, имел одну подсистему, отвечающую по-английски.
Единственным выходом было выключить эту advice
(`odata.filter.web.problem-detail-advice-enabled=false`) и вручную переобработать все семь типов её
исключений. Каждый сервис, использовавший модуль, писал одну и ту же advice, и предшественник этого
стартера — `ApiExceptionHandler` в `crud-service-example` — нёс комментарий, прямо об этом
говорящий.

**Форма.** Несколько advice — это несколько мнений о том, что содержит документ проблемы. Одна
выставляла `title` и не выставляла `code`, другая наоборот. Клиент не мог рассчитывать на наличие
`code`, потому что оно зависело от того, какая подсистема отказала.

Лечение — отделить **смысл** от **отрисовки**. Модуль сообщает только то, что означает его отказ: это
400, вот его код, вот поле-нарушитель, — внося `ExceptionProblemMapper`, и поставляет свой текст как
`ProblemMessageBundle`. Отрисовывает всё одна advice, а bundle самого приложения просматривается
раньше любого модульного, поэтому любую формулировку можно переопределить, объявив тот же ключ у
себя. Ни форка, ни настройки, ни переобработки.

```
      модуль                      приложение                      этот стартер
 ┌──────────────────┐          ┌───────────────────┐         ┌────────────────────┐
 │ его исключения   │          │ наследники        │         │ ProblemMapper      │
 │        │         │          │ LocalizedException│         │   Registry         │
 │        ▼         │          │        │          │         │        │           │
 │ ExceptionProblem │──────────┼────────┼──────────┼────────▶│ побеждает первое   │
 │   Mapper (бин)   │          │        ▼          │         │   совпадение       │
 │        +         │          │  i18n/messages    │         │        ▼           │
 │ ProblemMessage   │──────────┼── смотрится ──────┼────────▶│  ProblemMessages   │
 │  Bundle (бин)    │          │    первым         │         │        │           │
 └──────────────────┘          └───────────────────┘         │        ▼           │
                                                             │ ProblemDetail      │
                                                             │   Factory          │
                                                             │        │           │
                                                             │        ▼           │
                                                             │ ApiExceptionHandler│
                                                             └────────────────────┘
```

## Что он отвечает из коробки

| Отказ | Статус | Код |
|---|---|---|
| `LocalizedException` | тот, что он объявил | тот, что он объявил |
| отклонённое `@Valid @RequestBody` | 400 | `ludwig.web.error.validation` + `violations` |
| ограничение на `@RequestParam`/`@PathVariable` | 400 | `ludwig.web.error.validation` + `violations` |
| `ConstraintViolationException` от `@Validated`-бина | 400 | `ludwig.web.error.validation` + `violations` |
| неразбираемое тело | 400 | `ludwig.web.error.malformed-request` |
| отсутствующий или непреобразуемый параметр | 400 | `ludwig.web.error.bad-request` + `parameter` |
| неверный метод | 405 | `ludwig.web.error.method-not-allowed` (сохраняет `Allow`) |
| неверный `Content-Type` | 415 | `ludwig.web.error.unsupported-media-type` |
| ничего приемлемого для `Accept` | 406 | `ludwig.web.error.not-acceptable` |
| неизвестный путь | 404 | `ludwig.web.error.not-found` |
| `AccessDeniedException` внутри диспетчеризации | 403 | `ludwig.web.error.forbidden` |
| `AuthenticationException` внутри диспетчеризации | 401 | `ludwig.web.error.unauthorized` |
| `DataIntegrityViolationException` | 409 | `ludwig.web.error.conflict` |
| `OptimisticLockingFailureException` | 409 | `ludwig.web.error.concurrent-modification` |
| `PessimisticLockingFailureException` | 423 | `ludwig.web.error.concurrent-modification` |
| `QueryTimeoutException` | 503 | `ludwig.web.error.upstream-unavailable` |
| слишком большая загрузка | 413 | `ludwig.web.error.payload-too-large` |
| всё необработанное | 500 | `ludwig.web.error.internal` |

Несколько строк здесь — суть модуля, а не украшение. `AccessDeniedException` и
`AuthenticationException` бросаются `@PreAuthorize` и защитой данных **внутри** диспетчеризации MVC,
после того как цепочка фильтров security уже передала запрос дальше: `AccessDeniedHandler`
приложения их не видит, и без этого они всплывают пятисотками. Нарушение ограничения и отказ
оптимистичной блокировки по умолчанию тоже пятисотки — отклонённый ввод и проигранная гонка записи,
поданные как отказы сервера.

Каждый ответ несёт `Content-Type: application/problem+json` и `Content-Language`, называющий язык, на
котором тело действительно вернулось, — а он не всегда тот, который просили.

## Как это расширять

### Научить его исключению, о котором он не слышал

Один бин. `ExceptionProblemMapper.forType` закрывает обычный случай:

```java
@Bean
ExceptionProblemMapper quotaExceededMapper() {
    return ExceptionProblemMapper.forType(
            QuotaExceededException.class,
            e -> ProblemDefinition.of(ProblemStatus.TOO_MANY_REQUESTS, "error.quota.exceeded", e.limit())
                    .withProperty("limit", e.limit()));
}
```

Мапперы просматриваются в порядке `Ordered`, побеждает первое совпадение. Собственные мапперы
библиотеки регистрируются на `ExceptionProblemMapper.DEFAULT_MODULE_ORDER`, поэтому маппер
приложения — на порядке 0 по умолчанию — перекрывает любой из них, не зная, какое число выбрала
библиотека.

Реестр также идёт по цепочке причин. Исключение, дошедшее до advice, часто не то, которое бросили:
flush JPA заворачивает нарушение ограничения, менеджер транзакций — отказ коммита, прокси —
проверяемое исключение. Обход цепочки означает, что намеренный 409 остаётся 409, когда слой фреймворка
решил его завернуть, а не деградирует до 500. Побеждает более мелкая причина, поэтому обёртка,
которая **сама** отображена, выигрывает у своей отображённой причины.

### Внести текст из модуля

```java
@Bean
ProblemMessageBundle myModuleProblemMessages() {
    return ProblemMessageBundle.of("i18n/ludwig-mymodule-messages");
}
```

Порядок разрешения ключа полный: сначала `MessageSource` приложения, затем внесённые bundle в
объявленном порядке, затем умолчание вызывающего. Так модуль поставляет рабочий текст, а приложение
переформулирует любой его фрагмент, объявив тот же ключ в своём bundle.

### Сообщать собственные нарушения

Доменное правило, невыразимое в Bean Validation, сообщается в той же форме, что и отклонённый
`@NotBlank`, — чтобы у клиента был один путь обработки «отклонено по полям»:

```java
throw new InvalidProductException()
        .withProperty("violations", List.of(
                Violation.of("price", messages.get(...), "PriceBelowCost")));
```

## Локализация

Три бина, которые REST-сервис иначе писал бы сам, все под `@ConditionalOnMissingBean`:

- `MessageSource` поверх `ludwig.web.i18n.basenames`, UTF-8, никогда не откатывающийся к локали
  самого сервера — иначе язык API зависел бы от того, как настроен контейнер;
- `AcceptHeaderLocaleResolver`, ограниченный `supported-locales`, чтобы запросивший язык без bundle
  получил умолчание **целиком**, а не наполовину переведённый ответ;
- `LocalValidatorFactoryBean`, связанный с этим `MessageSource`, — именно он делает сообщения
  ограничений (`{catalog.validation.product.sku.required}`) локализованными. Без него отклонённый
  запрос возвращается с переведённым `title` и английскими сообщениями по полям.

Эта конфигурация объявлена `before` относительно `MessageSourceAutoConfiguration` и
`ValidationAutoConfiguration` Spring Boot, которые обе отступают при наличии бина нужного типа, — так
что она побеждает порядком, а не борьбой. Любой бин того же типа из приложения имеет приоритет, а
`ludwig.web.i18n.enabled=false` возвращает весь вопрос Boot-у.

Держите bundle в ногу: ключ, присутствующий в одной локали и отсутствующий в другой, — это то, как API
начинает отвечать наполовину на одном языке и наполовину на другом. Небольшой тест, сверяющий наборы
ключей между bundle, стоит своих десяти строк.

## Постраничные ответы

```java
@GetMapping
public PageResponse<ProductResponse> search(ProductQuery query) {
    return PageResponse.of(productService.search(query), mapper::toResponse);
}
```

```json
{ "content": [...], "page": 0, "size": 20, "totalElements": 137, "totalPages": 7 }
```

`Page` из Spring Data напрямую не возвращается. Его JSON-форма — деталь реализации библиотеки
хранения: она менялась между версиями, а при сериализации выдаёт объект `pageable`, описывающий, как
был выполнен запрос, а не то, о чём просил клиент. Возврат его делает каждого потребителя API
зависимым от выбора библиотеки доступа к данным; Spring Boot 3.3 предупреждает ровно об этом при
старте.

## Конфигурация

| Свойство | По умолчанию | Что делает |
|---|---|---|
| `ludwig.web.enabled` | `true` | общий выключатель стартера |
| `ludwig.web.problem.enabled` | `true` | регистрирует общую advice; при выключении конвейер остаётся доступным из вашей собственной |
| `ludwig.web.problem.type-prefix` | `urn:ludwig:problem:` | префикс URI в `type`; направьте на свою документацию, чтобы он разыменовывался |
| `ludwig.web.problem.include-instance` | `true` | выставляет `instance` в URI запроса |
| `ludwig.web.problem.include-timestamp` | `true` | добавляет член `timestamp` |
| `ludwig.web.problem.include-trace-id` | `true` | публикует идентификатор трассировки в теле |
| `ludwig.web.problem.trace-id-mdc-key` | `traceId` | ключ MDC, из которого он читается |
| `ludwig.web.problem.trace-id-property` | `traceId` | имя члена, под которым публикуется |
| `ludwig.web.problem.include-rejected-value` | `false` | возвращает присланное значение в `violations` |
| `ludwig.web.problem.include-exception-message` | `false` | добавляет член `debug` к проблемам 5xx |
| `ludwig.web.problem.advice-order` | последний | порядок общей advice, см. ниже |
| `ludwig.web.i18n.enabled` | `true` | настраивает `MessageSource`, `LocaleResolver`, валидатор |
| `ludwig.web.i18n.basenames` | `classpath:i18n/messages` | собственные bundle приложения |
| `ludwig.web.i18n.encoding` | `UTF-8` | кодировка bundle |
| `ludwig.web.i18n.supported-locales` | `en` | языки, на которых отвечает API |
| `ludwig.web.i18n.default-locale` | `en` | к чему откатывается неподдерживаемый язык |
| `ludwig.web.i18n.fallback-to-system-locale` | `false` | можно ли использовать локаль хоста |
| `ludwig.web.i18n.cache-duration` | `-1` (навсегда) | положительные значения перечитывают изменённые bundle |
| `ludwig.web.i18n.configure-validator` | `true` | связывает Bean Validation с `MessageSource` |

### Сосуществование с вашей собственной advice

Общая advice регистрируется на `Ordered.LOWEST_PRECEDENCE`, и это несущее решение, а не произвол. Она
обрабатывает `Exception`, а Spring возвращает первую advice, которая **вообще** способна обработать
брошенное исключение, — а не ту, у которой самый специфичный обработчик для него. Advice, стоящая
где-либо кроме последнего места, поэтому проглатывала бы каждое исключение, ради отрисовки которого
написана ваша `@RestControllerAdvice`, включая типы, объявленные ею явно. Последнее место означает,
что у всех прочих advice есть право первого отказа.

Ничьи при этом разрешаются в вашу пользу: сортировка Spring устойчива, а бины автоконфигурации
регистрируются после собственных бинов приложения, поэтому `@RestControllerAdvice` без `@Order` всё
равно идёт раньше этой. Поставьте явный `@Order`, если не хотите зависеть от этого.

Если вы хотите отрисовывать **всё** сами, `ludwig.web.problem.enabled=false` убирает advice и
оставляет `ProblemDetailFactory`, `ProblemMessages` и цепочку мапперов доступными для внедрения —
тогда в вашей advice по одной строке на обработчик, а локализованный текст по-прежнему приходит из
bundle каждого модуля.

### Два переключателя, которые лучше не трогать

`include-rejected-value` и `include-exception-message` выключены по умолчанию, и оба сделаны
переключателями, а не проверками профиля, чтобы их включение было решением о развёртывании, которое
можно проаудировать.

Поле, чаще всего не проходящее валидацию, — это же поле, чаще всего содержащее пароль, токен или
номер карты, а тело проблемы — ровно то, что вставляют в тикет. По той же причине `detail` у 500
никогда не цитирует исключение: *«connection refused to db-7.internal:5432»* написано для того, кто
эксплуатирует сервис. Вместо этого клиент получает идентификатор трассировки — в теле, а не только в
заголовке, который консоль браузера прячет, — и именно это делает указание «назовите trace id»
выполнимым.

## Замечания по безопасности

- Advice ловит `AccessDeniedException` раньше, чем это может сделать `ExceptionTranslationFilter`
  Spring Security, и это намеренно: тот фильтр не находится на пути исключения, брошенного внутри
  диспетчеризации. Отрисованный так 401 не несёт заголовка `WWW-Authenticate`; для API, смотрящего в
  браузер, это обычно и нужно, поскольку реакция браузера на этот заголовок — нативное окно ввода
  учётных данных, которого не просит ни один UI на сессиях.
- Ответы 401 и 403 сообщают лишь то, что в доступе отказано. Различение «вам не хватает роли
  редактора» и «эта запись принадлежит другому тенанту» превращает эндпоинт в оракул для перебора
  записей; подробности принадлежат журналу аудита с ключом по субъекту.
- Если присутствует `security-spring-boot-starter`, он вносит маппер, и эти ответы возвращаются под
  кодами `ludwig.security.error.*` — теми же, что пишут его обработчики в цепочке фильтров, чтобы
  клиенту никогда не приходилось знать, какой слой ему отказал.

## Неверховые модули

`LocalizedException`, `ProblemStatus`, `ProblemMessages` и цепочка мапперов не зависят от Spring MVC,
а MVC-половина автоконфигурации обусловлена сервлетным веб-приложением. Пакетное задание или
консьюмер Kafka могут бросать и отрисовывать те же ошибки — в ответное сообщение, в лог, в
dead-letter-запись — без Spring MVC в classpath.

## Замечание о сборке

Поведение стартера зависит от одного флага компилятора — `-parameters`, выставленного в корневом pom.
Spring читает имена параметров метода, чтобы связать `@RequestParam` без повторения имени в
аннотации, а нарушение ограничения на уровне параметра может назвать отклонённый параметр только при
наличии этих имён в байткоде: без флага 400 сообщает об `arg0`.

## Модули, вносящие вклад в этот конвейер

| Модуль | Что вносит |
|---|---|
| [`odata-filter-spring-boot-starter`](../odata-filter-spring-boot-starter/README.ru.md) | `ODataFilterProblemMapper` и тексты `ludwig.odata.error.*`; его собственная advice отступает при наличии этого стартера |
| [`security-spring-boot-starter`](../security-spring-boot-starter/README.ru.md) | `SecurityProblemMapper` и bundle `ludwig.security.error.*`, которым уже пользовались его обработчики в цепочке фильтров |
| [`db-core`](../db-core/README.ru.md) | `DbCoreProblemMapper` и тексты `ludwig.db.error.*` — прежде всего `EntityNotFoundException` как 404, а не 500 |

## Раскладка пакетов

| Пакет | Что внутри |
|---|---|
| `problem` | `LocalizedException`, `ProblemStatus`, `ProblemDefinition`, `ProblemCodes`, `Violation` — словарь, ничего из этого не зависит от MVC |
| `problem` (конвейер) | `ProblemMessages`, `ProblemMessageBundle`, `ExceptionProblemMapper`, `ProblemMapperRegistry`, `ProblemDetailFactory` |
| `problem.mapper` | мапперы, поставляемые здесь, каждый обусловлен классом, который отображает |
| `web` | `ApiExceptionHandler`, `PageResponse` |
| `trace` | `TraceIdProvider`, `MdcTraceIdProvider` |
| `config` | три автоконфигурации и `WebCoreProperties` |
