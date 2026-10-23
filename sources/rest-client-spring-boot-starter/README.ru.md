# rest-client-spring-boot-starter

*[English](README.md) · **Русский***

Исходящий HTTP как конфигурация. Сервис объявляет по именованному клиенту на каждую зависимость в
`application.yaml`, и каждый получает свой пул соединений, таймауты, аутентификацию, политику Resilience4j,
сериализацию, логирование, метрики и аудит - ни один клиент не может заморить другой. Клиенты используются как
декларативные интерфейсы `@LudwigRestClient`, внедряемые бины `RestClient`/`WebClient` или через реестр; за всеми
тремя один конвейер, одинаковый в sync- и async-режимах. Стартовый валидатор отвергает конфигурации, корректные по
полям и неверные в целом.

| | |
|---|---|
| Роль | starter |
| Зависит от (в репозитории) | [`audit-core`](../audit-core), [`observability`](../observability-spring-boot-starter), [`outbound-token-core`](../outbound-token-core), [`web-core`](../web-core-spring-boot-starter) |
| Используется | стартерами `export`, `reconciliation`, модулем `test-support-local`; `crud-service-example` |
| Поверхности | исходящий HTTP |

```
ru.ludwigandreas.restclient
├── annotation      @LudwigRestClient, @EnableLudwigRestClients
├── spi             все точки расширения (не зависят от остального модуля - ArchUnit)
├── config          свойства, глубокое слияние, валидатор, автоконфигурация
├── core            реестр, runtime, оба конвейера выполнения
├── auth            фабрика аутентификаторов и поставляемые провайдеры (кэш/получение/ретрансляция токена - outbound-token-core)
├── resilience      политики, решение о повторе, классификатор ошибок
├── transport       JDK / Apache / Reactor Netty, TLS, прокси, gauge пулов
├── observability   метрики, слушатели, редактирование, логирование обмена, аудит
├── error           иерархия исключений и цепочка трансляторов
├── registrar       сканирование интерфейсов и регистрация прокси
└── test            @LudwigRestClientTest
```

## Использование

```xml
<dependency>
    <groupId>ru.ludwigandreas</groupId>
    <artifactId>rest-client-spring-boot-starter</artifactId>
</dependency>
```

```yaml
ludwig:
  rest-client:
    defaults:                 # наследуется каждым клиентом; ключи клиента переопределяют
      connect-timeout: 2s
      read-timeout: 10s
      resilience:
        retry: { max-attempts: 3, wait-duration: 200ms, exponential-backoff-multiplier: 2.0, randomized-wait-factor: 0.5 }
        circuit-breaker: { failure-rate-threshold: 50, sliding-window-size: 100, wait-duration-in-open-state: 30s }
    clients:
      billing:                # длинный таймаут сокета + OAuth2 client credentials
        base-url: https://billing.internal/api/v1
        read-timeout: 120s
        auth:
          type: oauth2-client-credentials
          registration-id: billing-sa
        resilience:
          bulkhead: { max-concurrent-calls: 20 }
      pricing:                # короткий таймаут + статический bearer из секрета
        base-url: https://pricing.internal
        read-timeout: 800ms
        auth:
          type: bearer
          token: ${PRICING_TOKEN}
        resilience:
          retry: { max-attempts: 1 }
```

`billing`: таймаут сокета 120s, кэшируемый машинный токен с ранним обновлением и одним перевыпуском при 401, три
попытки с backoff и jitter, breaker на 100 вызовов, не более 20 одновременных вызовов. `pricing`: таймаут 800ms,
статический токен, без повторов. Раздельные пулы, breaker-ы и разрешения.

### Три модели программирования

**1. Декларативные интерфейсы (основная):**

```java
@LudwigRestClient("billing")
public interface BillingApi {

    @GetExchange("/invoices/{id}")
    Invoice invoice(@PathVariable UUID id);

    @PostExchange("/invoices")
    Invoice create(@RequestBody NewInvoice invoice);
}
```

Находятся сканированием под `@SpringBootApplication`; в других местах - через
`@EnableLudwigRestClients(basePackageClasses = BillingApi.class)`. Тип возврата, не соответствующий режиму клиента
(например, `Mono` у `sync`-клиента), валит старт с указанием интерфейса, метода и клиента.

**2. Внедряемые бины:** каждый `sync`-клиент - бин `RestClient` с именем `<name>RestClient` и квалификатором
`<name>`; каждый `async`-клиент - аналогично `WebClient`. `OrderService(@Qualifier("billing") RestClient billing)`.
Для разовых вызовов (`HEAD`, потоковая загрузка).

**3. Реестр:** `registry.rest("billing")`, `registry.reactive("pricing")` - для зависимостей, выбираемых во время
выполнения. Неизвестное имя бросает исключение со списком настроенных; блокирующее представление реактивного
клиента запрещено.

**Программные переопределения:** бины `LudwigRestClientCustomizer` / `LudwigWebClientCustomizer` применяются после
всех настроек из YAML:

```java
@Bean
LudwigRestClientCustomizer partnerMediaTypeCustomizer() {
    return new LudwigRestClientCustomizer() {
        @Override public boolean supports(String clientName) { return "partner".equals(clientName); }
        @Override public void customize(String clientName, RestClient.Builder builder) {
            builder.messageConverters(converters -> converters.add(0, new PartnerVendorMediaTypeConverter()));
        }
    };
}
```

### Приоритет

1. Встроенные значения (`ClientPropertiesMerger.builtInDefaults()`);
2. блок `defaults`;
3. блок клиента;
4. бины-кастомайзеры;
5. заголовки отдельного запроса.

Уровни 1-3 - явное глубокое слияние:

| Вид | Правило |
|---|---|
| Скаляры | Побеждает верхний уровень, если задан |
| Вложенные блоки | Слияние по полям (`retry.max-attempts` сохраняет унаследованный backoff) |
| Списки | **Заменяются** (`retry-on-status: [503]` означает только 503) |
| Списки `additional-*` | **Конкатенируются** |
| Объявленный блок `bulkhead` / `rate-limiter` | **Включает политику** (`enabled: false` всё равно побеждает, где указан) |
| Карты (`default-headers`) | Слияние по ключам |
| `base-url`, `auth.relay-enabled` | **Никогда не наследуются** |

**Заголовки запроса** (потребляются, не отправляются):

| Заголовок | Эффект |
|---|---|
| `X-Ludwig-Retry: true` | Повторять неидемпотентный запрос; вызывающий утверждает, что повтор безопасен (ключ идемпотентности, уникальное ограничение) |
| `X-Ludwig-Retry: false` | Подавить повтор |
| `X-Ludwig-Max-Attempts: 2` | Уменьшить (никогда не увеличить) предел попыток |

### Конвейер

```
RateLimiter → Bulkhead → CircuitBreaker → Retry → TimeLimiter → [authenticate → send]
```

Одинаков в обоих режимах и не настраивается:

- **Повтор внутри breaker-а**: одно разрешение и один результат на *логический* вызов; открытый breaker превращает
  три попытки в ноль, а не усиливает нагрузку на отказывающую зависимость.
- **Bulkhead снаружи повтора**: предел ограничивает вызовы в работе, а не попытки.
- **Rate limiter снаружи всех**: партнёр видит заданную частоту, а не кратную.
- **Time limiter внутри всех**: ограничивает одну попытку; `retry.max-elapsed-time` ограничивает вызов.
- **Аутентификация внутри повтора**: делает возможным `401 → обновление → повтор`.

Breaker учитывает 408, 429, 500, 502, 503, 504; **не** 400, 401, 403, 404, 409, 422 (ошибка клиента значит, что
партнёр здоров, а этот сервис отправил что-то неверное), а также ошибки аутентификации и отказы.

Breaker, bulkhead, rate limiter и time limiter - объекты Resilience4j в реестрах приложения, привязанных к Micrometer,
с именем клиента. **Повтор - собственный модуля** (на `IntervalFunction` Resilience4j): учитывает `Retry-After`,
держит бюджет времени и разделяет решения между блокирующим и реактивным путями. Поэтому метрик
`resilience4j.retry.*` для этих клиентов нет; есть `ludwig.restclient.retries`.

### Аутентификация

| `auth.type` | Ключи | Примечания |
|---|---|---|
| `none` | - | По умолчанию |
| `basic` | `username`, `password` | Запрещено по plain HTTP к не-loopback хосту |
| `bearer` | `token` **или** `token-supplier`, `scheme` | Ровно одно из двух |
| `api-key` | `key`, `header-name` **или** `query-param-name`, `value-prefix` | Вариант через query выдаёт предупреждение (URI попадают в логи прокси) |
| `oauth2-client-credentials` | `registration-id`, `refresh-skew`, `scopes` | Кэшируется, обновляется на `refresh-skew` (30s) раньше, single-flight, одно повторное получение при 401 вне бюджета повторов |
| `oauth2-token-relay` | `relay-enabled`, `fail-when-no-token` | См. ниже |
| `custom` | `authenticator` | Именованный бин `ClientAuthenticator` |

Кэш токенов, получение и чтение для ретрансляции - [`outbound-token-core`](../outbound-token-core).

**Ретрансляция токена** передаёт учётные данные вызывающих третьей стороне; три защиты: `relay-enabled: true` на
самом клиенте (никогда не наследуется); нет входящего токена → вызов падает, а не уходит анонимно; клиент
ретрансляции в сервисе, не являющемся resource server, валит старт.

**Новый тип аутентификации** - один бин `ClientAuthenticationProvider`:

```java
public class HawkAuthenticationProvider implements ClientAuthenticationProvider {

    @Override public String type() { return "hawk"; }

    @Override public Class<? extends ClientAuthProperties> propertiesType() { return HawkProperties.class; }

    @Override public ClientAuthenticator create(String clientName, ClientAuthProperties properties) {
        HawkProperties hawk = (HawkProperties) properties;
        Mac mac = Mac.getInstance("HmacSHA256");   // дорогая работа здесь, а не на каждый запрос
        mac.init(new SecretKeySpec(hawk.getKey().getBytes(UTF_8), "HmacSHA256"));
        return new ClientAuthenticator() {
            @Override public void authenticate(AuthRequest request) {
                String nonce = UUID.randomUUID().toString();
                String payload = request.method() + "\n" + request.uri().getPath() + "\n" + nonce;
                String signature = Base64.getEncoder().encodeToString(mac.doFinal(payload.getBytes(UTF_8)));
                request.headers().set("Authorization",
                        "Hawk id=\"" + hawk.getId() + "\", nonce=\"" + nonce + "\", mac=\"" + signature + "\"");
            }
            @Override public String describe() { return "hawk(id=" + hawk.getId() + ")"; }
        };
    }
}
```

```yaml
ludwig:
  rest-client:
    clients:
      partner:
        base-url: https://partner.example.com
        auth: { type: hawk, id: "${HAWK_ID}", key: "${HAWK_KEY}" }
```

Блок `auth` связывается в `HawkProperties` из `defaults.auth`, затем из блока клиента. Провайдер, чей `type()`
совпадает со встроенным, заменяет встроенный. Для одного клиента без новых свойств - `type: custom` с
`auth.authenticator: <bean>`.

### Ошибки

| Исключение | Смысл | Как web-core отрисует, если оно выйдет из контроллера |
|---|---|---|
| `RestClientResponseException` | Партнёр ответил ошибкой (статус, отредактированные заголовки, фрагмент тела, разобранный `ProblemDetail`) | 429 → 429; **4xx → 500** (этот сервис отправил неверное); 5xx → 503 `upstream-unavailable` |
| `RestClientTimeoutException` | Истёк дедлайн - запрос **мог** быть обработан | 503 |
| `RestClientConnectionException` | Запрос не дошёл до партнёра | 503 |
| `RestClientAuthenticationException` | Нет пригодных учётных данных | 500 |
| `RestClientCallNotPermittedException` | Отказала собственная политика клиента (breaker, bulkhead, rate limit) | 503 |

Каждое несёт имя клиента и correlation id в сообщении. Обработка ошибок применяется к `retrieve()`, а не к
`exchange()`. Партнёру с конвертом ошибок не по RFC 9457 нужен бин `ResponseErrorTranslator`:

```java
@Component
public class PartnerErrorTranslator implements ResponseErrorTranslator {
    @Override public boolean supports(String clientName) { return "partner".equals(clientName); }
    @Override public RuntimeException translate(ErrorContext context) {
        if (context.statusCode() != 409 || context.body() == null) {
            return null;                                   // передать следующему транслятору
        }
        return new DuplicateOrderException(readOrderId(context.body()));
    }
}
```

Трансляторы выполняются в порядке `@Order`; встроенный - последним.

### Fallback

```yaml
ludwig:
  rest-client:
    clients:
      billing:
        resilience:
          fallback:
            handler: cachedInvoiceFallback         # для всех методов
            methods:
              BillingApi#invoice: cachedInvoiceFallback
```

Только для ошибок, означающих, что *вызов не удалось выполнить или завершить*; никогда для 4xx и ошибок
аутентификации. Только декларативные интерфейсы.

### Слушатели

Бины `RestClientListener` наблюдают `onResponse`, `onRetry` и др. по клиентам. Слушатель, бросивший исключение,
никогда не ломает вызов (логируется раз на класс, учитывается); не используйте слушателей для принуждения; не
блокируйте в них.

### Транспорты и TLS

| `transport` | Пул | HTTP/2 | Примечания |
|---|---|---|---|
| `http-client` (по умолчанию) | не настраивается | да | JDK; блок `pool` сообщается как проигнорированный |
| `apache` | настраивается | нет | Когда важен пул |
| `reactor-netty` | настраивается | да | Подразумевается при `mode: async` |

Пулы никогда не делятся между клиентами. `compression: true` одинаков на всех трёх. TLS - по клиенту (truststore,
keystore для mTLS, протоколы TLSv1.3/1.2, наборы шифров, проверка имени хоста). `tls.trust-all` и
`tls.hostname-verification: false` требуют верхнеуровневого `ludwig.rest-client.allow-trust-all: true` **и**
отсутствия активного продакшен-профиля.

### Тестирование своих клиентов

```java
@LudwigRestClientTest
@TestPropertySource(properties = {
    "ludwig.rest-client.clients.billing.base-url=http://localhost:${wiremock.server.port}",
    "ludwig.rest-client.clients.billing.resilience.retry.max-attempts=2"
})
class BillingApiTest {
    @Autowired BillingApi billing;
    @Test void retriesOnce() { ... }
}
```

Slice поднимает только этот стартер и находит интерфейсы так же, как продакшен.

## Конфигурация

Длительности: `200ms`, `2s`, `5m`. Ключи с пометкой **refresh** перечитываются раз в секунду на клиента (с
hot-reload, actuator refresh или продлением Vault); вызов в работе сохраняет свою политику; неверное новое значение
оставляет предыдущее с предупреждением. Остальное требует перезапуска (запечено в пулы, `SSLContext`,
аутентификаторы, окна breaker-ов).

**Верхний уровень:**

| Свойство | По умолчанию | Смысл |
|---|---|---|
| `ludwig.rest-client.enabled` | `true` | `false` ничего не регистрирует |
| `ludwig.rest-client.allow-trust-all` | `false` | Второй переключатель ослаблений TLS |
| `ludwig.rest-client.production-profiles` | `prod,production` | Под ними ослабления TLS запрещены |
| `ludwig.rest-client.metrics.enabled` | `true` | |
| `ludwig.rest-client.metrics.max-uri-tags` | `100` | Сверх - `OTHER` |
| `ludwig.rest-client.metrics.pool` | `true` | Gauge пулов, где движок их сообщает |

**По клиенту** (в `ludwig.rest-client.clients.<name>` или `.defaults`):

| Ключ | По умолчанию | Перезагрузка | Примечания |
|---|---|---|---|
| `base-url` | - | restart | Обязателен; не наследуется |
| `mode` | `sync` | restart | `sync` / `async` |
| `transport` | `http-client` | restart | `http-client`, `apache`, `reactor-netty` |
| `connect-timeout` | `2s` | restart | |
| `read-timeout` | `10s` | restart | Наибольшая пауза между байтами, не дедлайн |
| `connection-request-timeout` | `2s` | restart | Ожидание соединения из пула |
| `request-timeout` | - | restart | Дедлайн одной попытки |
| `redirects` | `NORMAL` | restart | `NORMAL`, `NEVER`, `ALWAYS` |
| `compression` | `true` | restart | |
| `http2` | `false` | restart | |
| `user-agent` | `<app> (ludwig-rest-client; client=<name>)` | restart | |
| `default-headers.*`, `default-query-params.*` | - | restart | Слияние по ключам; значение из вызова побеждает |
| `pool.max-total` / `max-per-route` | `50` / `20` | restart | `apache`/`reactor-netty` |
| `pool.idle-eviction` / `time-to-live` / `validate-after-inactivity` / `keep-alive` | `30s` / `5m` / `2s` / `1m` | restart | |
| `tls.*`, `proxy.*` | - | restart | По клиенту |
| `auth.*` | `type: none` | restart | |
| `resilience.enabled` | `true` | restart | |
| `resilience.retry.enabled` / `max-attempts` | `true` / `3` | **refresh** | |
| `resilience.retry.wait-duration` / `exponential-backoff-multiplier` / `randomized-wait-factor` | `200ms` / `2.0` / `0.5` | **refresh** | |
| `resilience.retry.max-elapsed-time` | вычисляется | **refresh** | Бюджет времени (по умолчанию: все попытки с полным таймаутом плюс ожидания) |
| `resilience.retry.retry-on-status` | `408,425,429,500,502,503,504` | **refresh** | Заменяется, не расширяется |
| `resilience.retry.retry-on-exception` | - | restart | Имена классов |
| `resilience.retry.idempotent-methods-only` | `true` | **refresh** | |
| `resilience.retry.respect-retry-after` / `max-retry-after` | `true` / `30s` | **refresh** | |
| `resilience.circuit-breaker.*` | 50% из 100, открыт 30s | restart | |
| `resilience.bulkhead.enabled` | `false`, `true` если блок задан | restart | |
| `resilience.bulkhead.type` / `max-concurrent-calls` / `max-wait-duration` | `SEMAPHORE` / `25` / `0` | restart | `THREAD_POOL` и ненулевое ожидание запрещены для `async` |
| `resilience.rate-limiter.enabled` | `false`, `true` если задан | restart | На процесс: квота ÷ реплики |
| `resilience.rate-limiter.limit-for-period` / `timeout-duration` | `100` / `0` | **refresh** | |
| `resilience.rate-limiter.limit-refresh-period` | `1s` | restart | |
| `resilience.time-limiter.*` | выключен | restart | Только `async` |
| `resilience.fallback.handler` / `.methods` | - | restart | Имена бинов |
| `logging.level` | `BASIC` | **refresh** | `NONE`, `BASIC`, `HEADERS`, `BODY` |
| `logging.max-body-size` | `2048` | **refresh** | |
| `logging.redacted-headers` / `redacted-fields` | см. «Наблюдаемость» | **refresh** | Заменяются |
| `logging.additional-redacted-headers` / `-fields` | - | **refresh** | Конкатенируются |
| `logging.log-request-before-call` | `false` | **refresh** | Полезно, когда вызов зависает |
| `audit.enabled` | `false` | restart | |
| `audit.sampling-probability` | `1.0` | **refresh** | Только успехи |
| `audit.sink` | платформенный `AuditSink` | restart | Имя бина `AuditSink`, по клиенту |
| `audit.include-request-headers` / `-response-headers` | - | restart | Allow-list |
| `serialization.*` | mapper приложения | restart | Копия создаётся, только если что-то задано |

## Развёртывание

- Секреты (`auth.token`, `auth.password`, API-ключи, секреты клиентов, пароли keystore) - из хранилища секретов через
  плейсхолдеры; никогда не коммитить.
- Truststore/keystore монтируются по клиентам.
- Rate limit - на процесс: задавайте `limit-for-period` как квоту партнёра, делённую на число реплик, и
  пересматривайте при масштабировании.
- `base-url` указывает на сервис Kubernetes или шлюз; клиент не делает service discovery.

## Наблюдаемость

| Метрика | Теги | Смысл |
|---|---|---|
| `ludwig.restclient.requests` (timer) | `client`, `method`, `uri`, `status`, `outcome`, `exception` | RED по зависимости; `uri` - **шаблон**, ограничен `max-uri-tags` |
| `ludwig.restclient.retries` | `client`, `reason` | Попытки сверх первой; ÷ запросы = доля повторов, самый ранний признак деградации |
| `ludwig.restclient.circuitbreaker.transitions` | `client`, `from`, `to` | Считается (gauge раз в 15s пропускает короткие открытия) |
| `ludwig.restclient.auth.token.refreshes` | `client`, `type` | Полученные токены; намного больше одного за срок жизни = кэш не работает |
| `ludwig.restclient.calls.notpermitted` | `client`, `policy` | Вызовы, которые сервис отказался делать (сброс нагрузки) |
| `ludwig.restclient.listener.failures` | `client`, `listener`, `callback` | Слушатель бросил исключение |
| `ludwig.restclient.pool.{leased,pending,available,max}` | `client` | Только Apache; `pending` растёт раньше задержки |
| `resilience4j.circuitbreaker.state`, `resilience4j.bulkhead.*`, `resilience4j.ratelimiter.*` | `name` = клиент | Собственные метрики Resilience4j |
| `ludwig.audit.sink.failures{category="outbound-call"}` | | Отказы приёмника аудита (платформенная метрика) |

Ни учётные данные, ни токены, ни значения заголовков, ни раскрытые URI в теги не попадают.

**Трассировка**: W3C-контекст через инструментацию клиента Spring; correlation id - через
`CorrelationPropagatingRequestInterceptor` observability (и эквивалентный фильтр для `WebClient`).

**Логи**: по клиенту в `ludwig.restclient.<client>` (одну зависимость можно поднять до DEBUG отдельно). Завершённые
вызовы - `INFO`, неудачные - `WARN`. `logging.level` решает, что пишется (решение о данных); уровень SLF4J - пишется
ли (операционное решение). Тела материализуются только при ошибке или на уровне `BODY` **и** при объявленном
`Content-Length` меньше 256 KiB; потоковые ответы никогда не буферизуются. Редактируются по умолчанию: заголовки
`Authorization`, `Proxy-Authorization`, `Cookie`, `Set-Cookie`, `X-Api-Key`, `X-Auth-Token`, `Api-Key`; JSON-поля
`password`, `secret`, `token`, `access_token`, `refresh_token`, `id_token`, `client_secret`, `pin`, `otp`,
`card_number`, `cvv` (структурный обход через `Redactor` audit-core; списки клиента только расширяют правила
платформы).

**Рекомендуемые алерты:**

| Условие | Почему |
|---|---|
| `increase(ludwig_restclient_circuitbreaker_transitions_total{to="OPEN"}[5m]) > 0` | Зависимость отказывает |
| `rate(ludwig_restclient_retries_total[5m]) / rate(ludwig_restclient_requests_seconds_count[5m]) > 0.05` | Ранняя деградация |
| `ludwig_restclient_pool_pending > 0` дольше 1 мин | Пул исчерпан |
| `rate(ludwig_restclient_auth_token_refreshes_total[1h])` намного выше 1/срок жизни токена | Кэш токенов сломан |

## Аудит

Включается по клиенту (`audit.enabled`). Категория `outbound-call`. Запись несёт клиент, метод, **шаблонный** URI,
статус, исход, длительность, попытки, correlation id, trace id, принципал, время, заголовки из allow-list, тип
ошибки - без раскрытого URI и тел. Выборка применяется только к успехам; ошибки, отказы и ошибки аутентификации
пишутся всегда. Отказы собственной политики клиента - `DENIED`. Назначение, политика отказа и срок хранения -
платформенные (`ludwig.audit.*`); `audit.sink` позволяет направить след одного партнёра в отдельный бин `AuditSink`.

## Runbook

| Что произошло | Исключение | Лог | Метрики | Что делать |
|---|---|---|---|---|
| Партнёр отвечает 503, повторы успешны | нет | `WARN` на каждую неудачную попытку | `requests{status=200}`, `retries{reason="status:503"}` | Наблюдать; при устойчивости - связаться с партнёром |
| Партнёр отвечает 503, бюджет исчерпан | `RestClientResponseException` | `WARN` на каждую попытку | `requests{status=503}`, `retries` | Инцидент партнёра |
| Breaker открылся | `RestClientCallNotPermittedException` | нет (вызова не было) | `calls.notpermitted{policy="circuit-breaker"}`, `transitions{to="OPEN"}` | Инцидент партнёра; half-open через `wait-duration-in-open-state` |
| Bulkhead заполнен / rate limit | `RestClientCallNotPermittedException` | нет | `calls.notpermitted{policy=...}` | Нагрузка выше ёмкости; масштабировать или поднять пределы в рамках квоты партнёра |
| Таймаут чтения | `RestClientTimeoutException` | `WARN` с длительностью | `requests{outcome=UNKNOWN}` | Запрос мог быть обработан - не повторять вслепую неидемпотентные вызовы |
| Соединение отклонено | `RestClientConnectionException` | `WARN` | как выше | Сеть / DNS / партнёр недоступен |
| Эндпоинт токенов отказывает | `RestClientAuthenticationException` | `WARN`, никогда с учётными данными | `auth.token.refreshes` не растёт | Проверить регистрацию OAuth2 и секрет |
| Клиент ретрансляции без входящего токена | `RestClientAuthenticationException` | `WARN` | - | Вызов из задачи/listener-а: использовать машинный токен |
| Слушатель бросил исключение | нет | `WARN` раз на класс | `listener.failures` | Исправить слушатель |
| Старт падает со списком проблем | - | - | - | Исправить каждый пункт (список валидатора ниже) |

**Стартовый валидатор отвергает**: отсутствие `base-url` или его наличие в `defaults`; учётные данные по plain HTTP к
не-loopback хосту; бюджет повторов, в который не помещаются две попытки; `request-timeout` ≤ `connect-timeout`; time
limiter у `sync`, thread-pool bulkhead или ожидание bulkhead у `async`; `trust-all` без второго переключателя или под
продакшен-профилем; keystore без пароля; неизвестный `auth.type`; `bearer` с токеном и supplier одновременно;
ретрансляцию без `relay-enabled` или без resource server; breaker, который никогда не откроется; ожидание rate limiter
больше таймаута чтения; отсутствующий движок, класс исключения или модуль Jackson. Все сразу. **Предупреждения**: блок
пула на JDK-транспорте, `http2` на Apache, API-ключ в query, 4xx как ошибки breaker-а, повтор неидемпотентных методов
по умолчанию.

## Тестирование

Slice `@LudwigRestClientTest` (выше). Собственные наборы модуля: слияние и валидатор (`ClientPropertiesMergerTest`,
`ConfigurationValidatorTest`), политика повторов и `Retry-After`, редактирование, провайдеры аутентификации и
интеграционные тесты порядка декораторов, повторов, устойчивости, аутентификации, fallback, наблюдаемости,
перезагрузки и паритета реактивного режима, плюс `SpiPurityTest` и `TokenOwnershipTest`.

## Проектные решения

- **Один именованный клиент на зависимость**: общие таймауты и пулы превращают плохой день одного партнёра в отказ
  всего.
- **Устойчивость - свойство зависимости, а не метода**: нет аннотаций в стиле `@Retryable`; место вызова может только
  уменьшить предел попыток.
- **Нет service discovery, кэширования ответов, кластерного rate limiting и повтора потоковых тел** (тело `sync`
  буферизуется для повторной отправки; для больших загрузок отключайте повторы).
- **Интеграции опциональны**: observability (корреляция, trace id, предел URI, уровни логов на лету), web-core
  (отрисовка проблем), security (принципал в аудите, ретрансляция), hot-reload (перезагружаемые ключи). Без них
  клиенты работают; записи аудита несут `null` вместо корреляции или принципала.
