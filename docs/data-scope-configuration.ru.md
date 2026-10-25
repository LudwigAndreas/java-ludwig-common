# Настройка областей данных и сопоставления ролей

**[English](data-scope-configuration.md)** · **Русский**

Как определять области доступа на уровне данных в сервисе и сопоставлять их с ролями в конфигурации, чтобы доступ роли можно было изменить правкой конфигурации, а не кода.

Всё описанное здесь — уже существующее поведение `security-spring-boot-starter` (пакет `ru.ludwigandreas.security.data`). README модуля является справочным источником; этот документ — практическое руководство с примерами. Сервис `order`, используемый в примерах, условный и не является модулем этого репозитория. Реальная эталонная реализация — `product` в `services/crud-service-example`.

## Модель на одной странице

| Понятие | Что это | Где определяется |
|---|---|---|
| Ресурс | Защищаемый объект (`order`) | В коде: имя, передаваемое в `DataScopeMapping.forResource(...)` |
| Измерение | Ось, по которой можно ограничить строки (`owner`, `tenant`, `partner` или собственное измерение) | В коде: `ScopeDimension`, привязанное к столбцу в mapping |
| Действие | Операция над ресурсом (`read`, `write`, `delete` или собственная) | В коде: обычная строка, передаваемая в guard |
| Разрешение | Что получает роль для одного ресурса и действия: `ALL`, `NONE` или измерения, соединённые через `+` | Конфигурация |
| Политика | Дерево `resource -> action -> role -> grant` | Конфигурация: `ludwig.security.data.policies` |

Это разделение сделано намеренно. То, какой столбец соответствует какому измерению, задаётся в коде: здесь указываются реальные столбцы, и переименование столбца должно приводить к ошибке сборки. А то, какая роль получает какое разрешение, задаётся в конфигурации, поскольку именно это эксплуатационной команде нужно просматривать и менять без выпуска новой версии.

Задействованные модули:

| Модуль | Назначение |
|---|---|
| `security-spring-boot-starter` | Содержит модель: измерения, сопоставления, настроенную политику и `DataAccessGuard`. |
| `identity-projection-spring-boot-starter` | Предоставляет роли и атрибуты вызывающего субъекта (`AuthorityResolver`), а также записи разрешений для отдельных пользователей (`DatabaseDataScopeProvider`). |
| `test-support-security` | `TestPrincipalBuilder` для создания в тестах субъектов с ролями и атрибутами. |

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

Каждая привязка одновременно задаёт часть для запроса (путь QueryDSL) и часть для загрузки (getter), поэтому они не могут разойтись.

| Форма правила | Привязка |
|---|---|
| Один столбец содержит одно значение | `.owner(...)`, `.tenant(...)`, `.partner(...)`, `.bind(dimension, ...)` |
| Строка связана с несколькими значениями, любое из которых может совпасть | `.bindCollection(dimension, path.any(), getter)` |
| Любой другой случай | `.bindExpression(...)` — согласованность двух частей автоматически не проверяется, поэтому проверьте их друг относительно друга тестами. |

## Шаг 2. Сопоставьте роли с разрешениями

```yaml
ludwig:
  security:
    data:
      default-access: NONE          # если для ресурса/действия нет политики, доступ запрещён
      policies:
        order:                      # ресурс
          read:                     # действие
            ROLE_ORDER_ADMIN: ALL
            ROLE_ORDER_AGENT: OWN           # созданные ими строки
            ROLE_TENANT_VIEWER: TENANT      # строки их арендатора
            ROLE_PARTNER: PARTNER+TENANT    # их партнёр И их арендатор
          write:
            ROLE_ORDER_ADMIN: ALL
            ROLE_ORDER_AGENT: OWN
```

Чтобы изменить доступ роли, отредактируйте её строку. Установка значения `NONE` или удаление строки отзывает доступ.

Как объединяются разрешения:

- Измерения внутри одного разрешения соединяются логическим И (`OWN+TENANT` = «мои строки в моём арендаторе»).
- Если у вызывающего субъекта есть несколько подходящих ролей, их разрешения объединяются.
- `ALL` имеет приоритет над всем; `NONE` ничего не добавляет.
- Если у субъекта нет значения для обязательного измерения (например, tenant), доступ запрещается, а не расширяется.
- Если субъекту ничего не разрешено, endpoint списка возвращает пустую страницу, а не `403`.

## Шаг 3. Примените ограничения в сервисе

```java
// На этапе запроса: область становится частью WHERE, поэтому пагинация и подсчёт итогов остаются корректными.
queryFactory.selectFrom(order)
        .where(guard.predicate("order", DataAction.READ).and(criteria))
        .fetch();

// На этапе загрузки: дополнительная защита для загрузки по id и всех путей, которые не проходят через запрос с ограничением.
OrderEntity entity = repository.getByIdOrThrow(id);
guard.check("order", DataAction.READ, entity, OrderEntity::getId);
```

Используйте оба механизма. Код приложения внедряет `DataAccessGuard`, а не отдельный provider.

## Пользовательские измерения

Сервис добавляет собственную ось с помощью `ScopeDimension.of(...)`. Для этого нужны три шага.

1. Привяжите измерение:

   ```java
   static final ScopeDimension REGION = ScopeDimension.of("region");

   DataScopeMapping.forResource("order", OrderEntity.class)
           .bind(REGION, order.regionCode, OrderEntity::getRegionCode)
           .build();
   ```

2. Разрешите пользовательские токены и укажите измерение в политике:

   ```yaml
   ludwig:
     security:
       data:
         strict-policy-tokens: false     # иначе принимаются только OWN/TENANT/PARTNER
         policies:
           order:
             read:
               ROLE_REGIONAL_MANAGER: REGION
               ROLE_REGIONAL_AGENT: OWN+REGION
   ```

   Токен — это имя измерения; регистр не учитывается. Если токен указывает на измерение, не привязанное в mapping, запуск всё равно завершится ошибкой, поэтому опечатка будет обнаружена даже при отключённой строгой проверке токенов.

3. Передайте значение для вызывающего субъекта. Для пользовательского измерения настроенная политика сравнивает столбец с **атрибутом principal с тем же именем** (`region`). Стандартный `DatabaseAuthorityResolver` задаёт только `tenant` и `partner`, поэтому остальные атрибуты должен предоставить ваш `AuthorityResolver`:

   ```java
   return Authorities.builder()
           .role("REGIONAL_MANAGER")
           .attribute("region", user.getRegionCode())
           .build();
   ```

Атрибуты содержат одно значение. Если для одного измерения нужно несколько значений, см. раздел [Пользовательские провайдеры](#пользовательские-провайдеры).

## Практический пример: заказы и переходы состояний

Требования:

- Пользователь создаёт и удаляет заказы и видит только собственные.
- Модератор видит все заказы.
- Бизнес-заказчик видит заказы, для которых он указан бизнес-заказчиком.
- Участник команды внедрения видит заказы, назначенные его команде.
- Переходы между статусами не хранятся отдельно. Пользователь может отменить заказ; модератор может его одобрить.
- Позже команде внедрения может потребоваться полный доступ на чтение — желательно, чтобы для этого хватило одного изменения конфигурации.

### Модель

Переходы — это **действия над `order`**, а не отдельный ресурс: политика отвечает на вопрос «какая роль может выполнить это действие над какими заказами». Каждая связь с заказом — это **измерение**.

### Терминология

Храните каждое имя в одном месте для каждого ресурса. Для ресурса и измерений используйте строковые константы, а не enum: guard принимает `String`, `ScopeDimension` намеренно является типом-значением, а `@PreAuthorize("hasPermission(..., 'read')")` требует строковых литералов на этапе компиляции.

```java
public final class OrderAccess {

    public static final String RESOURCE = "order";

    public static final String CREATE = "create";
    public static final String READ = DataAction.READ;
    public static final String DELETE = DataAction.DELETE;

    public static final ScopeDimension CUSTOMER = ScopeDimension.of("customer");
    public static final ScopeDimension TEAM = ScopeDimension.of("team");

    private OrderAccess() {
    }
}
```

Переходы — исключение: список всё равно нужен конечному автомату, поэтому имя действия хранится в enum доменной модели.

```java
public enum OrderTransition {
    CANCEL("cancel"),
    APPROVE("approve");

    private final String action;

    OrderTransition(String action) {
        this.action = action;
    }

    public String action() {
        return action;
    }
}
```

Правила именования:

- **Действия:** нижний регистр, одно слово или kebab-case (`approve`, `send-back`). Сопоставление выполняется по точной строке, поэтому `Approve` в конфигурации не совпадает с `approve` в коде.
- **Измерения:** существительные в нижнем регистре, обозначающие связь (`customer`, `team`), а не столбец.
- **Роли:** везде используйте полную форму `ROLE_...`. Префикс нормализуется, но единое написание упрощает поиск.
- **Ресурс:** единственное число, то же слово, которое использует bean mapping.

### Сопоставление

```java
@Bean
DataScopeMapping<OrderEntity> orderScopeMapping() {
    QOrderEntity order = QOrderEntity.orderEntity;
    return DataScopeMapping.forResource(OrderAccess.RESOURCE, OrderEntity.class)
            .owner(order.createdBy, OrderEntity::getCreatedBy)
            .bind(OrderAccess.CUSTOMER, order.businessCustomerId, OrderEntity::getBusinessCustomerId)
            .bind(OrderAccess.TEAM, order.implementationTeamId, OrderEntity::getImplementationTeamId)
            .build();
}
```

### Политика

```yaml
ludwig:
  security:
    data:
      default-access: NONE
      strict-policy-tokens: false
      policies:
        order:
          create:
            ROLE_USER: ALL
          read:
            ROLE_MODERATOR: ALL
            ROLE_USER: OWN
            ROLE_BUSINESS_CUSTOMER: CUSTOMER
            ROLE_IMPLEMENTATION_TEAM: TEAM
          delete:
            ROLE_USER: OWN
          cancel:
            ROLE_USER: OWN
          approve:
            ROLE_MODERATOR: ALL
```

Будущее изменение — одна строка:

```yaml
            ROLE_IMPLEMENTATION_TEAM: ALL   # было TEAM
```

### Применение ограничений

```java
// Переход: загрузить объект, проверить действие относительно этой строки, затем проверить допустимость перехода в доменной модели.
OrderEntity entity = repository.getByIdOrThrow(id);
guard.check(OrderAccess.RESOURCE, transition.action(), entity, OrderEntity::getId);
entity.apply(transition);

// Создание: строки ещё нет, поэтому проверяем только наличие у вызывающего субъекта какого-либо разрешения.
if (guard.scope(OrderAccess.RESOURCE, OrderAccess.CREATE).denies()) {
    throw new AccessDeniedException("order.create");
}
```

Правило «одобрение допустимо только из состояния X» остаётся в доменной модели. Политика определяет, кто может выполнять действия над какими строками, и ничего не знает о статусах. Если когда-либо понадобится правило для каждой пары статусов, задайте отдельное имя действия для каждого перехода (например, `approve-from-review`).

### Откуда берутся значения

| Измерение | Атрибут principal | Значение |
|---|---|---|
| `team` | `team` | идентификатор команды пользователя |
| `customer` | `customer` | собственный subject пользователя |

Строка `customer` — обходное решение. Настроенная политика не умеет сравнивать этот пользовательский столбец с subject вызывающего субъекта, поэтому resolver копирует subject в атрибут. Альтернатива — bean `DataScopeProvider` (ниже), который переносит это единственное правило из конфигурации в код.

### Количество команд

| Ситуация | Что требуется |
|---|---|
| У команды много заказов; у заказа одна команда; пользователь состоит в одной команде | Столбец с идентификатором команды в заказе и атрибут `team`. Дополнительная таблица не нужна. |
| Заказ обслуживают несколько команд | Коллекция связей заказа с командами в сущности, привязанная через `.bindCollection(TEAM, ...)`. Это доменные данные, а не таблица безопасности. |
| Пользователь состоит в нескольких командах | `DataScopeProvider`, возвращающий множество идентификаторов команд. |

## Пользовательские провайдеры

Можно объявить любое количество bean-компонентов `DataScopeProvider`. `CompositeDataScopeProvider` собирает результаты всех них вместе с настроенной политикой и таблицей разрешений identity-projection, а затем объединяет их.

```java
@Bean
DataScopeProvider teamScopeProvider(TeamMembershipRepository memberships) {
    return (principal, resourceType, action) -> {
        boolean applies = OrderAccess.RESOURCE.equals(resourceType)
                && DataAction.READ.equals(action)
                && principal.hasRole("ROLE_IMPLEMENTATION_TEAM");
        if (!applies) {
            return DataScope.none();
        }
        Set<String> teamIds = memberships.teamIdsOf(principal.subject());
        return teamIds.isEmpty()
                ? DataScope.none()
                : DataScope.restrictedTo(Map.of(OrderAccess.TEAM, teamIds));
    };
}
```

- Провайдер может только расширить доступ. `DataScope.none()` означает «я ничего не добавляю», а не «запретить доступ».
- `ALL` от любого провайдера прерывает дальнейшую обработку.
- Порядок не влияет на результат; `@Order` определяет только очерёдность выполнения.
- Каждый провайдер запускается при каждой проверке области доступа, поэтому проверяйте ресурс, действие и роль до выполнения любых запросов.
- Роль и действие внутри провайдера задаются в Java, поэтому это правило нельзя сузить или переназначить конфигурацией. Расширение всё равно работает: `ROLE_IMPLEMENTATION_TEAM: ALL` в политике объединяется с ответом провайдера и имеет приоритет.

## Согласованность кода и конфигурации

Проверка при запуске (`DataScopePolicyValidator`) прерывает развёртывание в следующих случаях:

- опечатка в разрешении (`OWNN`, `ALL+TENANT`);
- политика для ресурса, у которого нет `DataScopeMapping`;
- политика использует измерение, не привязанное в mapping;
- ресурс одновременно указан как не требующий ограничений и как защищённый политикой.

Названия действий **не** проверяются. `aprove:` в конфигурации будет принято, а настоящее `approve` перейдёт к `default-access: NONE` — доступ будет закрыт, но ошибка останется незаметной. В сервисе есть тест, проверяющий оба направления:

```java
@Test
void everyActionHasAPolicyAndEveryPolicyIsAKnownAction() {
    Set<String> known = new HashSet<>(Set.of(OrderAccess.CREATE, OrderAccess.READ, OrderAccess.DELETE));
    Arrays.stream(OrderTransition.values()).map(OrderTransition::action).forEach(known::add);

    Set<String> configured = properties.getData().getPolicies().get(OrderAccess.RESOURCE).keySet();

    assertThat(configured).isEqualTo(known);
}
```

Храните полное дерево политик в `application.yml` сервиса как проверенную базовую конфигурацию, а для каждого окружения переопределяйте отдельные строки — тогда изменение доступа будет выглядеть как однострочный diff.

## Известные ограничения

| Ограничение | Последствие |
|---|---|
| Политики компилируются один раз при запуске (`ScopePolicies`) | Изменение конфигурации требует перезапуска; переключить доступ на лету нельзя. |
| Разрешения задаются отдельно для каждой роли | Выражение разрешения нельзя объявить один раз и повторно использовать для нескольких ролей. |
| Пользовательское измерение читает атрибут principal | Чтобы сравнить пользовательский столбец с subject вызывающего субъекта, нужен обходной вариант с атрибутом или bean-провайдер. |
| Атрибуты principal содержат одно значение | Для нескольких значений одного измерения нужен bean-провайдер или строки в таблице разрешений. |
| `guard.scope(...)` не выполняет аудит | Отказ в `create`, проверенный таким способом, не записывается как отказ так, как это делает `guard.check(...)`. |
| Названия действий не проверяются при запуске | Это проверяется описанным выше тестом сервиса. |

Изменение любого из этих ограничений является изменением платформы в `security-spring-boot-starter` и должно проходить через OpenSpec для capability `data-access`.
