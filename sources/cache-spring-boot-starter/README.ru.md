# cache-spring-boot-starter

*[English](README.md) · **Русский***

Примитив кэширования платформы: именованные типизированные кэши, которые объявляет модуль-владелец и
настраивает под одним префиксом развёртывание. Локальный уровень Caffeine с объединением запросов по ключу и
ранним обновлением с jitter; опциональный общий уровень Redis с трёхчастным вытеснением и версионированными
пространствами ключей с проверкой формы; негативное кэширование, различающее *отсутствие* и *сбой*;
вытеснение после коммита; метрики по каждому кэшу, включая долю попаданий.

> **Каждый именованный кэш объявляет, что означает его TTL.** `purpose: security` - TTL это время, в течение
> которого отозванное право продолжает работать: потолок, без устаревших чтений. `purpose: performance` - TTL
> это время, в течение которого пользователь видит старое значение: минуты, допустим stale-while-revalidate.
> Выбор назначения - единственное решение, которое модуль не может проверить за вас.

| | |
|---|---|
| Роль | starter |
| Зависит от (в репозитории) | ничего (test: `test-support`) |
| Используется | стартерами `pat`, `security`, `user-settings` |
| Поверхности | нет (опционально Redis) |

```
ru.ludwigandreas.cache
├── api/      LudwigCache, LudwigCacheRegistry, CacheDefinition, CacheLoad, CacheLoader, CachePurpose, CacheTier, CacheSettings
├── core/     DefaultLudwigCache, CacheEntry, NoopLudwigCache, CacheRefreshExecutor
├── shared/   SharedCacheTier, ValueShape, RedisSharedCacheTier, ...
├── config/   CacheProperties, CacheSettingsResolver, CacheConfigurationValidator, автоконфигурации
├── metrics/  CacheMetrics, MicrometerCacheMetrics
├── tx/       AfterCommitEviction
├── error/    CacheConfigurationException, CacheLoadException, UnknownCacheException
└── test/     LudwigCacheTestSupport - настоящий кэш без Spring-контекста (в основном jar)
```

## Использование

```xml
<dependency>
    <groupId>ru.ludwigandreas</groupId>
    <artifactId>cache-spring-boot-starter</artifactId>
</dependency>
```

### Объявление кэша (автор модуля)

```java
public final class AuthorityCaches {

    public static final String NAME = "authorities";

    public static CacheDefinition<PrincipalRef, Authorities> definition() {
        return CacheDefinition.<PrincipalRef, Authorities>named(NAME, CachePurpose.SECURITY)
                .owner("security-spring-boot-starter")
                .defaultTtl(Duration.ofSeconds(60))
                .defaultMaximumSize(10_000)
                .valueType(Authorities.class)
                .keyRenderer(ref -> ref.type().name() + ':' + ref.subject())
                .build();
    }
}

@Bean
CacheDefinition<PrincipalRef, Authorities> ludwigAuthorityCacheDefinition() {
    return AuthorityCaches.definition();
}

@Bean
LudwigCache<PrincipalRef, Authorities> ludwigAuthorityCache(
        LudwigCacheRegistry registry, CacheDefinition<PrincipalRef, Authorities> definition) {
    return registry.cache(definition);
}
```

- **Тип значения и рендерер ключа обязательны даже для локального кэша**, чтобы перевод кэша на общий
  уровень был изменением YAML, а не кода.
- Рендерер ключа должен быть **тотальным и инъективным**: два ключа с одинаковой строкой отдали бы значение
  одного вызывающего другому. Рендерера по умолчанию нет (`toString` нестабилен между релизами).
- Объявление без блока `ludwig.cache.caches.<name>` работает на значениях по умолчанию; блок, не
  соответствующий ни одному объявлению, **валит старт** (случай опечатки).

### Чтение

```java
Optional<Authorities> authorities = cache.get(ref, key ->
        CacheLoad.present(resolver.resolve(key)));
```

Загрузчик сообщает, что произошло:

```java
public sealed interface CacheLoad<V> {
    record Present<V>(V value) implements CacheLoad<V> { }
    record Absent<V>()        implements CacheLoad<V> { }   // кэшируется ненадолго, если включено негативное кэширование
    record Failed<V>(Throwable cause) implements CacheLoad<V> { }   // никогда не кэшируется
}
```

Брошенное исключение - это `Failed`. `CacheLoad.ofOptional(...)` адаптирует репозиторий, возвращающий
`Optional`. Кэширование сбоя превратило бы двухсекундный сбой в отказ для невезучих ключей и подавило бы
пробу, которая заметила бы восстановление. Негативное кэширование **выключено по умолчанию**: ошибочное
«отсутствует» обычно замечает тот, кто только что создал строку.

### Вытеснение после коммита

```java
cache.evictAfterCommit(subject);    // один ключ
cache.evictAllAfterCommit();        // запись на уровне роли, тенанта или платформы
```

Вытеснение внутри транзакции позволяет другому потоку прочитать строку до коммита и снова положить
устаревшее значение; при откате оно зря выбрасывает правильное значение.

**TTL - страховка, согласованность обеспечивает вытеснение.** TTL обязателен для каждого кэша, чтобы
пропущенное вытеснение (потерянное событие, лежащий потребитель) исправлялось само. Истечение всегда
`expireAfterWrite`: истечение по доступу позволило бы активному вызывающему держать отозванное право вечно,
а горячим ключам - никогда не истекать.

### Назначения

| | `purpose: security` | `purpose: performance` |
|---|---|---|
| TTL по умолчанию | 30s | 5m |
| потолок TTL | `ludwig.cache.security-ttl-ceiling` (2m); выше - **старт падает** | нет |
| устаревшие чтения после TTL (`stale-grace`) | запрещены при старте | разрешены |
| кластерная аренда загрузки | запрещена (она отдаёт устаревшее) | разрешена |
| раннее обновление с jitter | разрешено (перезагрузка внутри TTL) | разрешено |

Развёртывание может переопределить назначение модуля `security` на `performance`; это разрешено и
логируется на `WARN`, потому что снимает потолок с кэша, чей автор назвал TTL окном отзыва.

### Уровни

```yaml
ludwig.cache.caches.<name>.tiers: [local]            # по умолчанию
ludwig.cache.caches.<name>.tiers: [local, shared]    # по выбору; также нужен ludwig.cache.tiers.shared.enabled
```

`local` присутствует всегда. Общий уровень оправдан для действительно дорогих загрузок (агрегаты, удалённые
запросы к партнёру, прогрев на холодном старте), а не для чтения по первичному ключу из и так общей БД. Нужны
два переключателя, чтобы блок кэша, скопированный с ноутбука в Helm chart, не начал молча ходить в Redis.

**Чтение и запись общего уровня - best-effort**: недоступный Redis учитывается, логируется раз в период и
обходится. Сбой сообщает только вытеснение.

**Вытеснение с общим уровнем** состоит из трёх частей:

1. вытеснить локально на этой реплике - всегда, независимо от п. 2;
2. удалить общую запись, с немедленными повторами (без backoff - это поток запроса) и метрикой при сбое.
   **Неудачное общее вытеснение оставляет устаревшее значение на всех репликах**;
3. опубликовать инвалидацию в Redis pub/sub, чтобы другие реплики сбросили локальные копии. Включено по
   умолчанию; при `shared.invalidation: false` TTL кэша должен быть ≤
   `tiers.shared.local-ttl-cap-without-invalidation`.

**Пространства ключей:** `ludwig:{app}:{namespace}:v{version}:{key}`, плюс `:__shape` (отпечаток типа
значения) и `:__lease:{key}`. `{app}` - это `spring.application.name` (обязательно для общего уровня). При
первом использовании кэш записывает `ValueShape.of(valueType)` через `SET NX`; при последующих стартах другая
форма под той же версией **валит старт** с указанием кэша и свойства для увеличения. Увеличивайте
`key-namespace.version` при каждом изменении формы кэшируемого значения. Недоступный при старте Redis
оставляет уровень *непроверенным*: он не читает и не пишет, пока проверка не пройдёт при использовании.

### Защита от лавины запросов

- **Объединение по ключу, всегда**: одновременные промахи по одному ключу выполняют загрузчик один раз.
- **Раннее обновление с jitter** (включено по умолчанию): после `threshold` × TTL запись становится
  кандидатом на обновление с вероятностью, растущей до 1 к истечению, поэтому реплики перезагружают в разные
  моменты. Работает на маленьком ограниченном пуле, отбрасывающем при переполнении; неудачное обновление
  сохраняет существующую запись.
- **Кластерная аренда загрузки** (по выбору, общий уровень, только `performance`, нужен `stale-grace`):
  `SET NX PX`; проигравший отдаёт устаревшее значение, а не блокируется. Это не `RunLock` из `job-core` -
  обращение к БД на каждый промах стоило бы дороже загрузки.

### Вытеснение по предикату

`evictByScan(Predicate<K>)` - O(n), что отражено в имени. Кэш должен явно разрешить его
(`requiresScanEviction(true)` в объявлении или `scan-eviction: true`), и оно **запрещено с общим уровнем**
(Redis потребовал бы блокирующий `KEYS` или вторичный индекс).

### Тестирование соавтора

`LudwigCacheTestSupport` строит настоящий кэш без Spring, поэтому модульные тесты отличают попадание от
промаха без мока кэша и без поднятия контекста.

## Конфигурация

```yaml
ludwig:
  cache:
    enabled: true                       # off регистрирует NoopLudwigCache для каждого кэша
    security-ttl-ceiling: 2m
    refresh:
      pool-size: 2
      queue-capacity: 1000              # ограничен, отбрасывает
    tiers:
      shared:
        enabled: false                  # Redis; первый из двух переключателей
        local-ttl-cap-without-invalidation: 60s
    caches:
      authorities:
        enabled: true
        purpose: security
        ttl: 30s
        maximum-size: 10000
        tiers: [local, shared]
        key-namespace: { name: authorities, version: 3 }
        negative: { enabled: true, ttl: 5s }
        shared: { invalidation: true, eviction-retries: 2 }
      settings:
        purpose: performance
        ttl: 5m
        maximum-size: 50000
        tiers: [local]
        scan-eviction: true
        key-namespace: { name: settings, version: 7 }
        stampede:
          early-refresh: { enabled: true, threshold: 0.75 }
          stale-grace: 30s
          lease: { enabled: false, ttl: 5s }
```

Незаданные поля берутся из объявления модуля-владельца, затем из значений назначения. Кэши, объявленные в
платформе: `authorities` и `pat-introspection` (security-стартер, `security`), `pat-verification`
(pat-стартер, `security`), `settings` (user-settings, `performance`).

**Запрещено при старте** (все проблемы сообщаются разом):

| Запрещено | Потому что |
|---|---|
| TTL `security` выше потолка | отозванная роль продолжала бы работать, и никто бы об этом не сообщил |
| `security` с ненулевым `stale-grace` | устаревшие чтения после TTL ломают окно отзыва |
| `scan-eviction` с общим уровнем | нужен `KEYS` или вторичный индекс |
| `shared` в `tiers` без `tiers.shared.enabled` | второй переключатель |
| общий уровень без `spring.application.name` | два сервиса делили бы пространство ключей |
| `shared.invalidation: false` с TTL выше предела | этот TTL становится единственной границей устаревших копий других реплик |
| аренда без общего уровня / с `security` / без `stale-grace` / с TTL ≥ TTL кэша | аренда бессмысленна или вредна |
| негативный TTL длиннее TTL значения | отсутствие - ответ, который скорее всего перестанет быть верным |
| порог раннего обновления вне `(0, 1)` | вырожденное окно |
| блок `caches.<name>`, который ни один модуль не объявил | он связался бы и игнорировался |

## Развёртывание

Локальным кэшам ничего не нужно. Для общего уровня: Redis, доступный через `spring.data.redis.*`, разрешённый
pub/sub (канал инвалидации), заданный `spring.application.name` и оба переключателя. Рассчитывайте память
Redis как `maximum-size` × размер записи × число общих кэшей. Учётные данные Redis - из хранилища секретов.

**Rolling deploy**, меняющий тип кэшируемого значения, должен увеличить `key-namespace.version`, иначе новые
поды упадут при старте с расхождением формы (старые продолжат работать; значение неверной формы никто не
отдаст).

## Наблюдаемость

Статистика Caffeine (`recordStats()`) включена для всех кэшей и привязана к каждому через
`CaffeineCacheMetrics` (`cache.gets`, `cache.puts`, `cache.evictions`, `cache.size`, время загрузки). Каждая
метрика ниже несёт теги `cache` и `purpose`, чтобы дашборды разделяли кэши безопасности и производительности.

| Метрика | Тип | Доп. теги | Смысл |
|---|---|---|---|
| `ludwig.cache.hits` | counter | `tier` | Попадания по уровням (попадание в общий стоит round trip) |
| `ludwig.cache.misses` | counter | | Промахи |
| `ludwig.cache.loads` | timer | `outcome` (`present`, `absent`, `failed`) | Результаты загрузчика и длительность загрузки |
| `ludwig.cache.negative.hits` | counter | | «Отсутствует», отданное из негативного кэша |
| `ludwig.cache.early.refreshes` | counter | | Запущенные ранние обновления |
| `ludwig.cache.lease.lost` | counter | `served` | Аренда проиграна; отдано ли устаревшее |
| `ludwig.cache.shared.eviction.failures` | counter | | **Все реплики теперь отдают устаревшее значение. Алерт на любое** |
| `ludwig.cache.shared.errors` | counter | `operation` | Общий уровень деградировал до локального |
| `ludwig.cache.shared.shape.mismatches` | counter | | Версия ключа не увеличена после смены формы |
| `ludwig.cache.shared.available` | gauge | | `0`, пока уровень недоступен или не проверен |
| `ludwig.cache.maximum.size` | gauge | | Заданный потолок; сравнивайте с `cache.size` |

**Логи.** `INFO` по каждому кэшу при старте (TTL, размер, уровни) и итог валидации. `WARN` при откате общего
уровня на локальный, нечитаемой общей записи, непроверенном пространстве при старте и переопределении кэша
безопасности на производительность. `ERROR` при неудачном общем вытеснении и расхождении формы.

**Рекомендуемые алерты:**

| Условие | Почему |
|---|---|
| `increase(ludwig_cache_shared_eviction_failures_total[5m]) > 0` | Устаревшие значения на всех репликах до TTL |
| `ludwig_cache_shared_available == 0` дольше 5 мин | Работа только локально; растёт нагрузка на хранилище |
| `cache_size / ludwig_cache_maximum_size ≈ 1` устойчиво | Кэш мал |
| Резкое падение доли попаданий кэша | Шторм вытеснений или смена рендерера ключа |

## Аудит

Не применяется: кэширование не меняет бизнес-состояние. Вытеснения запускаются аудируемыми записями в
модулях-владельцах.

## Runbook

| Симптом | Вероятная причина | Что делать |
|---|---|---|
| Старт падает: `CacheConfigurationException` со списком проблем | Одна из запрещённых комбинаций или опечатка в `caches.<name>` | Исправить каждый пункт; сообщение называет кэш и свойство |
| Старт падает: версия пространства ключей уже записана с другой формой | Тип значения изменился, версия не увеличена | Увеличить `ludwig.cache.caches.<name>.key-namespace.version` |
| Отозванная роль всё ещё работает | TTL кэша безопасности; пропущенное вытеснение | Подождать ≤ TTL; принудительно: перезапустить поды (локальный уровень) и удалить ключи `ludwig:<app>:authorities:*`, если уровень общий |
| Новый объект сообщается как «не найден» | Негативный кэш | Подождать негативный TTL; вытеснять ключ после коммита при создании |
| `ludwig_cache_shared_eviction_failures_total` > 0 | Redis был недоступен при вытеснении | Восстановить Redis; устаревшие записи исправятся к TTL, либо удалить затронутые ключи вручную |
| Высокая задержка промахов по одному ключу | Медленный загрузчик; объединение заставляет вызывающих ждать одну загрузку | Починить хранилище; рассмотреть раннее обновление или более длинный TTL (только кэши производительности) |

## Тестирование

- Модульные (без Docker): загрузка, вытеснение (включая **отсутствие вытеснения при откате**), объединение
  (16 одновременных промахов → одна загрузка), валидатор, разрешение настроек, автоконфигурация, `ValueShape`.
- `SharedTierIntegrationTest` (Docker, Redis с закреплённым digest из `test-support`): путь сбоя
  двухуровневого вытеснения, версионирование ключей и расхождение формы при старте.
- `ModuleIndependenceTest`: нет зависимости от других модулей платформы; публичный API не раскрывает типы Redis.

## Проектные решения

- **Ноль зависимостей в репозитории.** `security-spring-boot-starter` зависит от этого модуля; любая
  зависимость здесь от audit-core, web-core или security создала бы цикл. Обеспечивается `ModuleIndependenceTest`.
- **Один примитив, с принуждением.** Группа `CACHING` в `architecture-rules`: `caching:no-private-caffeine-cache`
  (только этот модуль зависит от билдера Caffeine; одно исключение - `EnrichmentCache` экспорта на время
  запуска) и `caching:no-second-cache-spi`.
- **Здесь не кэши:** `EnrichmentCache` экспорта (ограничен одним запуском отчёта - общий кэш смешал бы данные
  разных моментов времени), `CachedToken` rest-client (семантика обновления до истечения), `CachedBodyRequest`
  idempotency (обёртка запроса).
- **Явная версия ключа, проверяемая отпечатком**, а не версия, выводимая из отпечатка: косметическое
  переименование не должно вызывать холодный старт всего парка без чьего-либо решения.
- **Нет no-op кэшей по модулям.** Выключатель - `enabled: false`.
