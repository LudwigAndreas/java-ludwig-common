# observability-spring-boot-starter

*[English](README.md) · **Русский***

Базовый уровень наблюдаемости каждого сервиса: распределённая трассировка через OTLP с принудительным
сэмплированием отдельного запроса, единый correlation id через HTTP, Kafka и MDC, структурированные
JSON-логи с trace, span, correlation и идентичностью сборки, RED-метрики с ограниченной кардинальностью URI
и агрегируемыми SLO-гистограммами, раздельные пробы liveness/readiness с плавной остановкой,
`GET /server/info` для пользовательских интерфейсов и изменение уровней логирования на лету. Добавление
зависимости настраивает сервис так же, как все остальные.

| | |
|---|---|
| Роль | starter |
| Зависит от (в репозитории) | [`audit-core`](../audit-core) (ради `Redaction.MASK`); опционально: [`web-core`](../web-core-spring-boot-starter), [`hot-reload`](../hot-reload-spring-boot-starter) |
| Используется | стартерами `export`, `file-action`, `grpc-client`, `grpc-server`, `reconciliation`, `rest-client`, `user-settings`; обоими сервисами |
| Поверхности | REST `GET /server/info`; эндпоинты actuator; экспорт OTLP; логи в stdout |

```text
ru.ludwigandreas.observability
├── config/        автоконфигурация, свойства, значения окружения по умолчанию
├── core/          ServiceIdentity, BuildIdentity
├── correlation/   correlation id, хранение, валидация
├── tracing/       сэмплер, фильтрация экспорта span-ов, доступ к контексту трассы
├── web/           сервлетные фильтры, исходящая HTTP-пропагация, /server/info
├── kafka/         корреляция продюсера и потребителя
├── logging/       JSON-энкодер, текстовый шаблон, событие идентичности при старте, уровни на лету
└── metrics/       RED meter-фильтры, ограничение кардинальности
```

## Использование

```xml
<dependency>
    <groupId>ru.ludwigandreas</groupId>
    <artifactId>observability-spring-boot-starter</artifactId>
</dependency>
```

Укажите коллектор:

```yaml
spring:
  application:
    name: product-catalog

management:
  otlp:
    tracing:
      endpoint: http://otel-collector:4318/v1/traces
  tracing:
    sampling:
      probability: 0.1
```

Тогда запрос даёт одну JSON-строку лога:

```json
{"@timestamp":"2026-09-16T09:12:13.481Z","log.level":"INFO","log.logger":"com.example.OrderService",
 "process.thread.name":"http-nio-8080-exec-3","message":"order 4711 accepted",
 "trace.id":"0af7651916cd43dd8448eb211c80319c","span.id":"b7ad6b7169203331",
 "correlation.id":"0af7651916cd43dd8448eb211c80319c","service.name":"product-catalog",
 "service.version":"1.4.2","service.environment":"prod","service.node.name":"catalog-7d9f-xk2",
 "service.commit.id":"c1fc5b8","labels":{"tenant":"acme"}}
```

заголовки ответа:

```console
$ curl -i -H 'X-Correlation-Id: order-4711' localhost:8080/api/v1/orders/4711
HTTP/1.1 200
X-Correlation-Id: order-4711
X-Trace-Id: 0af7651916cd43dd8448eb211c80319c
```

и метрики с тегами идентичности:

```
http_server_requests_seconds_bucket{service="product-catalog",environment="prod",version="1.4.2",
  uri="/api/v1/orders/{id}",method="GET",status="200",outcome="SUCCESS",le="0.1"} 412
```

### Что настраивается

| Область | Настройка |
|---|---|
| Трассировка | Экспорт OTLP, W3C-пропагация, parent-based ratio sampling |
| Принудительное сэмплирование | `X-Ludwig-Force-Trace: true` трассирует один запрос (по выбору) |
| Гигиена span-ов | Серверные span-ы `/actuator/**` отбрасываются при экспорте |
| Корреляция | Один id через входящий/исходящий HTTP, входящую/исходящую Kafka, MDC, тег span-а, заголовок ответа |
| Логи | JSON в stdout (читаемый текст в профиле `local`), наборы полей ECS/OTel/flat, коммит в каждой строке, маскирование, обрезка |
| Метрики | Общие теги идентичности, ограничение кардинальности URI, бакеты SLO-гистограмм, пути проб исключены |
| Аннотации | Зарегистрированы аспекты `@Timed`, `@Counted`, `@Observed` |
| Пробы | Раздельные liveness/readiness, плавная остановка (30s) |
| Уровни логов | `logging.level.*` применяются из отслеживаемого файла или секрета Vault, откатываются при удалении |

### Correlation id и trace id

- **Trace id**: одно распределённое выполнение; существует, только если запрос засэмплирован.
- **Correlation id**: одна единица бизнес-работы; есть у 100% запросов, переживает повторы, может
  передаваться вызывающим. Если запрос пришёл без него, trace id принимается как correlation id, поэтому
  любое значение работает в любом инструменте.

| Участок | Механизм |
|---|---|
| Входящий HTTP | `X-Correlation-Id`, иначе `X-Request-Id`, иначе trace id, иначе генерируется |
| Исходящий HTTP | Заголовок в каждом `RestTemplate`, построенном автоконфигурированным билдером |
| Исходящая Kafka | Заголовок записи через управляемую Spring фабрику продюсеров |
| Входящая Kafka | Заголовок записи в MDC на время работы listener-а |
| Логи | MDC `correlationId` → отдельное JSON-поле |
| Трассы | Тег span-а `correlation.id` |
| Ответ | `X-Correlation-Id` и `X-Trace-Id` |

Входящие значения недоверенные: длиннее `max-length` или вне `[A-Za-z0-9_.:-]` → **заменяются** (защита от
response splitting и раздувания логов). Код вне этих точек читает `CorrelationContext.currentId()`. Для
аудиторского следа опубликуйте `CorrelationProvider` на основе `CorrelationContext` (см.
[audit-core](../audit-core/README.ru.md#корреляция)).

### Логи

**JSON везде, кроме профиля `local`**, где по умолчанию читаемый текст. Имя профиля фиксировано;
`ludwig.observability.logging.json.enabled` переопределяет в любую сторону. Текст сохраняет все метаданные:

```text
2026-09-16T12:12:13.481+03:00  INFO [product-catalog/1.4.2@c1fc5b8 env=local instance=dev-laptop] [order-4711,0af7651916cd43dd8448eb211c80319c,b7ad6b7169203331] [http-nio-8080-exec-3] com.example.OrderService : order 4711 accepted
```

`[имя/версия@коммит env= instance=]`, затем `[correlation,trace,span]`; неразрешённые части опускаются
(незасэмплированный запрос выглядит как `[order-4711,,]`). Шаблон, заданный через `logging.pattern.*`, или
собственный энкодер в `logback-spring.xml` не трогаются.

| `field-set` | Имена | Поле коммита | Для |
|---|---|---|---|
| `ecs` (по умолчанию) | `@timestamp`, `log.level`, `trace.id` | `service.commit.id` | Elasticsearch, OpenSearch |
| `otel` | `Timestamp`, `SeverityText`, `TraceId` | `ServiceCommitId` | OpenTelemetry collector |
| `flat` | `timestamp`, `level`, `trace_id` | `commit_id` | Loki, стеки без схемы |

- Формат устанавливается сразу после инициализации логирования Spring Boot, поэтому вывод старта и Liquibase
  уже в нём. В режиме JSON `spring.main.banner-mode` по умолчанию `off`.
- Записи **MDC** вкладываются в `labels` (разворачивание позволило бы ключу MDC перезаписать структурное
  поле и сломать общие шаблоны индексов). Ограничение - `mdc-include-keys`. Значения, чей ключ содержит
  `password`, `secret`, `token`, `credential`, `authorization`, `apikey` или `api-key`, маскируются - это
  страховка, а не мера защиты.
- **Key-value SLF4J** становятся полями верхнего уровня, маскируются по тем же правилам:
  `log.atInfo().addKeyValue("orderId", id).log("order accepted");`
- Сообщения обрезаются по `max-message-length`.
- Логи идут **только в stdout**; без файлов и без экспорта логов по OTLP.

### Идентичность сборки

Каждое событие лога несёт сокращённый коммит. Полное происхождение логируется один раз при старте:

```text
Application identity: service=product-catalog version=1.4.2 environment=prod instance=catalog-7d9f-xk2 commit=c1fc5b81ecfa20e1b3418002a5ace90473d6734c branch=master built=2026-09-16T00:00:00Z ci-build=4711 tree=clean
```

Значения берутся из `git.properties` и `META-INF/build-info.properties`, которые упаковывает
[`ludwig-service-parent`](../../build/ludwig-service-parent); git во время выполнения не вызывается. Время
сборки обрезано до дня (воспроизводимые digest-и образов). Неразрешимые значения опускаются, никогда не
`unknown`. `BuildIdentity` никогда не бывает тегом метрики. `/actuator/info` показывает разделы `git` и
`build`; сузьте `management.endpoints.web.exposure.include`, если ветку и коммит раскрывать нельзя.

### `GET /server/info`

На порту приложения сервлетного сервиса:

```json
{"service": "product-catalog", "version": "1.4.2", "environment": "prod", "commit": "c1fc5b8", "built": "2026-09-16"}
```

Фиксированный путь в каждом сервисе - для интерфейсов, показывающих, какой сервис и какой версии ответил.
Пять полей - allow-list (без ветки, флага dirty, номера CI, полного коммита, namespace и экземпляра).
Неразрешённые значения опускаются; при полном отсутствии идентичности ответ `200 {}`. Стартер не добавляет
правил безопасности: за security-стартером укажите путь явно:

```yaml
ludwig:
  security:
    # Этот список заменяет список по умолчанию - перечислите всё, что ещё нужно.
    public-paths: [/actuator/health, /actuator/health/**, /actuator/info, /server/info]
```

### Принудительная трассировка

```console
$ curl -H 'X-Ludwig-Force-Trace: true' https://catalog.internal/api/v1/orders/4711
```

Выключено по умолчанию (`tracing.sampling.trusted-force-header`): любой, кто может выставить заголовок, может
заставить сервис трассировать весь трафик в бэкенд с оплатой за span. Включайте, только если граница сети
вырезает или авторизует заголовок. Действует только в корне трассы; входящее решение о сэмплировании
соблюдается.

### Уровни логирования на лету

С [`hot-reload-spring-boot-starter`](../hot-reload-spring-boot-starter):

```yaml
ludwig:
  hotreload:
    files:
      - path: /etc/config/logging.yml
```

```yaml
# /etc/config/logging.yml
logging:
  level:
    com.example.payments: DEBUG
```

Удаление ключа **возвращает** логгер к уровню на момент старта, поэтому забытый DEBUG не остаётся до
следующего перезапуска.

### Пробы

```console
$ curl localhost:8080/actuator/health/liveness    # JVM зависла? перезапустить
$ curl localhost:8080/actuator/health/readiness   # может обслуживать? направлять трафик
```

Сервис, потерявший БД, не готов, но жив. Каждый сервис сам решает, что входит в группу readiness
(`management.endpoint.health.group.readiness.include`); держите зависимости вне liveness.

Экспонируются по умолчанию `health,info,metrics,prometheus,loggers`. `env`, `configprops`, `heapdump` и
`threaddump` исключены (раскрывают учётные данные и память).

## Конфигурация

Все значения ниже - значения по умолчанию с наименьшим приоритетом; всё, что задано в `application.yml`,
окружении или системных свойствах, сильнее. Где есть свойства Spring Boot, используются они
(`management.tracing.sampling.probability`, `management.otlp.tracing.endpoint`, `management.endpoints.*`).

| Свойство | По умолчанию | Смысл |
|---|---|---|
| `ludwig.observability.enabled` | `true` | Главный выключатель |
| `ludwig.observability.service.name` | `${spring.application.name}` | На метриках, span-ах и логах |
| `ludwig.observability.service.version` | манифест jar / `build-info` | |
| `ludwig.observability.service.environment` | первый активный профиль | **Задавайте явно в каждом развёртывании** |
| `ludwig.observability.service.namespace` | не задан | Необязательная группировка |
| `ludwig.observability.service.instance` | `$HOSTNAME` | Имя пода в Kubernetes |
| `ludwig.observability.tracing.enabled` | `true` | |
| `ludwig.observability.tracing.excluded-paths` | `/actuator/**` | Отбрасываются при экспорте |
| `ludwig.observability.tracing.sampling.force-header` | `X-Ludwig-Force-Trace` | |
| `ludwig.observability.tracing.sampling.trusted-force-header` | `false` | Учитывать заголовок принудительной трассировки |
| `ludwig.observability.correlation.enabled` | `true` | |
| `ludwig.observability.correlation.header-name` | `X-Correlation-Id` | Должен совпадать с `ludwig.messaging.correlation-header` |
| `ludwig.observability.correlation.additional-inbound-headers` | `[X-Request-Id]` | Проверяются по порядку |
| `ludwig.observability.correlation.max-length` | `128` | Должен быть положительным |
| `ludwig.observability.correlation.allowed-pattern` | `[A-Za-z0-9_.:-]+` | Несовпадающие значения заменяются |
| `ludwig.observability.correlation.kafka.enabled` / `.header-name` | `true` / `X-Correlation-Id` | Пропагация через Kafka |
| `ludwig.observability.correlation.mdc-key` | `correlationId` | |
| `ludwig.observability.correlation.trace-id-response-header` | `X-Trace-Id` | |
| `ludwig.observability.build.*` | из `git.properties` / `build-info.properties` | `commit-id`, `abbreviated-commit-id`, `branch`, `timestamp`, `ci-build-number`, `dirty` |
| `ludwig.observability.server.info.enabled` | `true` | Обслуживать `GET /server/info` |
| `ludwig.observability.logging.json.enabled` | `true`; `false` в `local` | |
| `ludwig.observability.logging.json.field-set` | `ecs` | `ecs`, `otel`, `flat` |
| `ludwig.observability.logging.json.max-message-length` | `16384` | `0` = без обрезки |
| `ludwig.observability.logging.json.mdc-include-keys` / `mdc-exclude-keys` | пусто (все) / пусто | Allow-/deny-list ключей MDC |
| `ludwig.observability.logging.json.masked-keys` | `password, secret, token, credential, authorization, apikey, api-key` | Фрагменты ключей, чьи значения маскируются |
| `ludwig.observability.logging.json.max-stack-trace-length` | `12288` | |
| `ludwig.observability.logging.json.static-fields` | пусто | Дополнительные постоянные поля в каждом событии |
| `ludwig.observability.logging.levels.hot-reload-enabled` | `true` | Применять `logging.level.*` из источников hot-reload |
| `ludwig.observability.logging.levels.revert-on-removal` | `true` | |
| `ludwig.observability.metrics.enabled` | `true` | |
| `ludwig.observability.metrics.aspects.enabled` | `true` | Аспекты `@Timed`/`@Counted`/`@Observed` |
| `ludwig.observability.metrics.common-tags` | `true` | Теги идентичности на каждой метрике |
| `ludwig.observability.metrics.http.max-uri-tags` | `100` | Различных значений `uri` до `OTHER` |
| `ludwig.observability.metrics.http.slo` | `50ms, 100ms, 200ms, 500ms, 1s, 2s, 5s, 10s` | Бакеты гистограммы = обещанные задержки |
| `spring.main.banner-mode` | `off` в режиме JSON | |

## Развёртывание

- **Коллектор**: OTLP HTTP эндпоинт для трасс (`management.otlp.tracing.endpoint`).
- **Prometheus**: scrape `/actuator/prometheus` на management-порту.
- **Доставка логов**: агент узла собирает stdout. При строгом шаблоне индекса заранее отобразите поле коммита
  (`service.commit.id` / `ServiceCommitId` / `commit_id`) как keyword.
- **Пробы Kubernetes**: `livenessProbe` → `/actuator/health/liveness`, `readinessProbe` →
  `/actuator/health/readiness`; `terminationGracePeriodSeconds` больше 30s плавной остановки плюс таймауты
  дренажа воркеров.
- **Граница сети**: вырезайте `X-Ludwig-Force-Trace` у недоверенных вызывающих, если включён
  `trusted-force-header`.

Actuator, мост трассировки, OTLP-экспортёр и реестр Prometheus - compile-зависимости (развёртывание с другим
экспортом исключает их); Spring MVC, Spring Kafka, web-core и hot-reload опциональны.

## Наблюдаемость

Модуль определяет наблюдаемость платформы; его собственные сигналы:

| Сигнал | Смысл |
|---|---|
| `INFO Application identity: ...` | Один раз при старте |
| `WARN Reached the limit of <n> distinct 'uri' tag values ...` | Достигнут предел кардинальности; несовпавшие пути (часто сканеры) теперь пишутся как `OTHER` |
| `WARN Structured JSON logging is enabled but no pattern-encoded appender was found` | Собственная конфигурация logback обходит JSON-энкодер |
| `INFO/WARN Hot-reloaded log level ...` | Изменения уровней на лету, откаты, неверные уровни |

**Рекомендуемые алерты платформы** (по сервису, с тегами этого модуля):

| Условие | Почему |
|---|---|
| `sum(rate(http_server_requests_seconds_count{outcome="SERVER_ERROR"}[5m])) / sum(rate(http_server_requests_seconds_count[5m])) > 0.01` | Доля ошибок |
| `histogram_quantile(0.99, sum by (le, uri) (rate(http_server_requests_seconds_bucket[5m])))` выше SLO | Задержка |
| проба readiness падает на всех репликах | Сервис недоступен |
| `jvm_memory_used_bytes{area="heap"} / jvm_memory_max_bytes` > 0.9 дольше 10 мин | Давление на память |
| `hikaricp_connections_pending` > 0 дольше 5 мин | Исчерпание пула соединений |

**Известные пробелы:** нет пропагации корреляции для `WebClient` (используйте `ExchangeFilterFunction`,
читающий `CorrelationContext`) и для Kafka-продюсеров, построенных вне управляемой Spring фабрики.

## Аудит

Не применяется: изменений состояния нет. Изменения уровней логирования на лету аудирует hot-reload
(категория `config`).

## Runbook

| Симптом | Вероятная причина | Что делать |
|---|---|---|
| Нет трасс в бэкенде | Эндпоинт не задан/недоступен или вероятность 0 | Проверить `management.otlp.tracing.endpoint`, сетевой доступ к коллектору, `management.tracing.sampling.probability` |
| Нужна трасса одного падающего запроса | Не засэмплирован | Повторить с `X-Ludwig-Force-Trace: true` (если включено) или искать логи по correlation id |
| Логи не разбираются агрегатором | Текстовый формат вне `local` или не тот набор полей | Проверить `ludwig.observability.logging.json.enabled` и `field-set` |
| Доминирует `uri="OTHER"` | Несовпавшие пути (сканеры, 404) или слишком много шаблонов | Для сканеров ожидаемо; поднимайте `max-uri-tags`, только если реальных шаблонов больше |
| Под перезапускается по кругу при недоступной БД | Liveness включает зависимость | Убрать её из группы liveness; перенести в readiness |
| 502 во время деплоев | Grace period короче остановки | Поднять `terminationGracePeriodSeconds`; проверить `server.shutdown=graceful` |
| Нужен DEBUG на работающем поде | - | Добавить `logging.level.<pkg>: DEBUG` в отслеживаемый файл; удалить строку для отката |
| Нет correlation id в логах Kafka-потребителя | Фабрика построена не билдером messaging | Использовать `ListenerContainerFactoryBuilder` (он встраивает interceptor корреляции) |

## Тестирование

Модульные и контекстные тесты валидации и пропагации корреляции (HTTP, Kafka), JSON-энкодеров по наборам
полей, текстового шаблона, маскирования, обрезки, ограничения кардинальности, сэмплера принудительной
трассировки, перезагрузки/отката уровней и allow-list `/server/info`. Сервисы получают поведение через
зависимость; их `ApiDocumentIT` покрывает `/server/info` в OpenAPI-документе.

## Проектные решения

- **Один заголовок корреляции, одна форма логов, одна идентичность** на весь парк, поэтому сигналы связываются
  между сервисами.
- **Гистограммы, а не клиентские перцентили**: p99 отдельных реплик нельзя агрегировать, и они занижены.
- **Идентичность - общими тегами**, а не на усмотрение scraper-а: переживает федерацию и несёт версию.
- **Без файлов логов**: контейнеры пишут в stdout; агент узла доставляет.
- **Текст только в `local`**: JSON, который нужно включать, сервисы забывают включить.
