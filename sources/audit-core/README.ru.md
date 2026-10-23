# audit-core

*[English](README.md) · **Русский***

Единый аудиторский конверт платформы (`AuditEvent`), SPI приёмника (`AuditSink`) с настраиваемой
политикой отказа, пакет редактирования, используемый везде, где значение может быть чувствительным, и
единственный ответ на вопрос «кто действует» (`ActorResolver`). Модуль ни от чего в репозитории не
зависит, поэтому любой модуль - включая те, что ниже `db-core`, - может вести аудиторский след через него.

| | |
|---|---|
| Роль | library (с автоконфигурацией) |
| Зависит от (в репозитории) | ничего |
| Используется | стартерами `audit`, `export`, `file-action`, `file-ingest`, `grpc-client`, `hot-reload`, `idempotency`, `messaging`, `observability`, `odata-filter`, `outbox`, `pat`, `reconciliation`, `rest-client`, `security` |
| Поверхности | нет (пишет в логи; постоянные приёмники - в [`audit-spring-boot-starter`](../audit-spring-boot-starter)) |

**Разделение.** Модули, которым нужен след, есть на всех уровнях стека, несколько из них ниже `db-core`
(security, rest-client, hot-reload). Поэтому всё, что требует БД, планировщика или outbox, живёт в
[`audit-spring-boot-starter`](../audit-spring-boot-starter); этот модуль сохраняет ноль зависимостей в
репозитории. Редактирование живёт здесь по той же причине: модуль, импортирующий `audit-core` только ради
`Redaction.MASK` (как `observability-spring-boot-starter`), - задуманная схема.

## Использование

```xml
<dependency>
    <groupId>ru.ludwigandreas</groupId>
    <artifactId>audit-core</artifactId>
</dependency>
```

Одной зависимости достаточно для работающего следа: `AuditCoreAutoConfiguration` подключает SLF4J-приёмник,
композитный приёмник и политику отказа без БД и планировщика.

### Запись события

Держите типизированную запись в месте вызова и преобразуйте её:

```java
public record ExportAuditEvent(UUID runId, OperationStatus status, ...) {
    public AuditEvent toAuditEvent() {
        return AuditEvent.builder()
                .category(AuditCategories.EXPORT)
                .action("run." + status.name().toLowerCase(Locale.ROOT))
                .actor(principalId == null ? Actor.system() : Actor.of(principalId))
                .resource(new Resource(RESOURCE_TYPE, runId.toString(), definitionKey))
                .outcome(degradedStages.isEmpty() ? AuditOutcome.success()
                        : AuditOutcome.partial("degraded stages: " + ...))
                .correlationId(correlationId)
                .attributes(attributes)
                .build();
    }
}

// Внедрите AuditSink. Не объявляйте свой SPI, не ловите исключения, не оборачивайте.
audit.record(new ExportAuditEvent(...).toAuditEvent());
```

### Конверт

```java
public record AuditEvent(
        UUID id,                        // назначается при создании; ключ идемпотентности при отправке
        Instant occurredAt,
        String category,                // см. AuditCategories
        String action,                  // "run.completed", "access.denied", "setting.changed"
        Actor actor,                    // subject, principalType, displayName, onBehalfOf
        Resource resource,              // type, id, name
        AuditOutcome outcome,           // SUCCESS | FAILURE | DENIED | PARTIAL, + reason
        String correlationId,
        String traceId,
        Map<String, Object> attributes) // специфичное для модуля, уже отредактированное
```

Правила для каждого события:

1. **`attributes` редактируются при построении события, никогда в приёмнике.** Приёмник, который может
   получить неотредактированное событие, однажды его сохранит.
2. **Никаких payload.** След хранится годами и читается людьми, не имеющими права на охраняемые данные:
   записывайте, *какой* объект затронут, а не его содержимое.
3. **`DENIED` и `FAILURE` различаются.** Denied - платформа отказала; failure - попыталась и не смогла. Они
   ведут к противоположным расследованиям.

**Категории** (`AuditCategories`):

| Категория | Кто пишет |
|---|---|
| `access` | security - решения авторизации |
| `audit` | audit-стартер - очистка самого следа по сроку хранения |
| `config` | hot-reload - перезагрузки конфигурации и секретов; notification-service - объявления |
| `credential` | pat - жизненный цикл персональных токенов доступа |
| `export` | export - запуски отчётов |
| `file-action` | file-action - файловые операции (строковая константа в `FileActionAuditEvent`, не в `AuditCategories`) |
| `idempotency` | idempotency - захваты и повторы |
| `ingest` | file-ingest - загрузки файлов |
| `messaging` | messaging - dead letter и потери |
| `outbound-call` | rest-client, grpc-client - исходящие вызовы |
| `outbox` | outbox - переходы сообщений |
| `query` | odata-filter - выполнение запросов |
| `reconciliation` | reconciliation - запуски и записи синхронизации |
| `settings` | user-settings - изменения настроек и согласий |

Каждый README модуля перечисляет свои действия в разделе **Аудит**.

### Приёмники

```java
public interface AuditSink {
    void record(AuditEvent event);
}
```

Только запись, по замыслу: след, который может читать аудируемый код, однажды им же будет отфильтрован.
Чтение - это query API в стартере, другой тип с другими вызывающими.

| Приёмник | Модуль | Что делает |
|---|---|---|
| `Slf4jAuditSink` | здесь | Одна структурированная строка на событие в логгер `ru.ludwigandreas.audit` |
| `NoopAuditSink` | здесь | Отбрасывает (тесты или развёртывание, которое маршрутизирует иначе) |
| `CompositeAuditSink` | здесь | Рассылает в несколько приёмников; обычная конфигурация |
| `JpaAuditSink` | стартер | Только-добавляемая таблица `audit_event` |
| `OutboxAuditSink` | стартер | Отправка в SIEM ровно один раз через outbox |

### Политика отказа

Приёмник может бросить исключение. Выживет ли вызывающий, решает `AuditFailurePolicy`, применяемая
`FailurePolicyAuditSink` перед каждым настоящим приёмником:

| Категория | По умолчанию | Почему |
|---|---|---|
| `settings` | `FAIL_OPERATION` | Изменение настройки или согласия не должно закоммититься без записи аудита. Запись идёт в транзакции вызывающего, поэтому отказ откатывает изменение |
| остальные | `LOG_AND_CONTINUE` | Сбой аудита не должен становиться сбоем сервиса |

Модуль не должен ставить свой `try`/`catch` вокруг `record` - это переопределяет политику развёртывания.
(`AuthorizationDeniedAuditListener` - единственное исключение: он защищает решение авторизации от самого
слушателя.) Каждый отказ при любой политике доходит до `AuditSinkFailureListener`.

### Редактирование

Четыре **классификатора** решают, что чувствительно; они объединяются как **союз**
(`SensitivityClassifier.anyOf`), поэтому ни один не может снять пометку, поставленную другим:

| Классификатор | Правило |
|---|---|
| `KeyNameSensitivityClassifier` | Имя ключа совпадает с шаблоном имён секретов (`password`, `token`, `secret`, ...) |
| `ProvenanceSensitivityClassifier` | Значение пришло из Vault, как бы оно ни называлось |
| `ConfiguredNamesSensitivityClassifier` | Имя перечислено развёртыванием (без учёта регистра) |
| `DeclaredSensitivityClassifier` | Определение объявляет его персональными данными |

Один **`Redactor`** маскирует скаляры, карты атрибутов (рекурсивно), мультикарты заголовков, JSON-тела
(структурным обходом: регулярное выражение совпало бы с `"password"` как значением и пропустило бы
вложенные поля) и form-encoded тела (заменяются целиком).

**Одна маска:** `Redaction.MASK` = `***REDACTED***`, константа без свойства для изменения. Хэш обратим для
малых множеств значений, обрезка выдаёт идентифицирующую часть; настраиваемую маску можно выставить в
пустую строку, и «отредактировано» совпадёт с «никогда не задавалось». Правило Checkstyle
`SecondRedactionMask` валит сборку при второй константе маски где угодно.

### Кто действует

`ActorResolver` - единственное определение текущего действующего лица. Проставление `created_by` /
`last_modified_by` в `db-core` питается от того же резолвера (через `ActorResolverAuditorProvider` в
стартере), поэтому строка и событие аудита о ней называют одного человека.

| Резолвер | Модуль | Активен, когда |
|---|---|---|
| `PrincipalActorResolver` | security-стартер | `LudwigPrincipal` в classpath |
| `SpringSecurityActorResolver` | здесь | Spring Security есть, `LudwigPrincipal` нет |
| `ActorResolver.unattributed()` | здесь | Нет ни того, ни другого; события несут `Actor.system()` |

Три резолвера взаимоисключающи **по classpath** (`@ConditionalOnMissingClass` на вложенных классах
конфигурации), а не по порядку автоконфигурации: ошибка порядка годами молча записывала бы не того
человека. Бин `ActorResolver` сервиса заменяет все три.

### Корреляция

По умолчанию `CorrelationProvider.none()` (модуль не может зависеть от observability). Сервис с
observability или web-core публикует:

```java
@Bean
CorrelationProvider auditCorrelationProvider(CorrelationContext context) {
    return context::currentCorrelationId;
}
```

Correlation id связывает событие аудита с логами, трассами и исходящими вызовами того же запроса.

## Конфигурация

Все свойства `ludwig.audit.*` связывает `AuditProperties` здесь, включая блоки, реализованные стартером
(один класс на префикс, чтобы свойство не связывалось в одном месте и не игнорировалось в другом).

| Свойство | По умолчанию | Смысл |
|---|---|---|
| `ludwig.audit.enabled` | `true` | Главный выключатель |
| `ludwig.audit.source-system` | `spring.application.name` | Источник, записываемый в каждую сохранённую строку |
| `ludwig.audit.slf4j.enabled` | `true` | SLF4J-приёмник |
| `ludwig.audit.failure.default-policy` | `LOG_AND_CONTINUE` | Политика для категорий, не указанных ниже |
| `ludwig.audit.failure.by-category.<category>` | `settings: FAIL_OPERATION` | Политика по категории |
| `ludwig.audit.redaction.key-name-heuristic` | `true` | Включить классификатор по имени ключа |
| `ludwig.audit.redaction.key-name-pattern` | поставляемый шаблон | Заменить регулярное выражение имён |
| `ludwig.audit.redaction.sensitive-provenance-prefixes` | `vault:`, `vault-lease:` | Источники, считающиеся чувствительными |
| `ludwig.audit.redaction.sensitive-names` | пусто | Всегда чувствительные ключи, поля и заголовки |
| `ludwig.audit.jpa.*`, `.outbox.*`, `.retention.*`, `.liquibase.*` | | См. [`audit-spring-boot-starter`](../audit-spring-boot-starter/README.ru.md#конфигурация) |

*Шаблон* редактирования настраивается, *маска* - нет: расширять круг чувствительного всегда безопасно.

## Развёртывание

Развёртывать нечего. Направьте логгер `ru.ludwigandreas.audit` туда, где развёртывание хранит аудиторские
логи (это обычный SLF4J-логгер). Для следа в БД или отправки в SIEM добавьте
[`audit-spring-boot-starter`](../audit-spring-boot-starter).

## Наблюдаемость

**Логи.**

| Логгер / уровень | Содержимое |
|---|---|
| `ru.ludwigandreas.audit` / `INFO` | Одна строка на событие: `audit id=… at=… category=… action=… outcome=… actor=… actorType=… onBehalfOf=… resourceType=… resourceId=… correlationId=… traceId=… reason=… attributes=…` |
| `WARN` | `Audit sink <sink> failed for <category>/<action> (event <id>); the operation was not affected` (при `LOG_AND_CONTINUE`) |

**Метрики.** Здесь нет. Со стартером отказы приёмника считаются как `ludwig.audit.sink.failures`.

**Известные пробелы:** у сервиса только с `audit-core` (без стартера) нет метрики отказов приёмника - только
строка `WARN`. Зарегистрируйте `AuditSinkFailureListener` со счётчиком или добавьте стартер.

## Аудит

Этот модуль *и есть* механизм аудита; от своего имени он ничего не записывает.

## Runbook

| Симптом | Вероятная причина | Что делать |
|---|---|---|
| Предупреждения `Audit sink ... failed` | Нижестоящий приёмник (БД, outbox) отказывает | Смотреть исключение в той же строке; починить приёмник. События этого окна отсутствуют в упавшем приёмнике, но есть в других приёмниках композита (например, в логе) |
| Изменения настроек падают с 503 | Категория `settings` - `FAIL_OPERATION`, а приёмник отказывает | Так задумано. Восстановить приёмник (обычно таблицу `audit_event`) |
| Для действий пользователя actor = `system` | Ни один резолвер не смог прочитать принципала | Проверить, что security-стартер (или Spring Security) в classpath и запрос аутентифицирован |
| В строке аудита появился секрет | Имя не поймано классификаторами | Немедленно добавить его в `ludwig.audit.redaction.sensitive-names`; сменить секрет |

## Тестирование

Модульные тесты покрывают построитель конверта, политики отказа, каждый классификатор, их союз,
редактор на всех формах и взаимоисключающие по classpath резолверы действующего лица (с Spring Security /
`LudwigPrincipal` и без). Модуль, который аудирует, должен в своих тестах проверять, что ожидаемый
`AuditEvent` доходит до тестового двойника `AuditSink`.

## Проектные решения

- **Один конверт, один SPI, одна маска, одно определение действующего лица** на всю платформу, с
  принуждением: ArchUnit проверяет, что модуль аудирует через `AuditSink`; Checkstyle запрещает вторую маску.
- **Типизированные записи в местах вызова, `AuditEvent` для передачи.** Тринадцать именованных компонентов
  читаются лучше тринадцати элементов карты.
- **Без защиты от подделки (хэш-цепочки, подписи).** Ей нужны управление ключами и верификатор; недостроенная
  цепочка выглядит как гарантия, но не является ею. Вместо этого стартер обеспечивает только-добавление.
- **Без автоматической проверки «каждое изменение аудируется».** Какие методы меняют состояние -
  семантическое суждение, которое структурное правило сделать не может.
