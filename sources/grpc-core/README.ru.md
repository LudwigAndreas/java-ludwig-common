# grpc-core

*[English](README.md) · **Русский***

То, о чём должны договориться два сервиса платформы, общаясь по gRPC, без транспорта: ключи метаданных, кодек
между проблемами RFC 9457 и `google.rpc.Status`, сообщение долгой операции, мост контекста, восстанавливающий
thread-local вокруг каждого gRPC-callback, и конфигурация MapStruct для protobuf-сообщений. От модуля зависят оба
gRPC-стартера; сервис может нести только один из них.

| | |
|---|---|
| Роль | library |
| Зависит от (в репозитории) | [`web-core-spring-boot-starter`](../web-core-spring-boot-starter) |
| Используется | стартерами `grpc-client`, `grpc-server`; [`ludwig-grpc-api-parent`](../../build/ludwig-grpc-api-parent) |
| Поверхности | нет (определяет `ludwig/operation/v1/operation.proto`) |

```
ru.ludwigandreas.grpc.core
├── metadata    GrpcMetadataKeys
├── problem     GrpcStatusCodes, GrpcStatusCodec, DecodedProblem
├── operation   OperationMessages
├── context     GrpcCallContext, ThreadBinding, ContextRestoringServerInterceptor
└── mapping     ProtobufMapperConfig, ProtobufConversions

ru.ludwigandreas.operation.grpc.v1   сгенерировано из ludwig/operation/v1/operation.proto
```

## Использование

Сервисы не зависят от модуля напрямую; они получают его через gRPC-стартер или модуль контракта.

### Модуль контракта

gRPC-контракт сервиса - отдельный модуль `sources/<service>-grpc-api` с родителем
[`ludwig-grpc-api-parent`](../../build/ludwig-grpc-api-parent), содержащий только `.proto` и сгенерированный код.
Вызывающие зависят от этого jar. Весь POM:

```xml
<parent>
    <groupId>ru.ludwigandreas</groupId>
    <artifactId>ludwig-grpc-api-parent</artifactId>
    <version>${revision}</version>
    <!-- Обязательно: без него Maven молча берёт устаревшего родителя из ~/.m2. -->
    <relativePath>../../build/ludwig-grpc-api-parent/pom.xml</relativePath>
</parent>
<artifactId>billing-grpc-api</artifactId>
<name>Billing gRPC API</name>
<description>The gRPC contract of billing.</description>
```

плюс три регистрации: `<module>` в корневом `pom.xml`, запись в `build/ludwig-bom/pom.xml` и зависимость в
`build/jacoco-aggregate/pom.xml`. `scripts/manifest.sh layout` (запускается гейтом) падает с именем модуля, если
чего-то нет, родитель другой, в модуле есть Java или нет `.proto`.

```proto
syntax = "proto3";

package ludwig.billing.v1;

option java_package = "ru.ludwigandreas.billing.grpc.v1";
option java_multiple_files = true;

service BillingService {
  rpc GetInvoice(GetInvoiceRequest) returns (Invoice) {
    option idempotency_level = NO_SIDE_EFFECTS;
  }
  rpc CreateInvoice(CreateInvoiceRequest) returns (Invoice) {
    option idempotency_level = IDEMPOTENCY_UNKNOWN;
  }
}
```

`scripts/check_protos.sh` (запускается гейтом) требует:

| Правило | Почему |
|---|---|
| `package ludwig.<service>.v<N>` | `v2` можно обслуживать рядом с `v1` |
| `java_package = ru.ludwigandreas.<service>.grpc.v<N>` | Единое исключение сгенерированного кода из покрытия/анализа (`**/grpc/v*/**`) |
| `java_multiple_files = true` | Переименование файла не должно переименовывать все типы |
| каждый `rpc` объявляет `idempotency_level` | gRPC-клиент решает о повторах по нему; `IDEMPOTENCY_UNKNOWN` - допустимый ответ, молчание - нет |
| `.proto` только в модуле `-grpc-api` (или здесь) | Вызывающие не должны зависеть от jar, собираемого в образ |
| `protoc.version` (корневой POM) = `protobuf.version` (BOM) | Сгенерированный код соответствует runtime |

`protoc` разрешается как Maven-артефакт; системный компилятор не нужен. **Не проверяется:** истинность
`idempotency_level` (знает только автор) и совместимость изменённого proto на проводе (нужна базовая линия
дескрипторов на релиз; см. [architecture-rules](../../build/architecture-rules)). Проверяйте оба пункта на ревью.

### Отображение сообщений

```java
@Mapper(config = ProtobufMapperConfig.class)
public interface ProductMessageMapper {
    ru.ludwigandreas.catalog.grpc.v1.Product toMessage(Product product);
}
```

`ProtobufMapperConfig` оставляет поле незаданным для отсутствующего значения (сеттеры builder-а бросают на
`null`), заполняет repeated-поля через adder-ы и конвертирует `Instant`/`Duration` ↔
`google.protobuf.Timestamp`/`Duration` (`ProtobufConversions`). Protobuf SPI MapStruct (на пути процессоров
аннотаций из `ludwig-service-parent`) обрабатывает builder-ы, присутствие `hasX` и префиксы `ENUM_NAME_`.
Рабочий пример: `ProductMessageMapper` в `crud-service-example`.

### Ключи метаданных

`GrpcMetadataKeys` объявляет каждый ключ один раз; каждый - это HTTP-заголовок с тем же значением в нижнем
регистре.

| Ключ | Соответствует |
|---|---|
| `authorization` | HTTP `Authorization` |
| `x-correlation-id` | `ludwig.observability.correlation.header-name` (`forHeader`, если переименован) |
| `idempotency-key` | `IdempotencyHeaders.KEY` |
| `idempotency-replayed` | `IdempotencyHeaders.REPLAYED` |
| `accept-language` | HTTP `Accept-Language` |
| `x-time-zone` | заголовок часового пояса web-core |
| `retry-after` | `OperationHeaders.RETRY_AFTER` |

Модули-владельцы не являются зависимостями, поэтому литералы повторены; стартеры содержат тесты на равенство.

### Проблемы как статус gRPC

Какой проблемой является исключение, решает `ProblemMapperRegistry` web-core; `GrpcStatusCodec` меняет только
конверт. **Ни один модуль не пишет gRPC-специфичное отображение исключений.**

| Статус проблемы | Код gRPC | | Статус проблемы | Код gRPC |
|---|---|---|---|---|
| 400, 405, 406, 415 | `INVALID_ARGUMENT` | | 413, 429 | `RESOURCE_EXHAUSTED` |
| 401 | `UNAUTHENTICATED` | | 500 | `INTERNAL` |
| 403 | `PERMISSION_DENIED` | | 501 | `UNIMPLEMENTED` |
| 404, 410 | `NOT_FOUND` | | 502, 503 | `UNAVAILABLE` |
| 409, 423 | `ABORTED` | | 504 | `DEADLINE_EXCEEDED` |
| 412, 422 | `FAILED_PRECONDITION` | | | |

| Член проблемы | Передаётся как |
|---|---|
| status | код выше, точное число в `ErrorInfo.metadata[http_status]` |
| `code` | `ErrorInfo.reason` |
| type, title | `ErrorInfo.metadata[type]`, `[title]` |
| detail | сообщение статуса и `LocalizedMessage` с локалью |
| `violations` | `BadRequest.field_violations` |
| retry-after | `RetryInfo.retry_delay` |
| correlation id | `ErrorInfo.metadata[correlation_id]` |

- `409` - это `ABORTED`, а не `ALREADY_EXISTS`: конфликт так же часто бывает сбоем оптимистической блокировки;
  код проблемы уточняет.
- `GrpcStatusCodes.forProblemStatus` - исчерпывающий `switch` без default: новый статус в web-core останавливает
  компиляцию, пока ему не назначат код gRPC.
- **Никогда не передаются**: сообщения исключений, имена классов, стектрейсы, произвольные свойства проблемы
  (включая `debug`), отклонённые значения, если их не нёс бы HTTP. Энкодер не принимает `Throwable`.
- Декодирование никогда не бросает; статус не с платформы декодируется в приближение с `fromPlatform() == false`.

### Сообщение операции

`ludwig/operation/v1/operation.proto` - это `OperationResponse` web-core на проводе:

```proto
import "ludwig/operation/v1/operation.proto";

service ExportService {
  rpc StartExport(StartExportRequest) returns (ludwig.operation.v1.Operation) {
    option idempotency_level = IDEMPOTENT;
  }
}
```

```java
return OperationMessages.toMessage(run.toOperationResponse(), Duration.ofSeconds(5));
```

`retry_after` несёт то, что HTTP кладёт в `Retry-After`. Контракт никогда не объявляет свой enum статусов; enum в
этом proto - единственное исключение из архитектурного правила словаря операций. Оба преобразования -
исчерпывающие switch; нулевое значение proto3 и `UNRECOGNIZED` отвергаются, а не читаются как `PENDING`.

### Мост контекста

Идентичность, корреляция и предпочтения живут в thread-local, а gRPC доставляет каждый callback в любом
свободном потоке executor-а. `ContextRestoringServerInterceptor` прикрепляет gRPC `Context` и применяет каждый
`ThreadBinding` **вокруг каждого callback**, затем откатывает их в обратном порядке. Binding восстанавливает
поток в том виде, в каком его нашёл (пулы потоков могут нести внешний scope). Binding-и поставляет серверный
стартер.

## Конфигурация

Нет: у модуля нет автоконфигурации; стартеры создают то, что им нужно.

## Развёртывание

Развёртывать нечего.

## Наблюдаемость

На этом уровне не применяется; вызовы инструментируют стартеры. Correlation id передаётся как `x-correlation-id`
и внутри каждого статуса ошибки.

## Аудит

Не применяется.

## Runbook

| Симптом | Вероятная причина | Что делать |
|---|---|---|
| Гейт падает в `check_protos.sh` | Контракт нарушает правило выше | Исправить proto, как сказано в сообщении |
| Гейт падает в `manifest.sh layout` для модуля `-grpc-api` | Нет регистрации или не тот родитель | Добавить `<module>`, запись в BOM и зависимость в jacoco-aggregate |
| Вызывающий получает `UNKNOWN` без подробностей | Сервер не с этой платформы | Ожидаемо; `fromPlatform()` = false |
| В gRPC-обработчике нет идентичности или корреляции | Обработчик читает thread-local вне мостовых callback-ов (свой executor) | Захватить значения в потоке callback-а и передать дальше |

## Тестирование

`ContextBridgeTest` доставляет callback-и в потоках, отличных от потока interceptor-а, и чередует двух
вызывающих на одном потоке пула; тесты кругового кодирования по статусам; тесты исчерпывающих switch;
`ModuleIndependenceTest` падает на любом транспортном, сервлетном или платформенном типе, кроме web-core.

## Проектные решения

- **Ни транспорта, ни Spring-подключения, ни валидации**: только то, о чём должны договориться обе стороны.
- **Один конвейер проблем для HTTP и gRPC**: мапперы, написанные для HTTP, работают и для gRPC.
- **Исчерпывающие switch без default** на каждом пересечении словарей, поэтому расхождение - ошибка компиляции.
