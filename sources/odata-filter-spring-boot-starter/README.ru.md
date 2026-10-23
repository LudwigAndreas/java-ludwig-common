# odata-filter-spring-boot-starter

*[English](README.md) · **Русский***

OData `$filter` / `$top` / `$skip` / `$orderby` / `$count` для REST API на Spring Boot поверх Spring Data JPA.
Параметры разбираются ABNF-токенизатором Olingo и переводятся в типобезопасные предикаты QueryDSL с ограничениями
для продакшена: пределы глубины вложенности и длины, allow-list полей с запретом по умолчанию, включая связи, доступ к
полям по ролям, пределы размера страницы, серверный tie-breaker для детерминированной пагинации, программные
валидаторы, запись аудита каждого фильтра (пути и операторы, никогда значения) и опциональный эндпоинт обнаружения
для каждого вызывающего.

| | |
|---|---|
| Роль | starter |
| Зависит от (в репозитории) | [`audit-core`](../audit-core), [`web-core-spring-boot-starter`](../web-core-spring-boot-starter) |
| Используется | стартерами `export`, `user-settings`; обоими сервисами |
| Поверхности | параметры REST-запроса; опционально `GET <base-path>/{entity}` |

## Использование

```xml
<dependency>
    <groupId>ru.ludwigandreas</groupId>
    <artifactId>odata-filter-spring-boot-starter</artifactId>
</dependency>
```

### 1. Аннотируйте сущность

Фильтровать и сортировать можно только по аннотированным полям, включая связи.

```java
@Entity
@FilterPolicy(maxDepth = 4, maxPageSize = 100, defaultPageSize = 20,
        defaultOrderBy = "createdAt desc, id asc")
public class Product {

    @Filterable
    private String name;

    @Filterable(ops = {EQ, GT, GE, LT, LE})
    private BigDecimal price;

    @Filterable(name = "cost", roles = "ROLE_ADMIN")   // своё имя в API, только админы
    private BigDecimal supplierCost;

    private String internalNotes;                      // без аннотации -> никогда не фильтруется

    @Filterable                     // открывает @Filterable-поля Category как "category/code", ...
    @ManyToOne                      // неаннотированная связь - стена
    private Category category;
}
```

- **Переход по связям - по выбору.** Без `@Filterable` на связи поля целевой сущности здесь невидимы. `roles` на связи
  ограничивает всё поддерево.
- **`defaultOrderBy` делает пагинацию корректной.** Он **дописывается** к `$orderby` вызывающего (tie-breaker), поэтому
  заканчивайте его уникальной колонкой. Без полного порядка `$skip=0` и `$skip=20` могут повторять или пропускать
  строки на продакшен-объёмах. Его пути не требуют `@Filterable` и проверяются по сущности при первом разрешении
  политики.

### 2. Выполняйте в репозитории

```java
@Repository
@RequiredArgsConstructor
class ProductQueryRepositoryImpl implements ProductQueryRepository {

    private static final QProduct PRODUCT = QProduct.product;

    private final ODataQueryExecutor odataExecutor;

    @Override
    public ODataPage<Product> search(ODataQueryOptions options) {
        return odataExecutor.search(Product.class, options, ODataSearch.of(PRODUCT)
                // через AND и в выборку, и в подсчёт, чтобы страница и итог сходились - место для области данных
                .and(dataAccessGuard.predicate("product", DataAction.READ))
                // формирует только запрос содержимого (fetch join в подсчёте запрещён)
                .content(query -> query.leftJoin(PRODUCT.category).fetchJoin()));
    }
}
```

Один вызов делает предикат, сортировку, смещение, лимит и подсчёт (кроме `$count=false`).

**Передавайте сгенерированный экземпляр по умолчанию** (`QProduct.product`, алиас `product`). Другой алиас не падает в
JPQL - он соединяет таблицу саму с собой. Исполнитель отвергает несовпадающий алиас, называя оба.

Собственный запрос (проекция, оконная функция): берите сортировку из
`ODataPaths.orderSpecifiers(Product.class, query.pageable().getSort())`. Репозиторий `QuerydslPredicateExecutor` тоже
работает: `repository.findAll(query.predicate().orElse(null), query.pageable())`.

### 3. Передавайте параметры из контроллера вниз

```java
@GetMapping
public PageResponse<ProductResponse> search(ODataQueryOptions options) {
    ODataPage<ProductResponse> page = productService.search(options).map(mapper::toResponse);
    return PageResponse.of(page.content(), page.offset(), page.size(), page.totalElements());
}
```

```
GET /products?$filter=price lt 100 and category/code eq 'TOOLS'&$top=20&$orderby=name desc&$count=false
```

`ODataQueryOptions` несёт пять сырых параметров; ни сущность, ни тип QueryDSL не появляются выше репозитория
(`architecture-rules`: `web.controllers-do-not-expose-entities`, `layering.controllers-do-not-access-persistence`).
`ODataQueryOptionsOpenApiCustomizer` документирует параметры в OpenAPI при наличии springdoc. Его резолвер падает
только на неверном скалярном параметре (400). Принимаются алиасы без `$` для шлюзов, которые его портят
(`odata.filter.web.dollar-prefixed-parameters-only=true` их отключает).
[`crud-service-example`](../../services/crud-service-example) показывает всю схему.

`ODataFilterService` (разбор) не зависит ни от Spring MVC, ни от JPA, поэтому export может воспроизводить
сохранённые фильтры в рабочих потоках. `ODataQueryExecutor` условен на `JPAQueryFactory`.

**Ответ пагинации**: `offset` авторитетен (у невыровненного `$skip` нет целой страницы); `totalElements` и
`totalPages` отсутствуют при `$count=false` (запрос подсчёта не выполняется).

### Собственная валидация

```java
@Bean
FilterValidator orderDateRangeValidator() {
    return context -> {
        if (context.entityType() == Order.class && spansMoreThanAYear(context.root())) {
            throw new FilterValidationException("date range filters on Order may not span more than 366 days");
        }
    };
}
```

Выполняется после встроенных проверок, до построения предиката.

### Роли

Из полномочий `SecurityContextHolder`, если есть Spring Security; иначе реализуйте бин `FilterPrincipalResolver`.

### Поддерживаемая грамматика

`and`, `or`, `not`, скобки; `eq ne gt ge lt le`; `in (v1, ...)`; `contains`, `startswith`, `endswith`; пути через
аннотированные связи to-one с `/`. Литералы: строка, целое, decimal, double, boolean, дата, date-time-offset, GUID,
enum. Намеренно не поддерживаются: арифметика, `any`/`all`, `$select`/`$expand`, прочие функции. `$count` должен быть
`true` или `false` (иное - 400 с указанием `$count`).

Ловушки SQL: вложенный путь становится inner join, поэтому строки с пустой связью выпадают (также при `ne`/`not`);
`not (status eq 'X')` никогда не совпадает со строкой, где status - null.

### Эндпоинт обнаружения (по выбору)

```yaml
odata:
  filter:
    metadata:
      base-path: /api/v1/filter-metadata   # не задан = эндпоинта нет
```

```java
@FilterPolicy(maxPageSize = 100, defaultOrderBy = "createdAt desc, id asc",
        metadataName = "product")          // не задан = сущность ничего не публикует (404)
public class ProductEntity { }
```

```
GET /api/v1/filter-metadata/product
```

```json
{
  "entity": "product", "maxDepth": 4, "maxPageSize": 100, "defaultPageSize": 20,
  "maxNestedPropertyDepth": 2, "defaultOrderBy": "createdAt desc, id asc",
  "properties": [
    { "path": "category/code", "type": "string",  "operators": ["contains", "endswith", "eq", "ge", "gt", "in", "le", "lt", "ne", "startswith"], "sortable": true },
    { "path": "name",          "type": "string",  "operators": ["contains", "endswith", "eq", "startswith"], "sortable": true },
    { "path": "price",         "type": "decimal", "operators": ["eq", "ge", "gt", "le", "lt", "ne"], "sortable": true }
  ]
}
```

- Каждый вызывающий получает свой документ: недоступные ему пути **отсутствуют**; имён ролей нет.
- Неопубликованная сущность и неизвестное имя одинаково дают 404.
- Это проекция разрешённой политики (тест проверяет согласие с путём запроса), а не второй список.
- Не называется `$metadata`: это не документ OData CSDL.
- Без кэша: политика мемоизирована; кэш с ключом по ролям стоил бы дороже обхода.

## Конфигурация

Префикс `odata.filter` (глобальные значения; `@FilterPolicy` переопределяет по сущности).

| Свойство | По умолчанию | Переопределение | Смысл |
|---|---|---|---|
| `odata.filter.max-depth` | `4` | `maxDepth` | Глубина вложенности фильтра |
| `odata.filter.max-page-size` | `200` | `maxPageSize` | Максимальный `$top` |
| `odata.filter.default-page-size` | `20` | `defaultPageSize` | |
| `odata.filter.max-nested-property-depth` | `2` | `maxNestedPropertyDepth` | Глубина перехода по связям |
| `odata.filter.max-expression-length` | `2048` | - | Длина сырого `$filter` |
| `odata.filter.page-size-exceeded-strategy` | `REJECT` | - | `REJECT` или `CLAMP` |
| - | - | `defaultOrderBy` | Tie-breaker сортировки |
| - | - | `metadataName` | Имя для обнаружения |
| `odata.filter.web.dollar-prefixed-parameters-only` | `false` | - | Игнорировать алиасы без `$` |
| `odata.filter.metrics.enabled` | `true` | - | |
| `odata.filter.metadata.base-path` | не задан | - | Префикс эндпоинта обнаружения; не задан = отсутствует |
| `odata.filter.metadata.enabled` | `true` | - | |

По полю (`@Filterable`): `ops`, `roles`, `sortable`, `name`.

`odata.filter.web.problem-detail-advice-enabled` всё ещё связывается классом свойств, но ни на что не влияет: ошибки
всегда отрисовывает web-core.

## Развёртывание

Развёртывать нечего. Если эндпоинт обнаружения включён, решите, публичен ли он, и добавляйте его в
`ludwig.security.public-paths` только осознанно.

## Наблюдаемость

| Метрика | Тип | Теги | Смысл |
|---|---|---|---|
| `odata.filter.applied` | counter | `entityType` | Разобранные и переведённые фильтры |
| `odata.filter.rejected` | counter | `entityType`, `reason` (простое имя исключения) | Отказы. Устойчивый всплеск = ошибка клиента или зондирование |
| `odata.filter.parse.duration` | timer | `entityType` | Весь разбор при любом исходе |
| `odata.filter.metadata.served` | counter | `entityType` | Отданные документы обнаружения (отличает добросовестных клиентов от зондирующих) |

**Логи.** `INFO` при старте со списком опубликованных для обнаружения сущностей.

**Рекомендуемые алерты:** высокий `increase(odata_filter_rejected_total{reason="FilterAccessDeniedException"}[15m])`
по одному вызывающему/сущности (зондирование) и всплеск отказов после релиза клиента.

## Аудит

Каждый применённый фильтр записывается:

| Поле | Значение |
|---|---|
| category | `query` |
| action | `query.filtered` |
| resource | сущность |
| attributes | `properties` (пути) и `operators` (например, `email eq, name contains`) - **никогда значения** |

Значения не хэшируются и не обрезаются (хэш обратим для малых множеств; обрезка раскрывает идентифицирующий префикс);
`FilterSummary` строится по AST и никогда не читает литерал. Приёмник вызывается без `try`/`catch`: выставьте
`ludwig.audit.failure.by-category.query` в `FAIL_OPERATION`, если чтение не должно проходить без записи аудита.
Также публикуется внутрипроцессное `FilterAppliedEvent` (метрики, инвалидация кэша).

## Runbook

| Симптом | Вероятная причина | Что делать |
|---|---|---|
| 403 `ludwig.odata.error.field-forbidden` с `property` | Полю нужна роль, которой нет у вызывающего | Ожидаемо; выдать роль или убрать поле из фильтра |
| 400 `unfilterable-field` | Поле или связь без `@Filterable` | Аннотировать, если оно должно фильтроваться |
| 400 `depth-exceeded` / `page-size-exceeded` / `syntax` / `invalid-option` | Пределы или грамматика | Исправить запрос; менять `@FilterPolicy` только осознанно |
| Повторы или пропуски строк между страницами | Нет `defaultOrderBy` или он не заканчивается уникальной колонкой | Добавить уникальный tie-breaker |
| Строки молча пропадают при фильтре по `association/x` | Inner join отбрасывает пустые связи | Ожидаемое поведение SQL; фильтровать иначе |
| Результаты размножены / неверны | Не алиас по умолчанию у `Q` | Использовать `QEntity.entity` |
| Медленные списочные эндпоинты | Подсчёт по большим таблицам | Клиенты отправляют `$count=false`, когда итоги не нужны |

Коды ошибок: `ludwig.odata.error.{syntax, depth-exceeded, field-forbidden, unfilterable-field, page-size-exceeded,
invalid-option, invalid-query, validation, metadata-not-published}`, локализованы через
`i18n/ludwig-odata-filter-messages`, переопределяются в бандле сервиса.

## Тестирование

`mvn test`: парсер, разрешение политики, проверка полей/ролей, построение предиката. `ODataFilterIntegrationTest`
(Docker, PostgreSQL): HTTP → предикат → БД. `NoLocalAdviceTest` валит сборку, если в модуле появится
`@RestControllerAdvice`.

## Проектные решения

- **Запрет по умолчанию**, включая связи и эндпоинт обнаружения.
- **Разбор в репозитории, а не в резолвере аргумента контроллера**: нет сущностей в сигнатурах контроллеров, OpenAPI
  документирует параметры, политика оценивается после `@PreAuthorize`.
- **Один конвейер проблем** (web-core), поэтому ошибки локализованы и переопределяемы; своего advice нет.
- **Ограниченная грамматика** ради проверяемой поверхности.
- **Аудит записывает структуру, а не содержимое.**
