# catalog-grpc-api

*[English](README.md) · **Русский***

gRPC-контракт каталога товаров, версия 1 (`ludwig.catalog.v1.CatalogService`), который обслуживает
[`crud-service-example`](../../services/crud-service-example) рядом со своим REST API. Это эталонный модуль
контракта: копируйте его форму при добавлении gRPC API сервиса.

| | |
|---|---|
| Роль | contract |
| Зависит от (в репозитории) | родитель [`ludwig-grpc-api-parent`](../../build/ludwig-grpc-api-parent) (приносит `grpc-core`) |
| Используется | `crud-service-example` (сервер); любой вызывающий (клиентские stub-ы) |
| Поверхности | gRPC-контракт |

## Использование

```xml
<dependency>
    <groupId>ru.ludwigandreas</groupId>
    <artifactId>catalog-grpc-api</artifactId>
</dependency>
```

Исходник: [`src/main/proto/ludwig/catalog/v1/catalog.proto`](src/main/proto/ludwig/catalog/v1/catalog.proto).
Сгенерированный Java-код - в `ru.ludwigandreas.catalog.grpc.v1`.

| RPC | Запрос → Ответ | `idempotency_level` | Ошибки |
|---|---|---|---|
| `GetProduct` | `GetProductRequest{id}` → `Product` | `NO_SIDE_EFFECTS` | `NOT_FOUND` с кодом проблемы product-not-found |
| `GetProducts` | `GetProductsRequest{ids[]}` → `GetProductsResponse{products[]}` (в порядке запроса) | `NO_SIDE_EFFECTS` | `NOT_FOUND`, если какого-либо id нет |

`Product`: `id`, `sku`, `name`, опциональный `description` (отсутствует ≠ пустой), `price` **десятичной строкой**
(деньги никогда не передаются как float), `state` (`PRODUCT_STATE_DRAFT` / `ACTIVE` / `DISCONTINUED`),
`stock_quantity`, `category {id, code, name}`, `created_at`, `updated_at`, `version` (версия оптимистической
блокировки). Себестоимости у поставщика нет ни в одном API.

Клиент вызывает его через [`grpc-client-spring-boot-starter`](../grpc-client-spring-boot-starter); оба метода
безопасно повторять, так как объявлен `NO_SIDE_EFFECTS`.

## Конфигурация

Нет.

## Развёртывание

Публикуется jar-ом с каждым релизом. Ломающие изменения идут в новый пакет `ludwig.catalog.v2`, обслуживаемый рядом
с v1, и никогда - правкой v1.

## Наблюдаемость

Не применяется (контракт). Вызовы наблюдаются gRPC-стартерами с обеих сторон.

## Аудит

Не применяется.

## Runbook

| Симптом | Вероятная причина | Что делать |
|---|---|---|
| Клиент не может разобрать ответ после обновления сервера | В v1 внесено несовместимое изменение | Откатить; выпустить изменение как v2 |
| `check_protos.sh` падает после правки | Нарушено правило контракта | См. [grpc-core](../grpc-core/README.ru.md#модуль-контракта) |

## Тестирование

Сгенерированный код исключён из покрытия (`**/grpc/v*/**`). Серверная сторона тестируется в `crud-service-example`
in-process slice-ом gRPC.

## Проектные решения

- Деньги - десятичной строкой; опциональные поля там, где «отсутствует» и «пусто» значат разное.
- Только поля, которые раскрывает REST API, поэтому оба API раскрывают одни и те же данные.
