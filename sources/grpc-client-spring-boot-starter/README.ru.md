# grpc-client-spring-boot-starter

Именованные gRPC-клиенты как конфигурация. Объявите клиента в `ludwig.grpc.client.clients.<name>`,
внедрите сгенерированный stub через `@LudwigGrpcClient("<name>")` - и каждый вызов несёт дедлайн,
проходит через rate limiter, bulkhead и circuit breaker, повторяется только там, где контракт
объявляет повтор безопасным, аутентифицируется как настроено, падает с типизированным исключением,
измеряется, логируется без полезной нагрузки и - по желанию - аудируется. Своего `@Configuration`
не нужно.

Контракт и общий словарь (ключи метаданных, кодек статусов) - в [grpc-core](../grpc-core/README.ru.md);
серверная сторона - [grpc-server-spring-boot-starter](../grpc-server-spring-boot-starter/README.ru.md).
Форма повторяет [rest-client-spring-boot-starter](../rest-client-spring-boot-starter/README.ru.md)
там, где у gRPC есть то же понятие, и только там.

## Быстрый старт

```yaml
ludwig:
  grpc:
    client:
      defaults:
        deadline: 5s
        resilience:
          circuit-breaker: { failure-rate-threshold: 50, sliding-window-size: 100 }
      clients:
        notifications:
          target: dns:///notification-service-headless:9090
          deadline: 3s
          auth: { type: oauth2-client-credentials, registration-id: catalog-sa }
```

```java
@Service
class NotificationSender {
    private final NotificationServiceGrpc.NotificationServiceBlockingStub stub;

    NotificationSender(@LudwigGrpcClient("notifications")
                       NotificationServiceGrpc.NotificationServiceBlockingStub stub) {
        this.stub = stub;
    }
}
```

Три способа использовать клиента, одно поведение - все три выдают один и тот же канал с перехватчиками:

1. **Внедрённый stub** - `@LudwigGrpcClient("notifications")` на поле, параметре конструктора или
   параметре `@Bean`-метода с типом blocking, future или async stub.
2. **Внедрённый канал** - бин `Channel` `notificationsGrpcChannel` с квалификатором `notifications`
   (`@Qualifier("notifications")` или `@LudwigGrpcClient("notifications")`).
3. **Реестр** - `GrpcChannelRegistry.channel("notifications")`; неизвестное имя - исключение со
   списком настроенных.

Stub, привязанный к ненастроенному имени, валит старт с указанием точки внедрения.

## Приоритет

Встроенные значения, затем `defaults`, затем клиент. Скаляры переопределяются, если заданы; вложенные
блоки сливаются поле за полем (клиент, задавший только `sliding-window-size`, сохраняет унаследованный
порог); списки и словари заменяются. **`target` и `auth.relay-enabled` не наследуются никогда** -
адрес в `defaults` валит старт, а передача токена вызывающего решается для каждого клиента. Блок
`bulkhead` или `rate-limiter`, записанный где угодно, включает политику; во встроенном слое их нет.

## Конвейер и почему он фиксирован

```
observation -> correlation -> log/audit -> типизация ошибок
            -> rate limiter -> bulkhead -> circuit breaker -> дедлайн -> учётные данные
            [-> retry gRPC -> отправка, внутри канала]
```

Не настраивается, потому что у любого другого порядка есть дефект. Три политики Resilience4j берут
одно разрешение и записывают один результат на **логический** вызов, вокруг собственного retry gRPC:
вызов, успешный с третьей попытки, - один успех, три неудачные попытки - одна ошибка. Автомат,
считающий попытки, размыкался бы втрое быстрее ровно в том инциденте, который поглощали повторы, -
то же рассуждение и тот же порядок, что в `rest-client`. Observation и лог снаружи, чтобы вызов,
отклонённый собственной политикой клиента, тоже измерялся и аудировался как отклонённый. Учётные
данные внутри, чтобы каждая попытка несла текущий токен.

Что считает автомат: `UNAVAILABLE`, `DEADLINE_EXCEEDED`, `INTERNAL`, `UNKNOWN`, `RESOURCE_EXHAUSTED`,
`DATA_LOSS`. Ничего больше - пир, ответивший `INVALID_ARGUMENT`, `NOT_FOUND` или `PERMISSION_DENIED`,
здоров, а запрос был неверен; ошибка аутентификации - не сбой зависимости. Экземпляры называются
`grpc-<client>` в реестрах Resilience4j приложения (или в собственных, если их нет), поэтому
REST- и gRPC-клиент с одним именем никогда не делят автомат.

## Повтор определяется контрактом

Метод повторяется, только если его `.proto` объявляет `idempotency_level = IDEMPOTENT` или
`NO_SIDE_EFFECTS`, и только на `retry.retry-on` (встроенно `UNAVAILABLE`). Остальные методы получают
лишь прозрачный повтор gRPC, повторяющий запрос, который доказуемо не покинул процесс. Переключателя
на месте вызова намеренно нет, ничего похожего на `X-Ludwig-Retry`: безопасен ли повтор - заявление
владельца сервера, сделанное один раз в контракте. Повтор - собственный механизм gRPC (учитывает
коммит, экспоненциальная задержка с джиттером, троттлинг, server pushback), а не перехватчика,
потому что повтор вызова из перехватчика - это код, который работает, пока не потеряет сообщение.
Контракты стартер находит, сканируя `ru.ludwigandreas` на сгенерированные классы `<Service>Grpc`,
которые туда помещает `check_protos.sh`.

## Дедлайны

Каждый вызов получает самый ранний из: `deadline` клиента (встроенно 5s), дедлайна, заданного на
месте вызова, и - его применяет сам gRPC из текущего `Context` - остатка входящего вызова, внутри
которого сделан этот. Обработчик, у которого осталось 500 мс, не может начать вызов на 3 с. Дедлайн
ограничивает логический вызов вместе с повторами. Клиент с неположительным дедлайном валит старт;
отключить дедлайны нельзя.

## Аутентификация

| `auth.type` | |
|---|---|
| `none` | встроенно |
| `bearer` | статический `auth.token` |
| `oauth2-client-credentials` | машинный токен по `auth.registration-id` (`spring.security.oauth2.client.registration`), кэшируется и обновляется за `auth.refresh-skew` (30s) до истечения, выпускается один раз, сколько бы вызовов ни попросили одновременно |
| `oauth2-token-relay` | собственный bearer-токен вызывающего, читается на его потоке |
| любое другое | бин `GrpcCallCredentialsProvider` с совпадающим `type()`; он получает `auth.properties` |

На `UNAUTHENTICATED` унарный вызов с машинным токеном повторяется **один** раз с новым токеном - вне
бюджета повторов и внутри дедлайна; второй отказ сообщается как `GrpcClientAuthenticationException`.

У передачи токена три защиты: `relay-enabled: true` на самом клиенте, без наследования; нет входящего
токена - вызов падает до отправки, анонимно не уходит никогда; сервис, не являющийся сервером
ресурсов (нет `JwtDecoder`), вообще не может её настроить.

Учётные данные открытым текстом на не-loopback адрес валят старт.

### Откуда берутся токены

Кэш машинного токена, его выпуск по client credentials и чтение токена вызывающего - это
[`outbound-token-core`](../outbound-token-core/README.ru.md), единственная реализация, общая для этого
стартера и `rest-client-spring-boot-starter`. Своё у модуля только то, как токен попадает в вызов: как
gRPC `CallCredentials`, на каждую попытку. `TokenOwnershipTest` валит сборку, если здесь появится второй
кэш, выпуск или чтение токена.

## Ошибки

Stub бросает `StatusRuntimeException` без изменений, со статусом и трейлерами пира. Его причина -
одно из этих исключений, каждое с именем клиента и correlation id; получить его можно через
`GrpcClientException.from(e)`:

| Исключение | Когда | Если вылетает из контроллера |
|---|---|---|
| `GrpcClientResponseException` | пир ответил ошибкой; несёт код и декодированный `ProblemDetail` | 429 для `RESOURCE_EXHAUSTED`; **500** для кода на стороне вызывающего (`INVALID_ARGUMENT`, `NOT_FOUND`, `FAILED_PRECONDITION`, ...) - это отправил наш сервис; иначе 503 `upstream-unavailable` |
| `GrpcClientTimeoutException` | истёк дедлайн - запрос **мог** быть обработан | 503 |
| `GrpcClientConnectionException` | нет соединения или оно оборвалось до ответа | 503 |
| `GrpcClientAuthenticationException` | учётные данные не получены или пир их отверг | 500 |
| `GrpcClientCallNotPermittedException` | отказали rate limiter, bulkhead или автомат клиента; ничего не отправлено | 503 |

Отображение - это поставляемый `ExceptionProblemMapper`: своего advice в модуле нет, а реестр web-core
находит типизированную причину, проходя по цепочке причин.

## Наблюдаемость и аудит

- `ludwig.grpcclient.requests{client,service,method,status}` - наблюдение gRPC из Micrometer,
  переименованное; оно же клиентский span.
- `ludwig.grpcclient.retries{client,method}` - считается stream tracer'ом на каждую попытку, потому что
  повторы внутри канала перехватчику не видны.
- `ludwig.grpcclient.calls.notpermitted{client,policy}` и `ludwig.grpcclient.auth.token.refreshes{client,type}`.
- Одна строка INFO на вызов в `ludwig.grpcclient.<client>`: метод, статус, длительность, попытки,
  correlation id. **Без полезной нагрузки.** `logging.level: payload` выводит сообщения как JSON
  через `Redactor` из audit-core, обрезая до `max-payload-chars`, и запрещён при промышленном профиле.
- Correlation id уходит как `x-correlation-id` (имя заголовка observability): входящего вызова, если он
  есть, иначе текущий из observability; без observability - никакого.
- `audit.enabled: true` пишет события `GrpcCallAudit` в единственный `AuditSink` с
  `category=outbound-call`: каждую ошибку и отказ (отказ - как `DENIED`), успехи - по выборке
  `audit.success-sampling`. Без полезной нагрузки и метаданных.

## Конфигурация

**Перезапуск** - значение зашито в канал; **обновление** - перечитывается из связанных свойств (не
чаще раза в секунду), а вызов в полёте сохраняет значение, с которым начался.

| Ключ (в `ludwig.grpc.client.clients.<name>` или `.defaults`) | Встроенно | |
|---|---|---|
| `target` | - | перезапуск; не наследуется; `dns:///host:port`, `host:port` или `in-process:<name>` в тестах |
| `deadline` | `5s` | обновление |
| `load-balancing` | `round_robin` | перезапуск; `pick_first` пишется в WARN |
| `plaintext` | `false` | перезапуск; вне loopback нужен `ludwig.grpc.allow-plaintext`, в промышленной среде - никогда |
| `tls.bundle` | хранилище JDK | перезапуск; SSL-бандл Spring Boot, с ключом для mTLS |
| `max-inbound-message-size` | `4MB` | перезапуск |
| `idle-timeout` | `30m` | перезапуск |
| `keepalive.time` / `.timeout` / `.without-calls` | `30s` / `20s` / `false` | перезапуск; `time` не меньше `keepalive.permit-time` сервера - меняйте вместе |
| `resilience.circuit-breaker.*` | включён; 50% из 100 вызовов, минимум 20, открыт 30s | перезапуск |
| `resilience.bulkhead.max-concurrent-calls` / `.max-wait` | выключен; `25` / `0` после объявления | перезапуск |
| `resilience.rate-limiter.limit-for-period` / `.refresh-period` / `.timeout` | выключен; `100` / `1s` / `0` после объявления | лимит: обновление; остальное: перезапуск |
| `retry.enabled` / `.max-attempts` | `true` / `3` | перезапуск |
| `retry.initial-backoff` / `.max-backoff` / `.backoff-multiplier` | `100ms` / `2s` / `2` | перезапуск |
| `retry.retry-on` | `[UNAVAILABLE]` | перезапуск |
| `retry.throttling-max-tokens` / `.throttling-token-ratio` | `10` / `0.1` | перезапуск |
| `auth.type` / `.token` / `.registration-id` / `.scopes` / `.refresh-skew` / `.properties` | `none`, -, -, -, `30s`, - | перезапуск |
| `auth.relay-enabled` | - | перезапуск; не наследуется |
| `logging.level` / `.max-payload-chars` | `basic` / `2048` | обновление |
| `audit.enabled` / `.success-sampling` | `false` / `1.0` | обновление |

Общие с серверным стартером: `ludwig.grpc.allow-plaintext` (`false`) и
`ludwig.grpc.production-profiles` (`prod, production`).

Всё, что корректно по отдельным ключам и неверно в целом - нет адреса, адрес в defaults,
неположительный дедлайн, учётные данные открытым текстом, открытый текст без обоих переключателей
или в промышленной среде, неизвестный тип аутентификации, relay без переключателя или без сервера
ресурсов, автомат, который не может разомкнуться, логирование нагрузки в промышленной среде - валит
старт **один раз** со списком всех проблем всех клиентов.

## Тестирование

```java
@LudwigGrpcClientTest(properties = "ludwig.grpc.client.clients.notifications.target=in-process:peer")
class NotificationSenderTest { ... }
```

`@LudwigGrpcClientTest` поднимает этот стартер, реестр проблем web-core и audit-core. Направьте
клиента на in-process сервер, запущенный тестом (`InProcessServerBuilder.forName("peer")`), и каждый
вызов пройдёт промышленный конвейер.

## Чего он намеренно не делает

- **Нет включения повтора на месте вызова** и нет hedging.
- **Нет таймаута на попытку** - у gRPC нет стабильного, а дедлайн ограничивает логический вызов.
- **Нет режима «шифрует mesh»**: отсутствующий sidecar - ровно тот случай, когда токен читается в сети.
- **Нет особого поведения для client-streaming и bidi вызовов**, кроме конвейера; повтор при
  `UNAUTHENTICATED` - только для унарных.
- **Нет общего канала у разных клиентов**, даже к одному адресу.
- **Нет зависимости от `rest-client`**: общее у них - `outbound-token-core`, который не зависит ни от одного.
