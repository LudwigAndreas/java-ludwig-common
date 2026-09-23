# crud-service-example

*[English](README.md) · **Русский***

CRUD-микросервис боевой формы — каталог товаров, — собранный из собственных модулей этого
репозитория. Он существует, чтобы показать, как [`db-core`](../db-core/README.ru.md),
[`odata-filter-spring-boot-starter`](../odata-filter-spring-boot-starter/README.ru.md),
[`outbox-spring-boot-starter`](../outbox-spring-boot-starter/README.ru.md),
[`security-spring-boot-starter`](../security-spring-boot-starter/README.ru.md) и
[`identity-projection-spring-boot-starter`](../identity-projection-spring-boot-starter/README.ru.md)
складываются в один сервис и как выглядит слоистая, свободная от шаблонного кода и проверяемая на
этапе компиляции реализация поверх них.

## Что он демонстрирует

| Требование | Как |
|---|---|
| Никакого рукописного шаблонного кода | Lombok на сущностях, MapStruct на каждом преобразовании между моделями, db-core для идентификаторов, аудита и версий |
| Типобезопасный доступ к данным | Только QueryDSL-JPA против сгенерированных Q-типов: ни JDBC, ни строк JPQL/SQL, ни производных методов запросов |
| Три слоя моделей | `web.dto` (провод) → `service.model` (домен) → `repository.entity` (JPA), каждый переход — сгенерированным маппером |
| Локализация | Один bundle сообщений питает и сообщения валидации, **и** тела ошибок RFC 9457, разрешаемые на запрос по `Accept-Language`, — всё это из web-core-стартера, который сервис настраивает, а не реализует |
| Безопасный API запросов | OData `$filter`/`$orderby`/`$top`/`$skip`, ограниченный политикой на сущности по полям и ролям |
| Надёжные события | Каждая запись фиксирует своё событие в транзакционном outbox в той же транзакции |
| Доступ на уровне ресурса | `@PreAuthorize` на каждом эндпоинте; роли берутся из локальной проекции потока пользователей OIDC, никогда из claim-ов токена |
| Доступ на уровне данных | Область видимости вызывающего вклеивается в поисковый запрос через AND и перепроверяется при каждой загрузке по id — один бин `DataScopeMapping` и есть весь код безопасности этого сервиса |

## Архитектурный тест

Соглашения, которые демонстрирует сервис, — три слоя моделей, ни одной сущности за пределами
сервисного слоя, доступ к данным только через QueryDSL, транзакции в сервисном слое, чтение
конфигурации в одном месте — здесь не только описаны, но и проверяются. `ArchitectureTest` включает
общую библиотеку [`architecture-rules`](../architecture-rules/README.md):

```java
@AnalyzeArchitecture(
        packagesOf = CatalogApplication.class,
        enable = "domain-isolation",
        disable = {"kafka", "storage"})
class ArchitectureTest extends ArchitectureRulesTest {
}
```

39 правил прогоняются по байткоду модуля на каждой сборке, каждое — отдельным именованным тестом.
Kafka и объектное хранилище выключены, потому что у сервиса нет ни того, ни другого. Имена, которые
может дать только сервис (базовая сущность из `db-core`, базовое исключение из `web-core`), объявлены
в `src/test/resources/architecture-rules.properties`, и каждый прогон оставляет после себя сводку в
консоли и `target/architecture-report.json`.

## Как запустить

```bash
docker run --rm -e POSTGRES_DB=catalog -e POSTGRES_USER=catalog -e POSTGRES_PASSWORD=catalog \
  -p 5432:5432 postgres:16-alpine@sha256:cf78e76683b9ca8c5733cbbdce6c9262b45b6767934dd0a95e671f9a0fc20685

mvn install -DskipTests                      # один раз: локально публикует соседние модули
mvn -pl crud-service-example spring-boot:run \
  -Dspring-boot.run.profiles=local
```

Liquibase создаёт схему при старте — таблицы этого сервиса, модуля outbox и проекции identity, —
включая три справочные категории, используемые в примерах ниже.

Профиль `local` подменяет то, чего на ноутбуке нет: провайдера идентичности, слой сессий, брокера
Kafka. События outbox копятся строками `PENDING` вместо отправки, проекция пользователей не
питается, а **вызывающий берётся из заголовков запроса** — см. `LocalAuthenticationConfig`, который
закрыт и профилем, и отдельным переключателем, потому что это ровно тот чёрный ход, каким и
выглядит. Сама авторизация при этом не обходится: принципал, построенный из этих заголовков, проходит
те же проверки `@PreAuthorize` и те же области данных, что и выпущенный из настоящего токена, — из-за
чего эксперимент с ролями ниже и имеет смысл.

```bash
ADMIN=(-H 'X-Local-Subject: alice' -H 'X-Local-Roles: ROLE_CATALOG_ADMIN')
EDITOR=(-H 'X-Local-Subject: bob'  -H 'X-Local-Roles: ROLE_CATALOG_EDITOR')
PARTNER=(-H 'X-Local-Subject: acme' -H 'X-Local-Roles: ROLE_CATALOG_PARTNER' -H 'X-Local-Type: PARTNER')

# создание
curl -sS -X POST localhost:8080/api/v1/products "${ADMIN[@]}" -H 'Content-Type: application/json' -d '{
  "sku": "HAMMER-1", "name": "Hammer", "description": "Claw hammer, 450g",
  "price": 19.99, "supplierCost": 9.00, "status": "ACTIVE", "stockQuantity": 10,
  "categoryId": "11111111-1111-1111-1111-111111111111"}'

# поиск
curl -sS -G localhost:8080/api/v1/products "${ADMIN[@]}" \
  --data-urlencode '$filter=price lt 100 and category/code eq '"'"'TOOLS'"'"'' \
  --data-urlencode '$orderby=price desc' --data-urlencode '$top=20'

# обновление (version — тот, который вы прочитали последним; любой другой даёт 409)
curl -sS -X PUT localhost:8080/api/v1/products/$ID "${ADMIN[@]}" -H 'Content-Type: application/json' -d '{
  "name": "Hammer Pro", "price": 42.50, "status": "ACTIVE", "stockQuantity": 3,
  "categoryId": "11111111-1111-1111-1111-111111111111", "version": 0}'

# та же ошибка, по-русски
curl -sS localhost:8080/api/v1/products/00000000-0000-0000-0000-000000000000 "${ADMIN[@]}" \
  -H 'Accept-Language: ru'
```

Теперь поменяйте только вызывающего и посмотрите, как те же эндпоинты отвечают иначе:

```bash
# вообще без вызывающего -> 401, проблемой RFC 9457 на языке вызывающего
curl -sS localhost:8080/api/v1/products

# партнёр создаёт товар: строка штампуется его кодом партнёра, взятым из принципала,
# и никогда из тела запроса
curl -sS -X POST localhost:8080/api/v1/products "${PARTNER[@]}" -H 'Content-Type: application/json' -d '{
  "sku": "ACME-1", "name": "Partner drill", "price": 120.00, "status": "ACTIVE",
  "stockQuantity": 4, "categoryId": "11111111-1111-1111-1111-111111111111"}'

# ...и это единственный товар, который он видит. Итог тоже верен, потому что область — часть
# WHERE, а не фильтр, применённый к странице потом.
curl -sS localhost:8080/api/v1/products "${PARTNER[@]}"

# чтение товара администратора по id -> 403, ничего не сообщающий о его существовании
curl -sS localhost:8080/api/v1/products/$ID "${PARTNER[@]}"

# редактор читает весь каталог, но пишет только созданное им (политика: read ALL, write OWN)
curl -sS localhost:8080/api/v1/products/$ID "${EDITOR[@]}"                     # 200
curl -sS -X DELETE localhost:8080/api/v1/products/$ID "${EDITOR[@]}"           # 403: удаление только для администратора
```

## Три модели и зачем

```
CreateProductRequest / ProductResponse      web.dto           что обещает API
            │  ProductDtoMapper (MapStruct)
NewProduct / ProductUpdate / Product        service.model     с чем работает бизнес-логика
            │  ProductEntityMapper (MapStruct)
ProductEntity                               repository.entity что хранит база
```

Каждую границу пересекает сгенерированный маппер, а сборка запускает MapStruct с
`unmappedTargetPolicy=ERROR`: если поле добавлено в одну модель и не перенесено, сборка падает, а не
молча возвращает `null`. То же касается трёх перечислений `ProductStatus`/`ProductState`/
`ProductStatusDto` — выбрасывание константы из одного из них становится ошибкой компиляции.

Разделение окупается вполне конкретно: `supplierCost` хранится и фильтруется, но не имеет поля в
`ProductResponse`, поэтому утечь не может; SKU неизменяем, поэтому в `ProductUpdate` для него нет
поля, а маппер явно его игнорирует; и `Page` никогда не доходит до клиента, поэтому JSON-форма
Spring Data не входит в контракт этого API.

## Только проверяемые на компиляции запросы

Каждый запрос живёт в `ProductQueryRepositoryImpl` и написан через `JPAQueryFactory` против
сгенерированного `QProductEntity`:

```java
queryFactory.selectOne()
        .from(PRODUCT)
        .where(Predicates.allOf(
                PRODUCT.sku.equalsIgnoreCase(sku),
                Predicates.whenNotNull(excludedId, PRODUCT.id::ne)))
        .fetchFirst() != null;
```

Здесь намеренно нет ни производных методов вида `findBySkuIgnoreCase`, ни строк `@Query`: они
разбираются из имени или строки, поэтому переименованное или сменившее тип поле проявляется отказом
на старте или в рантайме. Переименуйте `sku` в сущности — и этот код просто перестанет
компилироваться. `Predicates.allOf`/`whenNotNull` приходят из db-core и делают необязательные
критерии null-безопасными.

Единственный динамический в рантайме вход — клиентский `$filter`, и он ограничивается до того, как
дойдёт до запроса: `ODataFilterService` порождает только те пути, которые разрешают аннотации
`@Filterable` сущности (с операторами, ролями и сортируемостью по полям), в пределах глубины и
размера страницы из `@FilterPolicy`/`odata.filter.*`. Всё прочее отклоняется: 400 для неизвестного или
нефильтруемого поля, 403 для поля, не покрытого ролями вызывающего.

## Локализация

Вся она приходит из [`web-core-spring-boot-starter`](../web-core-spring-boot-starter/README.ru.md).
У сервиса нет ни собственной advice для исключений, ни настройки `MessageSource`, ни резолвера
локали — он вносит только собственный словарь:

- четыре наследника `LocalizedException`, каждый называет исход (`ProblemStatus.CONFLICT`), код
  сообщения и аргументы, но никогда не форматированную строку;
- `i18n/messages[_ru].properties`, содержащие **только** коды этого сервиса (`error.product.*`,
  `error.category.*`) и его ключи Bean Validation (`{catalog.validation.product.sku.required}`).

Всё остальное разрешает конвейер стартера. Общие HTTP-ошибки (валидация, 401, 403, 404, конфликт,
500) поставляются web-core, ошибки запросов — OData-стартером, формулировки 401/403 —
security-стартером; каждый вносит свой bundle сообщений, а разрешение сначала смотрит в bundle **этого
сервиса**, поэтому любое их сообщение можно переформулировать, добавив ключ сюда. Настраивать нечего и
переобрабатывать нечего.

Последний пункт и есть причина существования стартера, а этот сервис — место, где проблема
проявилась. Его `ApiExceptionHandler` нёс комментарий, объясняющий, что
`odata.filter.web.problem-detail-advice-enabled` выставлен в `false`, потому что сообщения того
стартера были только на английском, — и сервис вручную переобрабатывал все семь типов его исключений,
чтобы сохранить единую локализованную форму ошибки. Этого флага больше нет в `application.yml`, как
нет и advice, `LocalizationConfig` и DTO `PageResponse`: четыре файла шаблонного кода, которые
копировал каждый сервис.

Каждый ответ — это `ProblemDetail` по RFC 9457 со стабильным машиночитаемым `code` рядом с
локализованными `title`/`detail`, чтобы клиенты ветвились по коду и показывали текст. Он также несёт
`traceId` — именно это делает выполнимой фразу «назовите trace id» в сообщении о пятисотке.

## События

`ProductServiceImpl` публикует в outbox в той же транзакции, что и запись, с ключом упорядочивания на
товар (собственные события товара остаются в порядке) и ключом идемпотентности
`id:eventType:version` (повторённая транзакция переиспользует строку, а не выпускает изменение
дважды). Маршрутизация, отправка, повторы с backoff и dead-letter — работа модуля outbox, см. его
README.

## Безопасность: два слоя, два места

Авторизация здесь разделена так, как настаивает [модуль
безопасности](../security-spring-boot-starter/README.ru.md), и разделение видно в раскладке файлов:

| Слой | Вопрос | Где в сервисе |
|---|---|---|
| Ресурс | может ли этот вызывающий пользоваться этим эндпоинтом? | `@PreAuthorize` на `ProductController` |
| Данные | какие товары и можно ли трогать **этот**? | ограниченный запрос в `ProductQueryRepositoryImpl`, защита в `ProductServiceImpl` |

Правила по строкам намеренно **не** живут в аннотациях контроллера. Положите их туда — и они окажутся
применены на одном эндпоинте и забыты на следующем; положите их там, где достаются строки, — и любой
путь к этим строкам пройдёт через них.

Единственный код безопасности, который пишет этот сервис, лежит в `SecurityConfig` и покрывает все
три формы правил, какие бывают у настоящего каталога:

```java
@Bean
DataScopeMapping<ProductEntity> productDataScopeMapping() {
    QProductEntity product = QProductEntity.productEntity;
    return DataScopeMapping.forResource("product", ProductEntity.class)
            // владение: одна колонка, одно значение
            .owner(product.createdBy, ProductEntity::getCreatedBy)
            .partner(product.supplierPartnerId, ProductEntity::getSupplierPartnerId)
            // членство: один товар, много наблюдателей — «всё, где я упомянут»
            .bindCollection(WATCHING, product.watcherSubjects.any(), ProductEntity::getWatcherSubjects)
            .build();
}
```

`watcherSubjects` — это `@ElementCollection`, поэтому `any()` — обход to-many из QueryDSL, который JPA
рендерит коррелированным `EXISTS`. Join размножил бы один товар по числу наблюдателей и раздул бы и
страницу, и её итог; `EXISTS` оставляет постраничность и подсчёт корректными. На стороне загрузки
проверка — пересечение: товар подходит, когда **любой** из его наблюдателей и есть вызывающий.

Значение для этой оси не может прийти из `application.yml`: настраиваемый язык политик сравнивает
измерение с чем-то, что принципал уже несёт (его subject, тенант, партнёр), а этому правилу нужен
собственный subject вызывающего как значение **пользовательской** оси. Для этого и существует
`DataScopeProvider`, и он умещается в десять строк:

```java
@Bean
DataScopeProvider watcherDataScopeProvider() {
    return (principal, resourceType, action) ->
            "product".equals(resourceType)
                    && DataAction.READ.equals(action)
                    && principal.hasRole("ROLE_CATALOG_WATCHER")
                    ? DataScope.restrictedTo(WATCHING, principal.subject())
                    : DataScope.none();   // ничего не вносит; настроенные политики продолжают работать
}
```

Провайдеры объединяются, поэтому пользователь, который одновременно редактор и наблюдатель, видит и
товары редактора, **и** те, за которыми наблюдает. Провайдер может только расширять доступ — отказ
выражается тем, что никто ничего не выдал, и именно это делает пустую конфигурацию безопасной.

`createdBy` не требует собственной колонки: аудит db-core уже заполняет её subject-ом
аутентифицированного принципала, а именно с ним и сравнивает политика `OWN`. Сама политика — это
конфигурация (`ludwig.security.data.policies` в `application.yml`), поэтому эксплуатация может
прочитать и изменить её во время инцидента без релиза:

```yaml
product:
  read:
    ROLE_CATALOG_ADMIN: ALL
    ROLE_CATALOG_EDITOR: ALL
    ROLE_CATALOG_PARTNER: PARTNER   # только строки, заведённые под его код партнёра
  write:
    ROLE_CATALOG_ADMIN: ALL
    ROLE_CATALOG_EDITOR: OWN        # редакторы читают всё, пишут только созданное ими
    ROLE_CATALOG_PARTNER: PARTNER
  delete:
    ROLE_CATALOG_ADMIN: ALL
```

Области чтения и записи различаются для одной роли намеренно. Схлопывание их в одно понятие «доступ»
и есть то, из-за чего люди выдают более широкое из двух.

Ошибки конфигурации ловятся на старте, а не на первом запросе, который их задействует: политика,
называющая ресурс без маппинга, измерение, которого маппинг не связывает, опечатка в гранте (`OWNN`)
или ресурс, перечисленный одновременно как неограниченный и как политизированный, роняют контекст.
Попробуйте: замените `OWN` на `OWNN` в `application.yml` — и сервис откажется стартовать, процитировав
проблемный ключ.

Три детали, которые стоит перенять:

- **`supplier_partner_id` штампуется из принципала, никогда из тела запроса.** Мапперу явно запрещено
  его заполнять. Значение от партнёра позволило бы одному партнёру заводить товары под чужим
  идентификатором — а затем читать и править их, поскольку именно по этой колонке совпадает его
  область.
- **Это поле не `@Filterable`.** Клиент, способный фильтровать по колонке области, может перебором
  выяснить, какие значения возвращают строки.
- **`lookupBySku` остаётся вне области.** Это проверка уникальности за ограничением SKU, а не чтение
  чьих-то данных; ограничение её областью позволило бы создать товар, конфликтующий с невидимым, и
  вставка упала бы на ограничении базы с ошибкой, которую никто не сможет объяснить.
- **Наблюдателей нельзя задать через создание или обновление.** Мапперу явно запрещено заполнять
  `watcherSubjects`, потому что клиент, способный добавить себя наблюдателем, выдал бы себе доступ на
  чтение любого товара — эта колонка ровно то, с чем совпадает его область данных.

Роли приходят из `identity-projection-spring-boot-starter`: таблица `security_user`, питаемая из
топика Kafka OIDC-провайдера, чей changelog сервис включает в собственный мастер-changelog рядом с
changelog-ом модуля outbox. `ludwig.security.authorities.require-resolver: true` делает старт **без**
этой проекции отказом, а не сервисом, где ни у кого нет ролей.

Отказы возвращаются локализованными проблемами RFC 9457 под кодами `ludwig.security.error.*`.
`@PreAuthorize` и защита данных бросают внутри диспетчеризации MVC, после того как цепочка фильтров
security передала запрос дальше, поэтому собственный `AccessDeniedHandler` модуля их не видит, и без
обработчика там они всплыли бы пятисотками. Advice из web-core-стартера их видит, а модуль
безопасности вносит маппер, чтобы отказ внутри диспетчеризации сообщал тот же код, который его
обработчики в цепочке фильтров пишут для того же условия: клиенту никогда не приходится знать, какой
слой ему отказал.

## Заметки о проводке

- `JpaConfig` объявляет `@EnableJpaRepositories(repositoryBaseClass = BaseRepositoryImpl.class)` —
  именно это даёт репозиториям рабочий `getByIdOrThrow`.
- Он же объявляет явный `@EntityScan`. Это обязательно, а не украшение: стартер outbox объявляет
  `@EntityScan` для собственных сущностей, а как только появляется хоть один `@EntityScan`, Spring
  Boot перестаёт откатываться к пакетам автоконфигурации при сканировании сущностей.
- Сборка Maven выставляет `-parameters` (обычно это делает spring-boot-starter-parent), без чего
  `@PathVariable UUID id` не может разрешить своё имя.

## Тестирование

```bash
mvn -pl crud-service-example test
```

`ProductServiceImplTest` покрывает бизнес-правила моками и настоящим сгенерированным маппером, без
контекста Spring. `CatalogIntegrationTest` прогоняет весь стек — HTTP через QueryDSL до настоящего
контейнера PostgreSQL — с применёнными реальными миграциями Liquibase и Hibernate, сверяющим свои
маппинги с ними (`ddl-auto=validate`), и проверяет поток CRUD, оптимистичную блокировку,
OData-фильтрацию и её отказы по политике, строки outbox, колонки аудита и оба языка. Ему нужен
работающий демон Docker.

Образ контейнера закреплён по имени, версии **и** дайджесту. Если ваш демон Docker отвергает версию
API, которую Testcontainers 1.21.4 берёт по умолчанию (Docker 29 убрал версии API ниже 1.40),
запускайте тесты с `-DargLine="-Dapi.version=1.44"`.
