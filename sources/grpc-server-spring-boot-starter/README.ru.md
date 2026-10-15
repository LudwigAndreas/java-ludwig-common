# grpc-server-spring-boot-starter

gRPC-сервер как конфигурация. Подключите стартер, унаследуйтесь от сгенерированного `ImplBase`,
поставьте `@LudwigGrpcService` - и сервис обслуживается на собственном порту за фиксированной цепочкой
перехватчиков, которая переносит через транспорт принципала, correlation id, предпочтения
вызывающего и маппинг ошибок RFC 9457. Своего `@Configuration` не нужно.

Построен прямо на `grpc-java` (Spring gRPC требует Boot 3.5+). Свойства - под `ludwig.grpc.server.*`,
никогда не `spring.grpc.*`, чтобы будущее обновление Boot не связало те же ключи с другим смыслом.
Контракт (`.proto`) живёт в модуле `<service>-grpc-api`; соглашения по proto, ключи метаданных и
таблица кодов статуса описаны в [grpc-core](../grpc-core/README.ru.md).

## Быстрый старт

```java
@LudwigGrpcService
class CatalogGrpcService extends CatalogServiceGrpc.CatalogServiceImplBase {

    private final ProductService products;          // работу делает прикладной сервис

    @Override
    public void getProduct(GetProductRequest request, StreamObserver<ProductReply> reply) {
        reply.onNext(mapper.toReply(products.get(request.getId())));
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

При настроенном сервере ресурсов (`spring.security.oauth2.resourceserver.jwt.*`) больше ничего не
нужно. Бросайте доменные исключения как в контроллере: `ExceptionProblemMapper`, которые уже
поставляют ваши модули, определяют и gRPC-статус.

## Цепочка и почему она фиксирована

```
observation → correlation → access log → exception→status → admission → authentication
            → context bridge (принципал, MDC correlation, предпочтения) → [идемпотентный replay]
            → ваши бины LudwigGrpcServerInterceptor → обработчик
```

Порядок - это порядок объявления `BuiltInServerInterceptor.Stage`, и ничего больше. У любого другого
порядка есть конкретный дефект: observation снаружи, чтобы отказанный вызов тоже измерялся;
транслятор исключений снаружи аутентификации, чтобы отвергнутый токен отображался как любая другая
проблема; admission до аутентификации, чтобы перегруженный сервер не проверял токен вызова, который
сейчас отклонит; context bridge внутри аутентификации, потому что принципал - одно из того, что он
привязывает. Свои перехватчики - бины `LudwigGrpcServerInterceptor`, они всегда идут после встроенных
стадий. Обычный бин `ServerInterceptor` намеренно не подхватывается.

**Thread-local восстанавливаются на каждом callback**, а не один раз на вызов. gRPC доставляет
события вызова на любой свободный поток исполнителя, а обработчик унарного вызова выполняется в
`onHalfClose`, как правило не на том потоке, где работали перехватчики. Поэтому
`SecurityContextHolder`, запись correlation в MDC и привязанные `UserPreferences` выставляются вокруг
каждого callback и затем возвращаются - обработчик видит своего вызывающего, а поток из пула никогда
не несёт личность предыдущего.

## Ловушка proxy-аннотаций

gRPC вызывает объект, захваченный `bindService()`, а не Spring-прокси вокруг бина. `@PreAuthorize`,
`@Secured`, `@RolesAllowed`, `@Transactional`, `@Cacheable` и `@Async` на gRPC-сервисе компилируются,
стартуют и **ничего не делают**. gRPC-сервис - адаптер: авторизация и транзакции - на прикладном
сервисе, который он вызывает. `RuleGroup.GRPC` из `architecture-rules` валит сборку на любой из этих
аннотаций и на зависимости от репозитория в классе с `@LudwigGrpcService`.

Исключение - `@Idempotent`: сервер читает её рефлексией при привязке сервиса.

## Аутентификация

Запрет по умолчанию. Bearer-токен из метаданных `authorization` декодируется `JwtDecoder` самого
приложения и конвертируется его `Converter<Jwt, AbstractAuthenticationToken>` (платформенный
`JwtPrincipalConverter`), так что gRPC- и HTTP-вызывающий с одним токеном - один и тот же принципал с
теми же полномочиями. Исключения: `grpc.health.v1.Health`, reflection, если включён, и методы из
`security.permit-unauthenticated` (каждый пишется в WARN при старте). Заголовки личности, которые
срезает HTTP-цепочка (`x-ludwig-*`, `x-forwarded-*`, ...), срезаются и здесь.

**Сервис без способа аутентификации не стартует.** При бине `@LudwigGrpcService` и отсутствии
`JwtDecoder` старт падает с указанием `ludwig.grpc.server.security.enabled`. Значение `false` - это
решение, которое записывается явно; оно пишется в WARN.

## Ошибки

Исключение разрешается `ProblemMapperRegistry` из `web-core`, ровно как для HTTP, и отображается как
`google.rpc.Status`: код по таблице из `grpc-core`, `ErrorInfo` с кодом проблемы, `LocalizedMessage`
на языке вызывающего, `BadRequest` для нарушений, `RetryInfo`, если есть retry-after. Исключение без
маппера - это `INTERNAL` с общим текстом и correlation id, но никогда не его собственное сообщение.
Обработчик, вызвавший `onError(e)`, транслируется так же, как бросивший исключение. Ошибка до context
bridge (аутентификация, admission) отображается на языке платформы по умолчанию, потому что язык
вызывающего ещё не разрешён.

## Идемпотентный replay

При наличии `idempotency-spring-boot-starter` **унарный** метод с `@Idempotent` выполняется не более
одного раза на `idempotency-key` и scope (по умолчанию - полное имя метода). Захват ключа, хранилище и
проблемы - модуля идемпотентности: тот же `IdempotencyStore`, тот же режим `STANDALONE`, что у
`IdempotencyFilter`; таблиц и SQL здесь не добавляется. Повтор с тем же запросом получает сохранённый
ответ с `idempotency-replayed: true`; переиспользованный ключ с другим запросом и дубликат, пока
выполняется первый, получают проблемы модуля идемпотентности; упавший или отменённый вызов не
сохраняется. `@Idempotent(required = true)` без ключа - `INVALID_ARGUMENT`. Метод с `@Idempotent` без
бина `IdempotencyStore` или стриминговый метод валит старт с указанием метода.

## Наблюдаемость

- `ludwig.grpcserver.requests{service,method,status}` - наблюдение gRPC из Micrometer,
  переименованное; оно же серверный span.
- `ludwig.grpcserver.calls.nodeadline{service,method}` - вызовы без дедлайна. Такой вызывающий может
  держать поток обработчика сколько угодно.
- Одна строка INFO на вызов в логгер `ludwig.grpcserver.<service>`: метод, статус, длительность,
  correlation id. **Полезная нагрузка не пишется никогда.**
- Correlation id разрешается резолвером observability по его же правилам из `x-correlation-id` (или
  настроенного заголовка) и привязывается к MDC в обработчике. Без
  `observability-spring-boot-starter` вызовы его не несут.

## Конфигурация

Любой ключ требует перезапуска: сервер, транспорт и цепочка строятся один раз при старте.

| Ключ | По умолчанию | |
|---|---|---|
| `ludwig.grpc.server.enabled` | `true` | `false` - ничего не слушать, какие бы сервисы ни были |
| `ludwig.grpc.server.port` | `9090` | `0` - свободный порт |
| `ludwig.grpc.server.address` | все интерфейсы | |
| `ludwig.grpc.server.in-process-name` | - | обслуживать in-process вместо порта (тесты) |
| `ludwig.grpc.server.max-inbound-message-size` | `4MB` | больше - `RESOURCE_EXHAUSTED` |
| `ludwig.grpc.server.max-inbound-metadata-size` | `8KB` | JWT помещается, «бомба» заголовков - нет |
| `ludwig.grpc.server.max-concurrent-calls-per-connection` | `100` | один вызывающий не монополизирует под |
| `ludwig.grpc.server.max-connection-age` | `30m` | заставляет вызывающих перерезолвиться, что и перебалансирует после масштабирования |
| `ludwig.grpc.server.max-connection-age-grace` | `30s` | увеличьте для долгих server-streaming вызовов, иначе они будут отменены |
| `ludwig.grpc.server.handshake-timeout` | `20s` | |
| `ludwig.grpc.server.keepalive.permit-time` | `30s` | не больше keepalive клиентов; умолчание клиентского стартера согласовано - меняйте вместе |
| `ludwig.grpc.server.keepalive.permit-without-calls` | `true` | |
| `ludwig.grpc.server.executor.threads` | `64` | потоки обработчиков |
| `ludwig.grpc.server.executor.queue-capacity` | `256` | сверх потоков + очереди вызов получает `RESOURCE_EXHAUSTED`, а не ждёт без границы |
| `ludwig.grpc.server.shutdown-grace` | `30s` | столько получают вызовы в полёте при остановке, затем отмена |
| `ludwig.grpc.server.tls.bundle` | - | SSL-бандл Spring Boot с ключом и сертификатом |
| `ludwig.grpc.server.tls.client-auth` | `none` | `optional` / `require` - mTLS аутентифицирует транспорт; принципал всё равно из токена |
| `ludwig.grpc.server.plaintext` | `false` | первый из двух переключателей открытого текста |
| `ludwig.grpc.allow-plaintext` | `false` | второй, общий с клиентским стартером |
| `ludwig.grpc.production-profiles` | `prod, production` | при них открытый текст и reflection запрещены |
| `ludwig.grpc.server.security.enabled` | `true` | `false` - без аутентификации, и это нужно записать явно |
| `ludwig.grpc.server.security.permit-unauthenticated` | `[]` | `pkg.Service/Method` или `pkg.Service/*` |
| `ludwig.grpc.server.reflection.enabled` | `false` | запрещён при промышленном профиле |

**Открытый текст.** Без `tls.bundle` сервер слушал бы открытым текстом. Это допустимо на loopback,
иначе - только при `plaintext: true` **и** `ludwig.grpc.allow-plaintext: true`, и никогда при
промышленном профиле, включая loopback. Режима «шифрует mesh» намеренно нет: отсутствующий или
неверно настроенный sidecar - ровно тот случай, когда токен читается в сети. Все отклонённые
настройки сообщаются сразу, каждая с указанием своего свойства.

## Health и остановка

`grpc.health.v1.Health` регистрируется всегда, следует `ReadinessState` Spring для сервера в целом и
для каждого сервиса по имени и открыт анонимным вызывающим. При остановке он первым переходит в
`NOT_SERVING`, затем сервер перестаёт принимать вызовы, вызовы в полёте получают `shutdown-grace`,
оставшиеся отменяются.

## Что нужно при развёртывании

- **Второй порт.** Откройте `9090` (или ваш `port`) в контейнере и в `Service`; тогда сетевая
  политика может открыть gRPC соседям, не открывая REST.
- **Headless `Service`** для вызывающих, чтобы `round_robin` клиентского стартера балансировал по
  вызовам. Обычный `Service` балансирует по соединениям, а HTTP/2 кладёт все вызовы в одно соединение.
- **gRPC readiness-проба** (`grpc: { port: 9090 }`), если Kubernetes должен опрашивать health; она
  согласована с HTTP readiness.

## Тестирование

```java
@LudwigGrpcServerTest
@Import({CatalogGrpcService.class, TestTokens.class})
class CatalogGrpcServiceTest {
    @Autowired ManagedChannel channel;   // in-process, та же цепочка, что в production
}
```

`@LudwigGrpcServerTest` поднимает автоконфигурации этого стартера и конфигурацию проблем и
предпочтений из web-core, ничего больше, на in-process транспорте со случайным именем.
Аутентификация включена, как в production: объявите `JwtDecoder` или задайте
`ludwig.grpc.server.security.enabled=false` в `properties`. Прочие автоконфигурации добавляются через
`@ImportAutoConfiguration`.

## Чего он намеренно не делает

- **Нет gRPC-over-servlet.** Shaded Netty на своём порту: Netty gRPC независим от Netty, которым
  управляет Boot, а настройка HTTP/2 не попадает в Tomcat.
- **Нет настраиваемого порядка цепочки** и нет способа поставить перехватчик перед встроенной стадией.
- **Нет особого поведения для client-streaming и bidi вызовов**, кроме цепочки; replay - только унарный.
- **Нет логирования полезной нагрузки.**
- **Нет проверки совместимости `.proto` на уровне провода** - см. README architecture-rules.
