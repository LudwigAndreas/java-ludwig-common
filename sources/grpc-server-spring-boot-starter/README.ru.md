# grpc-server-spring-boot-starter

*[English](README.md) · **Русский***

gRPC-сервер как конфигурация. Унаследуйте сгенерированный `ImplBase`, пометьте `@LudwigGrpcService` - и сервис
обслуживается на своём порту за фиксированной цепочкой interceptor-ов, которая переносит принципал платформы,
correlation id, предпочтения вызывающего и отображение проблем RFC 9457 через транспорт; с лимитами, которые
работают без настройки, TLS, если plaintext не разрешён дважды явно, health, привязанным к readiness, плавной
остановкой, опциональным повтором идемпотентных вызовов и in-process slice для тестов.

| | |
|---|---|
| Роль | starter |
| Зависит от (в репозитории) | [`grpc-core`](../grpc-core), [`idempotency`](../idempotency-spring-boot-starter), [`observability`](../observability-spring-boot-starter), [`security`](../security-spring-boot-starter), [`web-core`](../web-core-spring-boot-starter) |
| Используется | `crud-service-example`; `grpc-client` (тесты) |
| Поверхности | gRPC (свой порт, по умолчанию 9090) |

Построен прямо на `grpc-java`; свойства - в `ludwig.grpc.server.*` (никогда не `spring.grpc.*`, чтобы будущее
обновление Spring Boot не связало те же ключи с другим смыслом). Контракты живут в модулях `<service>-grpc-api`;
см. [grpc-core](../grpc-core).

## Использование

```java
@LudwigGrpcService
class CatalogGrpcService extends CatalogServiceGrpc.CatalogServiceImplBase {

    private final ProductService products;          // работу делает сервис приложения

    @Override
    public void getProduct(GetProductRequest request, StreamObserver<Product> reply) {
        reply.onNext(mapper.toMessage(products.get(request.getId())));
        reply.onCompleted();
    }
}
```

```yaml
ludwig:
  grpc:
    server:
      port: 9090
      tls:
        bundle: grpc            # запись spring.ssl.bundle.*
```

При настроенном resource server (`spring.security.oauth2.resourceserver.jwt.*`) больше ничего не нужно.
Бросайте доменные исключения как в контроллере: `ExceptionProblemMapper`-ы модулей определяют и статус gRPC.

### Цепочка interceptor-ов (фиксированная)

```
observation → correlation → access log → exception→status → admission → authentication
            → мост контекста (принципал, correlation MDC, предпочтения) → [административный доступ]
            → [идемпотентный повтор]
            → ваши бины LudwigGrpcServerInterceptor → обработчик
```

- Observation снаружи: отклонённый вызов всё равно измеряется.
- Трансляция исключений снаружи аутентификации: отклонённые учётные данные отрисовываются как любая проблема.
- Admission до аутентификации: перегруженный сервер не проверяет токены вызовов, которые всё равно отклонит.
- Мост контекста внутри аутентификации: принципал - одно из того, что он привязывает.
- Собственные interceptor-ы - бины `LudwigGrpcServerInterceptor`, всегда после встроенных стадий; обычный бин
  `ServerInterceptor` не подхватывается.

Thread-local (`SecurityContextHolder`, correlation MDC, `UserPreferences`) восстанавливаются **вокруг каждого
callback**, потому что unary-обработчик работает в `onHalfClose`, обычно в другом потоке.

### Ловушка прокси-аннотаций

gRPC вызывает объект, захваченный `bindService()`, а не Spring-прокси. `@PreAuthorize`, `@Secured`,
`@RolesAllowed`, `@Transactional`, `@Cacheable` и `@Async` на gRPC-сервисе **ничего не делают**. Ставьте
авторизацию и транзакции на сервис приложения, который он вызывает. Группа `RuleGroup.GRPC` в `architecture-rules`
валит сборку на этих аннотациях и на зависимости от репозитория в классе `@LudwigGrpcService`. `@Idempotent` и
`@AdminAccess` - исключения (читаются рефлексией при привязке).

### Аутентификация

Запрет по умолчанию. Bearer-токен в метаданных `authorization` декодируется `JwtDecoder` приложения и
преобразуется его `Converter<Jwt, AbstractAuthenticationToken>` (`JwtPrincipalConverter`), поэтому один токен даёт
одного принципала и в gRPC, и в HTTP. Исключения: `grpc.health.v1.Health`, reflection при включении и методы из
`security.permit-unauthenticated` (каждый логируется на `WARN` при старте). Заголовки идентичности, которые
вырезает HTTP-цепочка (`x-ludwig-*`, `x-forwarded-*`, ...), вырезаются и здесь.

Сервис с бином `@LudwigGrpcService` и без `JwtDecoder` **не стартует**, называя
`ludwig.grpc.server.security.enabled`; выставление `false` логируется на `WARN`.

### Ошибки

Разрешаются `ProblemMapperRegistry` web-core и отрисовываются как `google.rpc.Status` (таблица кодов, `ErrorInfo`,
`LocalizedMessage`, `BadRequest`, `RetryInfo` - см. [grpc-core](../grpc-core/README.ru.md#проблемы-как-статус-grpc)).
Неотображённое исключение - `INTERNAL` с общим текстом и correlation id. `onError(e)` транслируется как throw.
Ошибки до моста контекста (аутентификация, admission) используют локаль по умолчанию.

### Идемпотентный повтор

С `idempotency-spring-boot-starter` **unary**-метод с `@Idempotent` выполняется не более одного раза на
`idempotency-key` и scope (по умолчанию - полное имя метода), используя тот же `IdempotencyStore` и claim
`STANDALONE`, что и HTTP-фильтр. Повтор получает сохранённый ответ с `idempotency-replayed: true`;
переиспользованный ключ с другим запросом или дубликат во время выполнения первого получают проблемы модуля
идемпотентности; упавшие или отменённые вызовы не сохраняются. `@Idempotent(required = true)` без ключа →
`INVALID_ARGUMENT`. `@Idempotent` без `IdempotencyStore` или на потоковом методе валит старт.

### Административные методы

При административной поверхности security-стартера (`ludwig.admin`) метод `@LudwigGrpcService` с
`@AdminAccess("<area>.<tier>")` защищается так же, как HTTP-обработчик под `/admin/**`. Работают те же
`AdminCallerPolicy` и `AdminAccessEvaluator`, поэтому одни и те же `ludwig.admin.grants` действуют на обоих
транспортах:

- партнёры, персональные токены доступа (если не включён `ludwig.admin.allow-personal-access-tokens`) и люди не
  через `human-callers.required-via` отклоняются, как по HTTP;
- операция, которой у вызывающего нет → `PERMISSION_DENIED`, с аудитом и метрикой административного отказа
  (действие в аудите - имя метода);
- привилегированной операции нужны метаданные `admin-reason` (не длиннее 500 символов, как заголовок
  `Admin-Reason`) → иначе `INVALID_ARGUMENT`;
- обработчик выполняется с привязанным `AdminInvocation` в потоке каждого callback, поэтому `AdminAudit`
  записывает операцию, причину и исполнителя.

Старт падает с именем метода, если операции нет в каталоге, если операция входит в
`ludwig.admin.approval.required` (подтверждение вторым администратором есть только в HTTP) и для любого
аннотированного метода, когда административная поверхность не настроена. Ограничение по объектам применяется в
сервисе приложения, который вызывает метод.

### Health и остановка

`grpc.health.v1.Health` регистрируется всегда, следует `ReadinessState` Spring (для сервера и по сервисам) и
анонимен. При остановке переходит в `NOT_SERVING`, сервер перестаёт принимать, незавершённые вызовы получают
`shutdown-grace`, затем отменяются.

### Slice для тестов

```java
@LudwigGrpcServerTest
@Import({CatalogGrpcService.class, TestTokens.class})
class CatalogGrpcServiceTest {
    @Autowired ManagedChannel channel;   // in-process, та же цепочка, что в продакшене
}
```

Поднимает этот стартер плюс конфигурацию проблем и предпочтений web-core на in-process транспорте.
Аутентификация включена: объявите `JwtDecoder` или выставьте `ludwig.grpc.server.security.enabled=false` в
`properties`.

## Конфигурация

Все ключи требуют перезапуска.

| Свойство | По умолчанию | Смысл |
|---|---|---|
| `ludwig.grpc.server.enabled` | `true` | `false` ничего не привязывает |
| `ludwig.grpc.server.port` | `9090` | `0` = свободный порт |
| `ludwig.grpc.server.address` | все интерфейсы | |
| `ludwig.grpc.server.in-process-name` | - | Обслуживать in-process (тесты) |
| `ludwig.grpc.server.max-inbound-message-size` | `4MB` | Больше → `RESOURCE_EXHAUSTED` |
| `ludwig.grpc.server.max-inbound-metadata-size` | `8KB` | JWT помещается, «бомба» заголовков - нет |
| `ludwig.grpc.server.max-concurrent-calls-per-connection` | `100` | Один вызывающий не монополизирует под |
| `ludwig.grpc.server.max-connection-age` | `30m` | Заставляет клиентов переразрешать адреса (перебалансировка после масштабирования) |
| `ludwig.grpc.server.max-connection-age-grace` | `30s` | Поднимите для долгих server-streaming вызовов |
| `ludwig.grpc.server.handshake-timeout` | `20s` | |
| `ludwig.grpc.server.keepalive.permit-time` | `30s` | ≤ keepalive-времени клиентов; согласовано со значением клиентского стартера - меняйте вместе |
| `ludwig.grpc.server.keepalive.permit-without-calls` | `true` | |
| `ludwig.grpc.server.executor.threads` | `64` | Потоки обработчиков |
| `ludwig.grpc.server.executor.queue-capacity` | `256` | Сверх потоков + очереди → `RESOURCE_EXHAUSTED` |
| `ludwig.grpc.server.shutdown-grace` | `30s` | Время на незавершённые вызовы при остановке |
| `ludwig.grpc.server.tls.bundle` | - | SSL bundle Spring Boot с ключом и сертификатом |
| `ludwig.grpc.server.tls.client-auth` | `none` | `optional` / `require` (mTLS аутентифицирует транспорт; принципал всё равно из токена) |
| `ludwig.grpc.server.plaintext` | `false` | Первый переключатель plaintext |
| `ludwig.grpc.allow-plaintext` | `false` | Второй переключатель, общий с клиентским стартером |
| `ludwig.grpc.production-profiles` | `prod, production` | Под ними plaintext и reflection запрещены |
| `ludwig.grpc.server.security.enabled` | `true` | `false` обслуживает без аутентификации (логируется) |
| `ludwig.grpc.server.security.permit-unauthenticated` | `[]` | `pkg.Service/Method` или `pkg.Service/*` |
| `ludwig.grpc.server.reflection.enabled` | `false` | Запрещено в продакшен-профиле |

**Plaintext** допускается на loopback, иначе только при `plaintext: true` и `ludwig.grpc.allow-plaintext: true`
одновременно, и никогда в продакшен-профиле (включая loopback). Режима «шифрует mesh» нет. Все запрещённые
настройки сообщаются разом, каждая с именем своего свойства.

## Развёртывание

- **Второй порт**: откройте `9090` (или `port`) в контейнере и `Service`; сетевые политики могут открыть gRPC без
  REST-поверхности.
- **Headless `Service`** для вызывающих, чтобы `round_robin` клиентского стартера балансировал по вызовам (за
  обычным `Service` HTTP/2 сажает все вызовы на одно соединение).
- **gRPC readiness probe** (`grpc: { port: 9090 }`) по желанию; согласуется с HTTP readiness.
- **TLS**: SSL bundle (`spring.ssl.bundle.*`) из смонтированных сертификатов; ротация - передеплоем.

## Наблюдаемость

| Сигнал | Смысл |
|---|---|
| `ludwig.grpcserver.requests{service,method,status}` | gRPC observation Micrometer (timer) и серверный span |
| `ludwig.grpcserver.calls.nodeadline{service,method}` | Вызовы без дедлайна; такие вызывающие могут держать поток обработчика сколько угодно |
| `INFO` в логгер `ludwig.grpcserver.<service>` | Одна строка на вызов: метод, статус, длительность в мс, correlation id. **Без payload** |
| `INFO gRPC server started on <address> serving <services>` | Старт |
| `ERROR gRPC method <m> failed` | Неотображённое исключение (отрисовано как `INTERNAL`) |
| `WARN gRPC calls still in flight after <grace>; cancelling them` | Остановка прервала вызовы |

Корреляция берётся из `x-correlation-id` по правилам observability и привязывается к MDC в обработчике.

**Рекомендуемые алерты:**

| Условие | Почему |
|---|---|
| Доля `status` ∈ {`INTERNAL`, `UNKNOWN`, `UNAVAILABLE`} > 1% | Ошибки сервера |
| Рост `status="RESOURCE_EXHAUSTED"` | Executor перегружен или слишком большие сообщения |
| Рост `ludwig_grpcserver_calls_nodeadline_total` | Вызывающий без дедлайнов (исправить клиента) |

**Известные пробелы:** нет gauge глубины очереди executor-а и активных вызовов.

## Аудит

Собственных событий нет. Отказы авторизации от `@PreAuthorize` на сервисе приложения аудирует security-стартер
(`access.denied`).

**Известный пробел:** отклонённая аутентификация (нет/неверный/истёкший токен) в gRPC-вызове не записывается в
аудиторский след - только отрисовывается как `UNAUTHENTICATED`. То же верно для HTTP-цепочки.

## Runbook

| Симптом | Вероятная причина | Что делать |
|---|---|---|
| Старт падает с упоминанием `ludwig.grpc.server.security.enabled` | Нет `JwtDecoder` | Настроить resource server или явно отключить безопасность (не в продакшене) |
| Старт падает на plaintext / reflection | Продакшен-профиль или нет второго переключателя | Настроить `tls.bundle`; reflection только вне продакшена |
| Клиенты получают `UNAUTHENTICATED` | Нет/истёк токен, неверная audience, заголовки идентичности вырезаны | Проверить токен клиента и audience resource server-а |
| `@PreAuthorize` на gRPC-сервисе не действует | Ловушка прокси-аннотаций | Перенести на сервис приложения |
| `RESOURCE_EXHAUSTED` под нагрузкой | Превышены `executor.threads` + `queue-capacity` | Масштабировать или поднять потоки (следить за пулом БД) |
| Весь трафик на одном поде после масштабирования | Долгие соединения за обычным `Service` | Использовать headless `Service`; `max-connection-age` заставляет переразрешать |
| Потоковые вызовы обрываются при деплое | Мал `max-connection-age-grace` / `shutdown-grace` | Поднять их (и `terminationGracePeriodSeconds`) |

## Тестирование

`@LudwigGrpcServerTest` (in-process, та же цепочка). Собственные тесты модуля покрывают порядок цепочки,
восстановление контекста на каждом callback через потоки, отрисовку проблем, отказы аутентификации, запреты
plaintext/reflection, лимиты, health и остановку, идемпотентный повтор; группа `GRPC` в `architecture-rules`
проверяет сервисы.

## Проектные решения

- **Netty на своём порту**, а не gRPC-over-servlet: независим от Netty Boot и HTTP/2-настроек Tomcat.
- **Фиксированный порядок цепочки**; поставить interceptor перед встроенной стадией нельзя.
- **Без логирования payload.**
- **Повтор - только unary**; для client-streaming и bidirectional нет особого поведения кроме цепочки.
- **Нет проверки совместимости изменений `.proto` на проводе** (см. architecture-rules).
