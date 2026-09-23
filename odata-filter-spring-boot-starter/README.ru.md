# odata-filter-spring-boot-starter

*[English](README.md) · **Русский***

Промышленная поддержка OData `$filter`/`$top`/`$skip`/`$orderby` для REST API на Spring Boot и
Spring Data JPA. Разбирает параметры OData-запроса в типобезопасные предикаты QueryDSL над вашими
JPA-сущностями — с теми ограничителями, которые нужны боевому API: пределом вложенности фильтра,
белым списком полей с запретом по умолчанию, распространяющимся и на ассоциации, ролевым доступом к
полям, максимальным размером страницы, серверной сортировкой-тайбрейкером, благодаря которой
постраничная выдача остаётся детерминированной, и точкой для программной валидации. Добавьте
зависимость, разметьте сущности и вызывайте её из репозитория.

## Зачем

[`odata-server-api`](https://olingo.apache.org)/`odata-server-core` и `olingo-jpa-processor-v4` —
хорошие строительные блоки, но требуют реальной работы, чтобы стать безопасными для мультитенантного
боевого API: неограниченный `$filter` — вектор отказа в обслуживании, а в модели OData нет понятия
«по этому полю фильтровать может только администратор». Этот стартер использует ABNF-токенизатор
самого Olingo (`UriTokenizer`) для разбора стандартной грамматики OData `$filter`, затем переводит её
в `Predicate` QueryDSL через `PathBuilder` — без шага генерации `QEntity` процессором аннотаций — и
применяет политику до того, как построен хоть один JPQL-запрос.

## Быстрый старт

```xml
<dependency>
    <groupId>ru.ludwigandreas</groupId>
    <artifactId>odata-filter-spring-boot-starter</artifactId>
    <version>1.0.0</version>
</dependency>
```

Разметьте поля сущности, по которым разрешены фильтрация и сортировка. Всё остальное, включая
ассоциации, по умолчанию недостижимо:

```java
@Entity
@FilterPolicy(maxDepth = 4, maxPageSize = 100, defaultPageSize = 20,
        defaultOrderBy = "createdAt desc, id asc")
public class Product {

    @Filterable
    private String name;

    @Filterable(ops = {EQ, GT, GE, LT, LE})
    private BigDecimal price;

    @Filterable(name = "cost", roles = "ROLE_ADMIN")   // наружу — под собственным именем
    private BigDecimal supplierCost;                   // и только для администраторов

    private String internalNotes;                      // без аннотации -> никогда не фильтруется

    @Filterable                     // открывает собственные @Filterable-поля Category как
    @ManyToOne                      // "category/code", "category/name", ...; без аннотации
    private Category category;      // ассоциация — глухая стена
}
```

Два момента здесь стоит перечитать дважды.

**Обход ассоциаций включается явно, как и всё остальное.** `@Filterable`-поля `Category` выбирались
для эндпоинта *самой Category*; без аннотации на поле `category` они здесь не видны. Будь обход
автоматическим, открытие одного поля у часто используемой сущности расширяло бы поверхность
фильтрации каждого эндпоинта, который до неё дотягивается. `roles` на ассоциации закрывает всё
поддерево: вызывающему нужны и роли ассоциации, и роли самого вложенного поля.

**`defaultOrderBy` — то, что делает постраничную выдачу корректной.** Запрос без полного порядка
позволяет базе возвращать строки как ей угодно, поэтому `$skip=0` и `$skip=20` — два независимых
запроса, которые могут показать одну и ту же строку дважды или не показать вовсе; такая ошибка
проявляется только на боевых объёмах. Эта сортировка *дописывается* к `$orderby` вызывающего, а не
используется лишь как запасная, поэтому разрывает ничьи и при `$orderby=status`. Заканчивайте её
уникальной колонкой. Эти пути — конфигурация сервера, а не ввод клиента, поэтому `@Filterable` им не
нужен (суррогатный ключ, по которому клиенту фильтровать нельзя, — обычный выбор), но они
проверяются по полям сущности при первом разрешении политики, так что опечатка падает явно, а не
доходит до базы.

Разбирайте параметры вызывающего **в том слое, которому принадлежит сущность**, — в репозитории.

```java
@Repository
@RequiredArgsConstructor
class ProductQueryRepositoryImpl implements ProductQueryRepository {

    private static final QProduct PRODUCT = QProduct.product;

    private final JPAQueryFactory queryFactory;
    private final ODataFilterService filterService;

    @Override
    public Page<Product> search(ProductSearchCriteria criteria) {   // criteria — исходные строки
        ODataQuery<Product> query = filterService.parse(
                Product.class, criteria.filter(), criteria.top(), criteria.skip(), criteria.orderBy());
        Pageable pageable = query.pageable();

        List<Product> content = queryFactory.selectFrom(PRODUCT)
                .where(query.predicate())
                .orderBy(orderSpecifiers(pageable.getSort()))
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        return PageableExecutionUtils.getPage(content, pageable, () -> count(query.predicate()));
    }
}
```

Репозиторий Spring Data подходит не хуже и короче: `query.predicate()` строится динамически через
`PathBuilder` из QueryDSL по соглашению об алиасах `SimpleEntityPathResolver` (простое имя класса с
маленькой буквы), поэтому генерация `QProduct` для этого не нужна:

```java
Page<Product> page = repository.findAll(query.predicate(), query.pageable());
```

В написанных вручную запросах QueryDSL следите именно за алиасом: корень, объявленный как
`new QProduct("p")`, адресует другой алиас, чем предикат, и вместо ошибки получится молчаливое
декартово соединение.

Контроллер принимает параметры простыми строками и передаёт их вниз неразобранными — так ни
JPA-сущность, ни типы QueryDSL не поднимаются выше репозитория:

```java
@GetMapping
public PageResponse<ProductResponse> search(
        @RequestParam(name = "$filter", required = false) String filter,
        @RequestParam(name = "$orderby", required = false) String orderBy,
        @RequestParam(name = "$top", required = false) Integer top,
        @RequestParam(name = "$skip", required = false) Integer skip) {
    Page<Product> page = productService.search(new ProductQuery(filter, orderBy, top, skip));
    return PageResponse.of(page.map(mapper::toResponse));
}
```

```
GET /products?$filter=price lt 100 and category/code eq 'TOOLS'&$top=20&$orderby=name desc
```

[`crud-service-example`](../crud-service-example/README.ru.md) — это та же схема целиком:
контроллер -> сервис -> репозиторий, где область данных вызывающего попадает в тот же `WHERE`, что
и `$filter`.

### Почему не связывать напрямую с параметром контроллера

Раньше модуль документировал однострочник: объявить в контроллере параметр `ODataQuery<Product>` и
позволить резолверу аргументов его заполнить. Резолвер никуда не делся, помечен `@Deprecated` и
**выключен по умолчанию** (вернуть — `odata.filter.web.argument-resolver-enabled=true`). Четыре
причины, по которым он ушёл:

- `ODataQuery<Product>` называет JPA-сущность в сигнатуре контроллера, что отвергает правило
  `web.controllers-do-not-expose-entities` из
  [`architecture-rules`](../architecture-rules/README.ru.md): оно проверяет и аргументы обобщённых
  типов, поэтому параметр ловится даже когда возвращается DTO.
- Отдаёт он `Predicate` QueryDSL, выполнить который может только репозиторий. Значит, контроллер
  либо держит репозиторий сам — это `layering.controllers-do-not-access-persistence` и запрос вне
  транзакции, — либо передаёт предикат вниз, и тогда сущность оказывается в API сервисного слоя.
- springdoc не умеет описать такой параметр, поэтому `$filter`, `$top`, `$skip` и `$orderby`
  пропадают из документа OpenAPI. Объявленные как `@RequestParam`, они документируют себя сами.
- Разрешение аргументов выполняется до `@PreAuthorize` обработчика, поэтому вызывающий, которому
  эндпоинт вообще не положен, всё равно узнаёт из 403, какие поля фильтруются.

Ничего при этом не теряется: `ODataFilterService` не зависит от Spring MVC — именно это позволяет
пакетному заданию или резолверу GraphQL повторно применить сохранённый фильтр.

## Что ограничивается и где настраивается

| Предмет | Глобальное умолчание (`odata.filter.*`) | Переопределение на сущности (`@FilterPolicy`) |
|---|---|---|
| Вложенность фильтра | `max-depth` (4) | `maxDepth` |
| Максимальный размер страницы (`$top`) | `max-page-size` (200) | `maxPageSize` |
| Размер страницы по умолчанию | `default-page-size` (20) | `defaultPageSize` |
| Глубина обхода ассоциаций | `max-nested-property-depth` (2) | `maxNestedPropertyDepth` |
| Длина исходной строки `$filter` | `max-expression-length` (2048) | — |
| Сортировка, дописываемая к `$orderby` | — | `defaultOrderBy` |
| `$top` больше максимума | `page-size-exceeded-strategy` (`REJECT`/`CLAMP`) | — |

На уровне поля, через `@Filterable`: какие операторы разрешены (`ops`), какие роли могут им
пользоваться (`roles`) и можно ли по нему сортировать (`sortable`).

Для всего, что декларативная политика выразить не может («фильтры по дате в Order не могут охватывать
больше года»), зарегистрируйте бин `FilterValidator` — он выполняется после встроенных проверок и до
построения предиката:

```java
@Bean
FilterValidator orderDateRangeValidator() {
    return context -> {
        if (context.entityType() == Order.class && spansMoreThanAYear(context.root())) {
            throw new FilterValidationException("фильтры по диапазону дат в Order не могут охватывать более 366 дней");
        }
    };
}
```

Каждый применённый фильтр также публикует `FilterAppliedEvent` (тип сущности, исходный фильтр,
построенный предикат, роли вызывающего) — напишите на него `@EventListener`, чтобы вести аудит.

## Разрешение ролей

Если Spring Security есть в classpath, роли автоматически читаются из `GrantedAuthority` в
`SecurityContextHolder`. Иначе (или чтобы брать роли откуда-то ещё, например из заголовка, добавленного
шлюзом) реализуйте `FilterPrincipalResolver` и объявите его бином.

## Поддерживаемая грамматика `$filter`

`and`, `or`, `not`, группировка скобками; сравнения `eq ne gt ge lt le`; `in (v1, v2, ...)`; строковые
функции `contains`/`startswith`/`endswith`; пути свойств с обходом размеченных to-one-ассоциаций через
`/` (например, `department/manager/name`). Литералы строк, целых, decimal, double, булевых значений,
дат, datetimeoffset, GUID и перечислений. Намеренно не поддерживаются — ради ограниченной, обозримой
поверхности: арифметика, `any`/`all`, `$select`/`$expand` и прочие функции OData.

`$count` тоже не реализован и игнорируется, а не отвергается. Раньше он разбирался во флаг на
`ODataQuery`, с которым ничего нельзя было сделать: `Page` всегда несёт общее число, как и
`PageResponse`, который возвращают такие эндпоинты, поэтому `$count=false` ничего не давал
вызывающему, лишь выглядел так, будто даёт. Пропустить подсчёт — решение репозитория (вернуть
`Slice` или считать условно), а не то, о чём может попросить параметр запроса.

Ещё две ловушки, обе унаследованы от SQL, а не от этой библиотеки: фильтр по вложенному пути
(`department/name eq 'Sales'`) превращается в inner join, поэтому строки с пустой ассоциацией
выпадают из результата — в том числе под `ne` и `not`, — а трёхзначная логика означает, что
`not (status eq 'X')` никогда не совпадёт со строкой, у которой status равен null.

## Ответы об ошибках

Каждое исключение этого модуля наследует `ODataFilterException`, а то, как оно доходит до клиента,
зависит от одного: есть ли в classpath
[`web-core-spring-boot-starter`](../web-core-spring-boot-starter/README.md).

**Если есть** — а это предполагаемая схема — модуль отдаёт в общий конвейер проблем того стартера
`ODataFilterProblemMapper` и bundle `i18n/ludwig-odata-filter-messages`. Ошибки запроса тогда
возвращаются **на языке вызывающего**, под кодами `ludwig.odata.error.*`, ровно в той же форме
RFC 9457, что и любая другая ошибка сервиса, а поле-нарушитель лежит в члене `property`, который
клиент читает, не разбирая предложение:

```json
{
  "type": "urn:ludwig:problem:ludwig.odata.error.field-forbidden",
  "title": "Доступ запрещён",
  "detail": "У вас нет прав на фильтрацию или сортировку по одному из полей этого выражения. См. поле \"property\".",
  "status": 403,
  "code": "ludwig.odata.error.field-forbidden",
  "property": "supplierCost"
}
```

Переформулировать что угодно можно, объявив тот же ключ в собственном bundle: конвейер сначала
смотрит в bundle приложения.

**Если нет**, вместо этого регистрируется старая advice `ODataFilterExceptionHandler`, так что сервис,
не использующий тот стартер, всё равно получает ответы RFC 7807 — на английском, из собственных
сообщений исключений, написанных для разработчика. Выключается через
`odata.filter.web.problem-detail-advice-enabled=false`, если вы хотите обрабатывать эти исключения
сами.

Вместе они не сосуществуют никогда. Та advice была первым ответом модуля, и у неё был структурный
изъян: библиотека может отдавать только тот текст, с которым была скомпилирована, поэтому её
английские сообщения оставались единственной непереводимой частью в остальном локализованного API — а
единственным выходом было выключить её и вручную переобработать все семь типов исключений, что каждый
потребитель затем и делал, одинаково. Маппер вместе с bundle убирает обе половины: смысл объявлен
здесь один раз, текст поставляется отсюда на всех поддерживаемых модулем языках, а приложение
по-прежнему может переопределить что угодно.

## Метрики

Опционально (только при наличии Micrometer в classpath, включено по умолчанию через
`odata.filter.metrics.enabled`): `odata.filter.applied` считает успешно разобранные и переведённые
фильтры, `odata.filter.rejected` — каждое отклонение с тегами `entityType` и `reason` (простое имя
отклонившего исключения: `FilterSyntaxException`, `FilterAccessDeniedException`,
`PageSizeExceededException`, …), а `odata.filter.parse.duration` измеряет весь вызов `parse`
независимо от исхода. Устойчивый всплеск `rejected` — это либо баг клиента, либо чей-то перебор в
поисках того, что фильтруется; и то и другое стоит алерта.

## Тестирование

`mvn test` прогоняет модульные тесты (парсер, разрешение политики, проверка полей и ролей, построение
предиката) без внешних зависимостей. `ODataFilterIntegrationTest` дополнительно поднимает настоящий
контейнер PostgreSQL через Testcontainers и проходит весь путь HTTP → предикат → Postgres; ему нужен
работающий демон Docker.
