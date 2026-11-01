# Настройка областей данных и сопоставления ролей

**[English](data-scope-configuration.md)** · **Русский**

Как ограничивать строки, которые затрагивают операции сервиса, и выдавать эти операции ролям в конфигурации, чтобы
доступ роли можно было изменить правкой конфигурации, а не кода.

Всё описанное здесь — уже существующее поведение
[`security-spring-boot-starter`](../sources/security-spring-boot-starter/README.ru.md) (пакеты
`ru.ludwigandreas.security.operation` и `ru.ludwigandreas.security.data`). README модуля является справочным
источником; этот документ — практическое руководство с примерами. Сервис `order`, используемый в примерах, условный
и не является модулем этого репозитория. Реальная эталонная реализация — `product` в `services/crud-service-example`.

## Модель на одной странице

| Понятие | Что это | Где определяется |
|---|---|---|
| Операция | Что делает вызывающий: `<area>.<object>.<verb>` (`sales.order.update`), с чувствительностью и, если она затрагивает строки, ресурсом и действием над данными | В коде: бин `OperationContributor` |
| Ресурс | Защищаемые строки (`order`) | В коде: имя, передаваемое в `DataScopeMapping.forResource(...)` |
| Измерение | Ось, по которой можно ограничить строки (`owner`, `tenant`, `partner`, `object` или собственное измерение) | В коде: `ScopeDimension`, привязанное к столбцу в mapping |
| Область | Что получает роль для одной операции: `ALL` или измерения, соединённые через `+` | В конфигурации |
| Роль | Набор операций с их областями, при необходимости включающий другие роли | В конфигурации: `ludwig.security.roles` |

Такое разделение сделано намеренно. Какие операции существуют и какой столбец несёт какое измерение — это код,
потому что он называет реальные обработчики и столбцы и должен ломать сборку при их переименовании. Какая роль
владеет какой операцией и с какой областью — это конфигурация, потому что именно её эксплуатации нужно читать и
менять без выпуска. Код никогда не называет роль.

Задействованные модули:

| Модуль | Роль |
|---|---|
| `security-spring-boot-starter` | Владеет моделью: каталог операций, таблица ролей, измерения, mapping, `DataAccessGuard`. |
| `identity-projection-spring-boot-starter` | Поставляет роли и атрибуты вызывающего (`AuthorityResolver`) и строки персональных разрешений (`DatabaseDataScopeProvider`). |
| `test-support-security` | `TestPrincipalBuilder` для построения принципалов с ролями и атрибутами в тестах. |

## Шаг 1. Привяжите измерения к столбцам

```java
@Bean
DataScopeMapping<OrderEntity> orderScopeMapping() {
    QOrderEntity order = QOrderEntity.orderEntity;
    return DataScopeMapping.forResource("order", OrderEntity.class)
            .owner(order.createdBy, OrderEntity::getCreatedBy)
            .tenant(order.tenantId, OrderEntity::getTenantId, UUID::fromString)
            .partner(order.partnerId, OrderEntity::getPartnerId)
            .build();
}
```

Каждая привязка объявляет одновременно часть для запроса (путь QueryDSL) и часть для загрузки (getter), поэтому
они не могут разойтись.

| Форма правила | Привязка |
|---|---|
| Один столбец содержит одно значение | `.owner(...)`, `.tenant(...)`, `.partner(...)`, `.bind(dimension, ...)` |
| Строка связана со многими значениями, любое из которых может совпасть | `.bindCollection(dimension, path.any(), getter)` |
| Всё остальное | `.bindExpression(...)` — ничто не проверяет согласованность двух частей, поэтому тестируйте их друг против друга |

## Шаг 2. Объявите операции

```java
public final class OrderOperations implements OperationContributor {

    public static final String READ = "sales.order.read";
    public static final String UPDATE = "sales.order.update";

    @Override
    public Collection<Operation> operations() {
        return List.of(
                Operation.business(READ, Sensitivity.READ, "sales.operation." + READ).on("order", DataAction.READ),
                Operation.business(UPDATE, Sensitivity.CHANGE, "sales.operation." + UPDATE)
                        .on("order", DataAction.WRITE));
    }
}
```

Зарегистрируйте его бином и задайте значение каждого ключа описания в бандле сообщений сервиса (EN и RU): его
показывает человеку `GET /me/operations`, а старт падает на ключе, который нигде не разрешается. Одна операция
чтения покрывает список и получение одного вида объектов; каждое отдельное изменение — своя операция. Операция
`READ` не может объявить запись, а операция `CHANGE` или `PRIVILEGED` — действие чтения.

## Шаг 3. Выдайте операции ролям

```yaml
ludwig:
  security:
    roles:
      ORDER_AGENT:
        operations:
          sales.order.read: OWN              # созданные ими строки
          sales.order.update: OWN
      TENANT_VIEWER:
        operations:
          sales.order.read: TENANT           # строки их арендатора
      PARTNER:
        operations:
          sales.order.read: PARTNER+TENANT   # их партнёр И их арендатор
      ORDER_ADMIN:
        includes: [ORDER_AGENT]
        operations:
          sales.order.read: ALL
          sales.order.update: ALL
```

Изменение доступа роли — это правка её строки; удаление строки отзывает доступ.

Как сочетаются области:

- Измерения внутри одной области объединяются через И (`OWN+TENANT` = «мои строки в моём арендаторе»).
- Вызывающий с несколькими ролями получает объединение их областей, а `includes` объединяет роль с включёнными в неё
  ролями, транзитивно.
- `ALL` перекрывает всё. Владеть операцией — значит владеть непустой областью; операция, которую не перечисляет ни
  одна роль, запрещена всем.
- Вызывающему без значения для требуемого измерения (например, без арендатора) доступ запрещается, а не расширяется.
- Вызывающий, которому ничего не положено, получает пустую страницу от эндпоинта списка, а не 403.

## Шаг 4. Примените ограничения в сервисе

```java
// Время запроса: область становится частью WHERE, поэтому постраничность и итоги остаются корректными.
queryFactory.selectFrom(order)
        .where(guard.predicate(OrderOperations.READ).and(criteria))
        .fetch();

// Время загрузки: страховка для загрузок по id и любого пути, который не проходит через ограниченный запрос.
OrderEntity entity = repository.getByIdOrThrow(id);
guard.check(OrderOperations.UPDATE, entity, OrderEntity::getId);
```

Используйте оба способа. Прикладной код внедряет `DataAccessGuard`, а не отдельный провайдер. Guard принимает
операцию; операция задаёт ресурс, поэтому код и конфигурация называют одно и то же.

## Пользовательские измерения

Сервис добавляет собственную ось через `ScopeDimension.of(...)`. Нужны три вещи.

1. Привяжите её:

   ```java
   static final ScopeDimension REGION = ScopeDimension.of("region");

   DataScopeMapping.forResource("order", OrderEntity.class)
           .bind(REGION, order.regionCode, OrderEntity::getRegionCode)
           .build();
   ```

2. Назовите измерение в области роли:

   ```yaml
   ludwig:
     security:
       roles:
         REGIONAL_MANAGER:
           operations:
             sales.order.read: REGION
         REGIONAL_AGENT:
           operations:
             sales.order.read: OWN+REGION
   ```

   Токен — это имя измерения без учёта регистра. Токен с измерением, которое ресурс операции не привязывает, роняет
   старт, поэтому опечатка ловится.

3. Дайте вызывающему значение. Для пользовательского измерения область роли сравнивает столбец с **атрибутом
   принципала с тем же именем** (`region`). Штатный `DatabaseAuthorityResolver` устанавливает только `tenant` и
   `partner`, поэтому остальное должен предоставить ваш `AuthorityResolver`:

   ```java
   return Authorities.builder()
           .role("REGIONAL_MANAGER")
           .attribute("region", user.getRegionCode())
           .build();
   ```

Атрибуты однозначны. Случай, когда вызывающему нужно несколько значений для одного измерения, описан в разделе
[Пользовательские провайдеры](#пользовательские-провайдеры).

## Практический пример: заказы и переходы состояний

Требования:

- Пользователь создаёт и удаляет заказы и видит только свои.
- Модератор видит все заказы.
- Бизнес-клиент видит заказы, для которых он выбран бизнес-клиентом.
- Участник команды внедрения видит заказы, назначенные его команде.
- Переходы между статусами не хранятся. Пользователь может отменить заказ; модератор может одобрить.
- Позже команде внедрения может понадобиться полный доступ на чтение — в идеале одной правкой конфигурации.

### Модель

Каждый глагол — это **операция над `order`**: создание, чтение, удаление и каждый переход, — поэтому роли выдаются
ровно те глаголы, которые она выполняет, ровно над теми строками, которые допускает её область. Каждая связь с
заказом — это **измерение**.

### Терминология

Держите все имена ресурса в одном месте: идентификаторы операций и измерения, которые привязывает mapping.

```java
public final class OrderOperations implements OperationContributor {

    public static final String RESOURCE = "order";

    public static final String CREATE = "sales.order.create";
    public static final String READ = "sales.order.read";
    public static final String DELETE = "sales.order.delete";
    public static final String CANCEL = "sales.order.cancel";
    public static final String APPROVE = "sales.order.approve";

    public static final ScopeDimension CUSTOMER = ScopeDimension.of("customer");
    public static final ScopeDimension TEAM = ScopeDimension.of("team");

    private static final String KEY = "sales.operation.";

    @Override
    public Collection<Operation> operations() {
        return List.of(
                Operation.business(CREATE, Sensitivity.CHANGE, KEY + CREATE).on(RESOURCE, DataAction.WRITE),
                Operation.business(READ, Sensitivity.READ, KEY + READ).on(RESOURCE, DataAction.READ),
                Operation.business(DELETE, Sensitivity.CHANGE, KEY + DELETE).on(RESOURCE, DataAction.DELETE),
                Operation.business(CANCEL, Sensitivity.CHANGE, KEY + CANCEL).on(RESOURCE, "cancel"),
                Operation.business(APPROVE, Sensitivity.CHANGE, KEY + APPROVE).on(RESOURCE, "approve"));
    }
}
```

Машина состояний сопоставляет каждому переходу его операцию:

```java
public enum OrderTransition {
    CANCEL(OrderOperations.CANCEL),
    APPROVE(OrderOperations.APPROVE);

    private final String operation;

    OrderTransition(String operation) {
        this.operation = operation;
    }

    public String operation() {
        return operation;
    }
}
```

Правила именования:

- Операции: `<area>.<object>.<verb>`, три сегмента из строчных латинских букв и цифр. Старт отклоняет всё прочее, а
  также запись роли с операцией, которой нет в каталоге.
- Измерения: существительные в нижнем регистре, называющие связь (`customer`, `team`), а не столбец.
- Роли: в `ludwig.security.roles` пишутся без префикса `ROLE_`; префикс принимается и нормализуется.
- Ресурс: в единственном числе, то же слово, что использует бин mapping.

### Сопоставление

```java
@Bean
DataScopeMapping<OrderEntity> orderScopeMapping() {
    QOrderEntity order = QOrderEntity.orderEntity;
    return DataScopeMapping.forResource(OrderOperations.RESOURCE, OrderEntity.class)
            .owner(order.createdBy, OrderEntity::getCreatedBy)
            .bind(OrderOperations.CUSTOMER, order.businessCustomerId, OrderEntity::getBusinessCustomerId)
            .bind(OrderOperations.TEAM, order.implementationTeamId, OrderEntity::getImplementationTeamId)
            .build();
}
```

### Роли

```yaml
ludwig:
  security:
    roles:
      USER:
        operations:
          sales.order.create: ALL
          sales.order.read: OWN
          sales.order.delete: OWN
          sales.order.cancel: OWN
      MODERATOR:
        operations:
          sales.order.read: ALL
          sales.order.approve: ALL
      BUSINESS_CUSTOMER:
        operations:
          sales.order.read: CUSTOMER
      IMPLEMENTATION_TEAM:
        operations:
          sales.order.read: TEAM
```

Будущее изменение — одна строка:

```yaml
          sales.order.read: ALL   # было TEAM
```

### Применение ограничений

```java
// Переход: загрузить, проверить операцию для этой строки, затем дать домену решить, допустим ли переход.
OrderEntity entity = repository.getByIdOrThrow(id);
guard.check(transition.operation(), entity, OrderEntity::getId);
entity.apply(transition);

// Создание: строки ещё нет, поэтому спрашиваем только, владеет ли вызывающий операцией.
if (guard.scope(OrderOperations.CREATE).denies()) {
    throw new AccessDeniedException(OrderOperations.CREATE);
}
```

«Одобрение допустимо только из статуса X» остаётся в домене. Область роли решает, кто над какими строками может
действовать, и ничего не знает о статусах. Если когда-нибудь понадобится правило для каждой пары статусов, объявите
операцию на каждое ребро (`sales.order.approvefromreview`).

### Откуда берутся значения

| Измерение | Атрибут принципала | Значение |
|---|---|---|
| `team` | `team` | id команды пользователя |
| `customer` | `customer` | собственный subject пользователя |

Строка `customer` — обходной приём. Настроенная область не умеет сказать «сравни этот пользовательский столбец с
subject вызывающего», поэтому резолвер копирует subject в атрибут. Альтернатива — бин `DataScopeProvider` (ниже),
который выносит это одно правило из конфигурации.

### Количество команд

| Ситуация | Что нужно |
|---|---|
| У команды много заказов; у заказа одна команда; пользователь в одной команде | Столбец с id команды у заказа плюс атрибут `team`. Дополнительная таблица не нужна. |
| Один заказ обрабатывают несколько команд | Коллекция «заказ — команда» в сущности, привязанная через `.bindCollection(TEAM, ...)`. Это данные домена, а не таблица безопасности. |
| Один пользователь состоит в нескольких командах | `DataScopeProvider`, возвращающий множество id команд. |

## Административные объекты

Административную операцию над именованным объектом — например, задачей сверки — можно сузить до объектов, выданных
вызывающему. Измерение — встроенное `object`, значения которого — имена объектов; обработчик объявляет, какой
параметр называет объект. Роль выдаёт `OBJECT` (тогда объекты выдаются каждому субъекту отдельно) или `ALL` (все
объекты); любое другое измерение у операции, не затрагивающей строк, роняет старт.

Пример: команда снабжения управляет только `supplier-sync`, а платформенная команда — всем:

```yaml
ludwig:
  security:
    roles:
      SUPPLY_OPS:
        operations:
          reconciliation.task.read: OBJECT
          reconciliation.run.start: OBJECT
      PLATFORM_ADMIN:
        operations:
          reconciliation.task.read: ALL
          reconciliation.run.start: ALL
```

Затем каждому участнику команды выдаётся задача для каждой операции через `POST /admin/v1/identity/grants` с телом
`{"subject": "bob", "principalType": "USER", "resourceType": "reconciliation.run.start", "action": "invoke",
"dimension": "object", "dimensionValue": "supplier-sync"}` (и ещё раз с `"resourceType":
"reconciliation.task.read"`; для дежурного доступа подходит `expiresAt`). Операция, не затрагивающая строк,
запрашивается по собственному id и действию `invoke`. Запуск `supplier-sync` от Боба принимается, запуск
`customer-sync` — `403`, а чтение `customer-sync` — `404`, тот же ответ, что и для несуществующей задачи. Список
задач показывает ему только `supplier-sync`. Выдача доходит до других реплик в пределах TTL кэша областей данных,
как и любая выдача.

Пока свои объекты ограничивает только `reconciliation`. Кандидаты — `jobs` (имена задач) и `fileingest` (источники).

## Пользовательские провайдеры

Можно объявить любое количество бинов `DataScopeProvider`. Они собираются вместе с таблицей персональных разрешений
проекции идентичности, а их ответы объединяются с областями ролей. Провайдер получает ресурс операции и действие
над данными (для операции, не затрагивающей строк, — её id и `invoke`).

```java
@Bean
DataScopeProvider teamScopeProvider(TeamMembershipRepository memberships) {
    return (principal, resourceType, action) -> {
        boolean applies = OrderOperations.RESOURCE.equals(resourceType) && DataAction.READ.equals(action);
        if (!applies) {
            return DataScope.none();
        }
        Set<String> teamIds = memberships.teamIdsOf(principal.subject());
        return teamIds.isEmpty()
                ? DataScope.none()
                : DataScope.restrictedTo(Map.of(OrderOperations.TEAM, teamIds));
    };
}
```

- Провайдер может только расширять доступ. `DataScope.none()` означает «я ничего не добавляю», а не «запретить».
- `ALL` от любого провайдера прерывает проверку остальных.
- Порядок не меняет результат; `@Order` решает только, какой провайдер выполняется первым.
- Каждый провайдер выполняется при каждой проверке области, поэтому выходите по ресурсу и действию до любого поиска.
- Провайдер отвечает за субъект по данным. Он не должен сравнивать роли: кто чем владеет, решает таблица ролей, а
  роль, сравниваемую в коде, нельзя изменить из конфигурации. Расширение по-прежнему работает: `sales.order.read: ALL`
  у роли объединяется с ответом провайдера и побеждает.

## Согласованность кода и конфигурации

Старт срывает развёртывание при:

- идентификаторе операции вне грамматики, повторяющемся идентификаторе или ключе описания, который нигде не
  разрешается;
- записи роли с операцией, которой нет в каталоге сервиса, — с перечнем операций этой области из каталога;
- некорректной области (`OWNN`, `ALL+TENANT`), `NONE` или шаблоне;
- измерении, которое ресурс операции не привязывает, операции, у ресурса которой нет `DataScopeMapping`, или
  измерении строк у операции, не затрагивающей строк;
- цикле `includes` или включении неопределённой роли;
- аннотации авторизации с операцией, которой нет в каталоге;
- всё ещё заданных `ludwig.security.data.policies`, `default-access` или `strict-policy-tokens`.

Он один раз записывает в журнал каждую операцию каталога, которой не владеет ни одна роль. Неопределённое имя роли
в полномочиях вызывающего ошибкой не является, потому что роли приходят от провайдера идентичности.

Держите полную таблицу ролей в `application.yml` сервиса как проверенный эталон и переопределяйте отдельные строки
по окружениям, чтобы изменение доступа было диффом в одну строку. `GET /admin/v1/security/operations/{id}/holders`
показывает, кто владеет операцией в работающем развёртывании.

## Известные ограничения

| Ограничение | Следствие |
|---|---|
| Таблица ролей компилируется один раз при старте | Изменение конфигурации требует перезапуска; это не переключатель на лету. |
| Области записываются прямо в каждой роли | Выражение области нельзя назвать один раз и переиспользовать; `includes` разделяет роли целиком. |
| Пользовательское измерение читает атрибут принципала | Сравнение пользовательского столбца с subject вызывающего требует обходного приёма с атрибутом или бина-провайдера. |
| Атрибуты принципала однозначны | Несколько значений для одного измерения требуют бина-провайдера или строк в таблице разрешений. |
| `guard.scope(...)` не пишет аудит | Отказ в `create`, проверенный таким способом, не записывается как отказ, в отличие от `guard.check(...)`. |

Изменение любого из этих пунктов — это платформенное изменение `security-spring-boot-starter`, которое проходит через
OpenSpec относительно capability `operation-authorization`.
