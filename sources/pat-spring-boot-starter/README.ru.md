# pat-spring-boot-starter

*[English](README.md) · **Русский***

Выпускающая половина персональных токенов доступа (PAT) платформы для сервиса-провайдера идентичности: таблица
`ludwig_pat`, API управления, обмен токенов RFC 8693, интроспекция RFC 7662, аудит, метрики, i18n и problem
details. **Проверяющая** половина - в [`security-spring-boot-starter`](../security-spring-boot-starter), в каждом
сервисе. Формат - в [`pat-core`](../pat-core).

| | |
|---|---|
| Роль | starter (только провайдер идентичности) |
| Зависит от (в репозитории) | [`audit-core`](../audit-core), [`cache`](../cache-spring-boot-starter), [`db-core`](../db-core), [`pat-core`](../pat-core), [`security`](../security-spring-boot-starter), [`web-core`](../web-core-spring-boot-starter) |
| Используется | сервисом-провайдером идентичности (не в этом репозитории) |
| Поверхности | REST: `POST /oauth2/token`, `POST /introspect`, `/api/v1/personal-access-tokens` |

```
  CI / curl / notebook
        │  Authorization: Bearer lpat_<keyId>_<secret>_<crc>     ← один статический заголовок
        ▼
  ГРАНИЦА (Envoy / шлюз, не в этом репозитории)
        │  префикс "lpat_"? → кэш[(digest, audience)] попадание → повторно использовать assertion
        │                                               промах → обмен, кэш на expires_in - skew
        ▼  POST /oauth2/token
  провайдер идентичности (этот стартер)
        разбор → контрольная сумма → rate limit → чтение по keyId → сравнение дайджеста за пост. время
        → не отозван / не истёк → источник разрешён → audience разрешена
        → выпуск JWT: sub=<владелец>, ludwig_pat={id, scopes}, 5m
        ▼  короткоживущий assertion с audience
  ЛЮБОЙ сервис (security-стартер)
        права владельца = AuthorityLookup.lookup(sub)  ← заново
        эффективные     = владелец ∩ ludwig_pat.scopes
```

Клиент отправляет один статический заголовок и никогда не выполняет обмен сам.

## Использование

```xml
<dependency>
    <groupId>ru.ludwigandreas</groupId>
    <artifactId>pat-spring-boot-starter</artifactId>
</dependency>
```

```yaml
ludwig:
  pat:
    exchange:
      issuer: https://idp.internal            # обязательно - без него старт падает
      audiences: [deploy-service, billing-service]
  security:
    public-paths:
      - /actuator/health
      - /oauth2/token                          # обязательно
      - /introspect                            # если сервисы аутентифицируют токены напрямую
```

Плюс бин `RSAKey` для minter-а по умолчанию или бин `PatAssertionMinter`, делегирующий существующему ключу подписи
провайдера (предпочтительно: один ключ для ротации, один JWKS).

### API управления (`ludwig.pat.web.base-path`, по умолчанию `/api/v1/personal-access-tokens`)

| Метод | Путь | Действие |
|---|---|---|
| `POST` | `/` | Выпустить токен (секрет показывается **один раз**) |
| `GET` | `/` | Список токенов вызывающего |
| `GET` | `/{id}` | Один токен (без секрета и дайджеста) |
| `POST` | `/{id}/rotations` | Ротация: новый секрет; старый работает ещё `rotation-overlap` |
| `DELETE` | `/{id}` | Отзыв |

Права: `pat:manage` - управлять своими токенами; `pat:manage:any` - действовать за другого (выдавайте скупо: так
учётные данные переживают своего создателя). Запрос, аутентифицированный **через PAT**, отклоняется на каждом
эндпоинте управления (`PatCredentialGuard`): токен не может выпускать, ротировать или отзывать токены.

Выпуск отклоняет отсутствие срока, срок больше `max-lifetime`, пустые scope и пустые audience - каждый со своим
локализованным сообщением.

### Обмен (`POST /oauth2/token`)

```
grant_type=urn:ietf:params:oauth:grant-type:token-exchange
&subject_token=<значение lpat_>
&subject_token_type=urn:ludwig:params:oauth:token-type:pat
&resource=<сервис назначения>
```

Без аутентификации по замыслу (токен - субъект обмена). Каждый отказ возвращает одинаковый ответ; причина - только
в метриках и аудите.

### Интроспекция (`POST /introspect`)

Для платформ, где граница не умеет выполнять обмен (например, сессионный шлюз, который делает только cookie → JWT):
**сервис** спрашивает о полученном токене.

```
POST /introspect
token=lpat_...&resource=deploy-service     ← собственная audience вызывающего
```

```json
{ "active": true, "sub": "alice", "scope": "orders:read deploy:write",
  "aud": ["deploy-service"], "patId": "pat-42", "exp": "2026-09-01T00:00:00Z" }
```

и `{ "active": false }` с `200` для любой ошибки (RFC 7662). Использует проверку обмена без изменений (разбор,
контрольная сумма, чтение по ключу, сравнение за постоянное время, отзыв, срок, allowlist CIDR, перекрытие
ротации, кэш с назначением security). Ключ подписи не нужен. Потребляющая сторона включается
`ludwig.security.pat.filter.enabled=true` в каждом сервисе; когда граница начнёт поддерживать обмен, выключите это и
`ludwig.pat.introspection.enabled=false` здесь.

`/introspect` должен быть в `public-paths`: сервис, аутентифицирующий токен, вызывает его без собственных учётных
данных. Он раскрывает меньше публичного обмена; обоим нужен полный секрет. Открывайте его только внутри mesh.

### Что должна делать граница (не проверяется из этого репозитория)

1. **Обмен на каждую audience назначения и кэш по `(дайджест секрета, audience)`** на `expires_in` минус запас.
   Ключ только по дайджесту отдал бы assertion одного сервиса другому. Без кэша каждый запрос становится запросом к
   провайдеру.
2. **Отдавать неудачный обмен клиенту как `401`** с `WWW-Authenticate: Bearer error="invalid_token"` и телом
   `PatProblemTypes` (`manageCredentialsAt`). **Не раскрывать причину.**
3. **Вырезать учётные данные `lpat_`** перед проксированием; сервисы получают только assertion.

### Окно отзыва

Security-стартер при старте каждого сервиса вычисляет два окна и не стартует, если любое превышает
`ludwig.security.pat.max-revocation-window` (15m):

```
запрос/ответ:          время жизни assertion + время кэша границы + TTL кэша прав   (5m + 5m + 60s = 11m)
долгие соединения:     интервал перепроверки + TTL кэша прав                         (1m + 60s = 2m)
```

Чтобы сократить, уменьшайте `ludwig.pat.exchange.assertion-lifetime` здесь **и** кэш границы вместе, а
`ludwig.security.pat.assertion-lifetime` / `edge-cache-lifetime` в сервисах держите равными им.

## Конфигурация

| Свойство | По умолчанию | Смысл |
|---|---|---|
| `ludwig.pat.enabled` | `true` | Главный выключатель |
| `ludwig.pat.max-lifetime` | `90d` | Наибольший срок при выпуске |
| `ludwig.pat.allow-non-expiring` | `false` | Разрешить токены без срока |
| `ludwig.pat.rotation-overlap` | `24h` | Сколько старый секрет принимается после ротации |
| `ludwig.pat.retention` | `365d` | Сколько хранятся терминальные записи до удаления |
| `ludwig.pat.last-used-debounce` | `5m` | Минимальный интервал между записями `last_used_at` |
| `ludwig.pat.inactivity-expiry` | `180d` | Неиспользуемые токены отзываются после этого (`0` отключает) |
| `ludwig.pat.dormancy-threshold` | `7d` | Перерыв, после которого использование аудируется как `pat.dormant-wake` |
| `ludwig.pat.sweep-batch-size` | `500` | Строк в пачке обслуживания |
| `ludwig.pat.exchange.enabled` | `true` | Монтировать обмен |
| `ludwig.pat.exchange.path` | `/oauth2/token` | |
| `ludwig.pat.exchange.issuer` | - | **Обязательно**; `iss` выпускаемых assertion |
| `ludwig.pat.exchange.audiences` | `[]` | Audience, для которых выпускаются assertion (пусто - предупреждение) |
| `ludwig.pat.exchange.assertion-lifetime` | `5m` | Время жизни assertion |
| `ludwig.pat.rate-limit.enabled` | `true` | |
| `ludwig.pat.rate-limit.per-key-failures` | `10` | Ошибок на key id за окно |
| `ludwig.pat.rate-limit.per-source-failures` | `100` | Ошибок на источник за окно |
| `ludwig.pat.rate-limit.window` | `1m` | |
| `ludwig.pat.introspection.enabled` / `.path` | `true` / `/introspect` | |
| `ludwig.pat.web.base-path` | `/api/v1/personal-access-tokens` | API управления |
| `ludwig.pat.liquibase.enabled` | `true` | Применять changelog собственным бином |
| `ludwig.cache.caches.pat-verification.*` | назначение `security` | Кэш проверки (см. cache-стартер) |

## Развёртывание

1. **Зарегистрируйте шаблон `lpat_` во всех сканерах секретов до выпуска первого токена** (в
   `pat-secret-detection.properties` модуля `pat-core` есть готовое правило `gitleaks`).
2. Схема: `db/changelog/pat/pat-changelog.xml` (таблица `ludwig_pat`, уникальные индексы по `key_id` и
   `previous_key_id`, индексы по `owner_subject` и `expires_at`), подключается или запускается
   `PatLiquibaseAutoConfiguration`.
3. Ключ подписи (бин `RSAKey`) или `PatAssertionMinter`, делегирующий провайдеру.
4. `/oauth2/token` (и `/introspect`, если используется) в `ludwig.security.public-paths`; доступны только внутри mesh.
5. Выдать `pat:manage` владельцам токенов; `pat:manage:any` - только администраторам.
6. **Запланировать операции обслуживания** - см. «Известные пробелы» ниже.
7. Настроить границу, как описано выше.

## Наблюдаемость

**Метрики:**

| Метрика | Тип | Теги | Смысл |
|---|---|---|---|
| `ludwig.pat.exchange` | counter | `audience` | Успешные обмены. Должна следовать *числу различных токенов за время жизни assertion*, а не объёму запросов |
| `ludwig.pat.exchange.failure` | counter | `reason` (`malformed`, `unknown-key`, `bad-secret`, `revoked`, `expired`, `source-not-allowed`, `audience-not-permitted`, `audience-not-issuable`, `audience-missing`, `rate-limited`) | Отклонённые обмены |
| `ludwig.pat.rate-limited` | counter | `limit` | Срабатывания rate limit |
| `ludwig.pat.last-used.write-failed` | counter | | Не удалось записать `last_used_at` (обмен всё равно прошёл) |

**Логи.** `WARN`, если путь обмена или интроспекции отсутствует в `public-paths`, если `audiences` пуст, если
запрос, аутентифицированный PAT, отклонён на поверхности управления, если не удалось записать последнее
использование. `INFO` при отзыве токенов отключённого владельца.

**Рекомендуемые алерты:**

| Условие | Почему |
|---|---|
| `increase(ludwig_pat_exchange_failure_total{reason="revoked"}[15m]) > 0` | Кто-то всё ещё держит отозванные учётные данные |
| `rate(ludwig_pat_exchange_total[5m])` пропорциональна объёму запросов сервисов | Граница не кэширует |
| Высокий `increase(ludwig_pat_exchange_failure_total{reason=~"bad-secret|unknown-key"}[5m])` | Подбор / перебор |
| Рост `ludwig_pat_rate_limited_total` | То же или неверно настроенный клиент |

**Известные пробелы:**

- `PatService.destroyExpiredSecrets()`, `endElapsedRotationOverlaps()`, `revokeInactive()` и `purge()` **не
  планируются стартером**. Проверка всё равно соблюдает срок и перекрытие ротации по времени, но без планировщика
  неиспользуемые токены не отзываются после `inactivity-expiry`, дайджесты истёкших секретов остаются в таблице, а
  терминальные записи не удаляются после `retention`. Развёртывание должно запускать их на одной реплике (например,
  `ScheduledJob` из `job-core` под `RunLock`).
- Нет gauge живых токенов по состояниям и метрик операций обслуживания.

## Аудит

Категория `credential`; действия:

| Действие | Когда |
|---|---|
| `pat.issued` / `pat.issuance-refused` | Выпуск и отказ в выпуске (с причиной) |
| `pat.rotated` | Ротация |
| `pat.revoked` | Отзыв (вручную, владелец отключён, неактивность) с причиной |
| `pat.expired` | Обработано истечение |
| `pat.first-use` | Первый успешный обмен токена |
| `pat.dormant-wake` | Использование после перерыва больше `dormancy-threshold` |
| `pat.unseen-source` | Использование из ранее не встречавшегося источника |
| `pat.revoked-token-presented` | Предъявлен отозванный токен |
| `pat.purged` | Терминальная запись удалена по сроку хранения |

Секреты и дайджесты в события никогда не попадают.

## Runbook

| Симптом | Вероятная причина | Что делать |
|---|---|---|
| Все PAT падают на границе с 401 | `/oauth2/token` не публичен, неверный issuer/ключ подписи или граница не выполняет обмен | Проверить `WARN` при старте, причины в `ludwig_pat_exchange_failure_total`, конфигурацию границы |
| `audience-not-permitted` / `audience-not-issuable` | Токен выпущен не для этого сервиса или сервиса нет в `exchange.audiences` | Перевыпустить с audience или добавить её в `audiences` |
| Токен нужно уничтожить немедленно | Утечка | `DELETE /api/v1/personal-access-tokens/{id}` (владелец или `pat:manage:any`); вступает в силу в пределах окна отзыва (≤ 11m по умолчанию) |
| Владелец уволился | - | Отключение принципала (`PrincipalDisabledEvent`) отзывает его токены (`PatOwnerDisabledListener`); проверить: `SELECT id, revoked_at, revocation_reason FROM ludwig_pat WHERE owner_subject = '<sub>';` |
| Старые токены не отзываются по неактивности / таблица растёт | Обслуживание не запланировано | См. «Известные пробелы»; запускать `revokeInactive()` и `purge()` по расписанию |
| Растёт `last-used.write-failed` | Конкуренция в БД на `ludwig_pat` | Поднять `last-used-debounce`; проверить БД |

## Тестирование

Модульные: правила сервиса, отслеживание последнего использования, определение кэша, события аудита, отзыв при
отключении владельца. Интеграционные (Docker): `TokenExchangeIT`, `UniformExchangeFailureIT` (все отказы
неразличимы), `ExchangeRateLimitIT`, `PatIntrospectionIT`, `UniformIntrospectionFailureIT`,
`PatDirectAuthenticationIT`, `PatManagementIT`, `PatRetentionIT`, `PatSchemaIT`, `CredentialRejectedProblemIT`.
`SqlConfinementTest` (нет SQL/JDBC) и `PatArchitectureRulesTest`.

## Проектные решения

| Правило | Чем обеспечено |
|---|---|
| Эффективные права = владелец ∩ scope, никогда объединение | одно место построения, `credentials.one-attenuation-path` |
| Нет второго хранилища учётных данных, SPI или формата токена | `credentials.no-second-credential-store`, `...-spi` |
| Сырой секрет не покидает модули учётных данных | `credentials.secret-carrier-stays-inside`, огороженный accessor, маскирующий `toString()` |
| Токен не может управлять токенами | `PatCredentialGuard` на каждом эндпоинте |
| Кэш проверки - ограниченное окно отзыва | `CachePurpose.SECURITY` |
| Нет слабых алгоритмов дайджеста | Checkstyle `WeakDigestAlgorithm` |

- **Обмен на границе, а не у клиента**: логика OAuth не попадает в каждый `curl` и скрипт CI.
- **Интроспекция - запасной путь**, пока граница не умеет обмен; таблица, API, ротация, отзыв, аудит и ослабление
  прав одинаковы в обоих случаях.
- **Нет общей таблицы учётных данных** для deploy-ключей и секретов webhook: разные жизненные циклы и полномочия.
- **Пока нет принуждения для потоковых транспортов**: `revalidation-interval` существует; первый потоковый
  транспорт должен принести обеспечивающий тест.
