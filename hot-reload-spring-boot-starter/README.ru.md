# hot-reload-spring-boot-starter

*[English](README.md) · **Русский***

Промышленная горячая перезагрузка для сервисов на Java 17 + Spring Boot: типизированная и
валидируемая конфигурация, живое обновление property/YAML-файлов, шаблонов FreeMarker и секретов
HashiCorp Vault — причём переменные окружения и системные свойства всегда могут перекрыть
перезагруженное значение. Добавьте зависимость, укажите файлы и/или пути Vault — и связанные
конфигурационные бины начнут обновляться по мере изменения источников: без перезапуска, без
передеплоя.

## Быстрый старт

```xml
<dependency>
    <groupId>ru.ludwigandreas</groupId>
    <artifactId>hot-reload-spring-boot-starter</artifactId>
    <version>1.0.0</version>
</dependency>
```

Горячая перезагрузка properties- или YAML-файла:

```properties
ludwig.hotreload.files[0].path=/etc/config/app.properties
```

Любой ключ этого файла теперь живое Spring-свойство: читайте его обычным образом (`@Value`,
`Environment.getProperty(...)`, `@ConfigurationProperties`), и оно будет отражать текущее содержимое
файла. Правка файла — или, в Kubernetes, ConfigMap/Secret, из которого он смонтирован, —
подхватывается автоматически.

Для значения, которое должно обновляться **без** перезапуска бина `@ConfigurationProperties`,
используйте `RefreshableConfig`:

```java
@Service
class MyService {

    private final RefreshableConfig<MyServiceProperties> config;

    MyService(HotReloadTypedConfigFactory typedConfigFactory) {
        this.config = typedConfigFactory.create("myapp.myservice", MyServiceProperties.class);
    }

    void doWork() {
        MyServiceProperties current = config.get(); // всегда последнее валидное значение
        ...
    }
}
```

`MyServiceProperties` может нести ограничения `jakarta.validation` (`@NotBlank`, `@Min`, …) —
перезагрузка, не прошедшая валидацию, отклоняется, и продолжает работать предыдущее валидное
значение.

## Зачем горячая перезагрузка и что здесь означает «перекрытие окружением»

Горячо перезагружаемые значения (файлы, секреты Vault) регистрируются в `Environment` с **самым
низким** приоритетом — ниже `application.yml`/`.properties`, системных свойств и переменных
окружения. Это намеренно: горячая перезагрузка существует ради значений, живущих вне вашего
артефакта (секреты, конфигурация, которой управляет эксплуатация), и ни одно из них не должно молча
перекрывать явное переопределение, выставленное для локальной разработки или разбора инцидента. Если
вы экспортировали `MYAPP_MYSERVICE_NAME=local-override`, побеждает именно оно — точка, независимо от
того, что говорят перезагруженный файл или секрет Vault.

## Возможности и где они живут

| Возможность | Пакет |
|---|---|
| Движок перезагрузки (диф, откат к последнему валидному, слушатели) | `core`, `core.watch` |
| Живые property/YAML-файлы | `properties`, `propertysource` |
| Типизированная валидируемая конфигурация | `binding` (`RefreshableConfig`, `HotReloadTypedConfigFactory`) |
| Секреты HashiCorp Vault (опрос KV, динамические секреты с продлением аренды, аутентификация Kubernetes) | `secrets` |
| Шаблоны FreeMarker | `template` |
| Конфигурация, автоконфигурация | `config` (`HotReloadProperties`, `ludwig.hotreload.*`) |
| Общее уведомление о перезагрузке | `event` (`ConfigurationRefreshedEvent`) |
| Аудит (кто/когда/старое/новое) | `audit` (`HotReloadAuditEntry`, `AuditingSourceChangeListener`) |
| Метрики | `metrics` (Micrometer, опционально) |

## Справочник конфигурации

```properties
ludwig.hotreload.enabled=true

# --- Файлы -------------------------------------------------------------
ludwig.hotreload.files[0].path=/etc/config/app.properties
ludwig.hotreload.files[0].key-prefix=
ludwig.hotreload.file-watch.debounce=500ms

# --- Vault ---------------------------------------------------------------
ludwig.hotreload.vault.enabled=true
ludwig.hotreload.vault.uri=https://vault.vault.svc:8200
ludwig.hotreload.vault.poll-interval=30s

# Аутентификация Kubernetes (рекомендуется внутри кластера: меняет JWT сервис-аккаунта пода на токен Vault)
ludwig.hotreload.vault.kubernetes.role=my-app
ludwig.hotreload.vault.kubernetes.auth-path=kubernetes
# ludwig.hotreload.vault.kubernetes.service-account-token-file по умолчанию указывает на projected-токен

# Статический токен (только для локальной разработки)
# ludwig.hotreload.vault.token=...

# Статический KV-секрет (v1 или v2), перечитывается каждые poll-interval
ludwig.hotreload.vault.secrets[0].mount=secret
ludwig.hotreload.vault.secrets[0].path=myapp/config
ludwig.hotreload.vault.secrets[0].kv-version=2
ludwig.hotreload.vault.secrets[0].key-prefix=

# Динамический секрет (например, database/creds/my-role), приходит через собственное продление аренды Vault
ludwig.hotreload.vault.dynamic-secrets[0].path=database/creds/my-role
ludwig.hotreload.vault.dynamic-secrets[0].rotating=true
ludwig.hotreload.vault.dynamic-secrets[0].key-prefix=db.

# --- FreeMarker ------------------------------------------------------------
ludwig.hotreload.freemarker.template-directory=/etc/templates

# --- Аудит -----------------------------------------------------------
ludwig.hotreload.audit.enabled=true
ludwig.hotreload.audit.actor=

# --- Метрики -----------------------------------------------------------
ludwig.hotreload.metrics.enabled=true
```

## HashiCorp Vault в Kubernetes: две поддерживаемые схемы

1. **Vault Agent Injector (рекомендуется, когда доступен).** Сайдкар рендерит секреты в файлы на
   `emptyDir`, обновляя их на месте атомарной подменой симлинка при каждой ротации. Направьте
   `ludwig.hotreload.files` на эти файлы — ни клиента Vault, ни единого сетевого вызова из вашего
   приложения. Это собственная рекомендованная HashiCorp интеграция с Kubernetes, и
   `FileResourceWatcher` написан специально так, чтобы корректно отрабатывать именно этот механизм
   подмены симлинка (он следит за каталогом, а не только за именем файла).
2. **Прямой API Vault (`ludwig.hotreload.vault.*`).** На случай, когда инжектор не используется или
   нужны динамические (арендованные) секреты. `VaultKvSecretSource` опрашивает статические
   KV-секреты; динамические (учётные данные БД, PKI, …) запрашиваются у Vault в
   renewable/rotating-режиме и приходят через собственное продление аренды `SecretLeaseContainer` —
   без всякого опроса. Поскольку аутентификация в Vault — сетевой вызов, значения из Vault не
   гарантированно доступны к моменту конструирования самого первого бина так, как доступны значения
   из файлов (см. Javadoc у `HotReloadVaultAutoConfiguration`): для значений из этого источника
   предпочитайте `RefreshableConfig`/`HotReloadTypedConfigFactory` либо вернитесь к схеме 1, если
   значение обязано существовать с первой строки `main()`.

## Валидация

Добавьте `spring-boot-starter-validation`, чтобы получить и Hibernate Validator, и реализацию EL,
которая нужна его интерполятору сообщений по умолчанию (один `hibernate-validator` без неё бросает
`HV000183`). Без какого-либо валидатора в classpath связывание всё равно работает — ограничения
просто не проверяются.

## FreeMarker

```properties
ludwig.hotreload.freemarker.template-directory=/etc/templates
```

С этой настройкой (и `freemarker` в classpath) бин `freemarker.template.Configuration`
предоставляется автоматически и подключён так, чтобы перезагружать шаблон в момент изменения его
каталога, — без ожидания ленивой проверки `templateUpdateDelay` самого FreeMarker. Если в приложении
уже есть собственный бин `Configuration`, соберите его вручную:

```java
@Bean
Configuration freeMarkerConfiguration(HotReloadableTemplateLoader templateLoader) {
    Configuration configuration = new Configuration(Configuration.VERSION_2_3_32);
    configuration.setTemplateLoader(templateLoader);
    templateLoader.bindTo(configuration); // нужно, чтобы invalidateAll() чистил кэш именно этой Configuration
    return configuration;
}
```

## Аудит

Каждая успешная перезагрузка порождает одну запись `HotReloadAuditEntry` — источник, отметка времени,
актор, старое и новое значение каждого изменившегося ключа, — которая разворачивается в конверт аудита
платформы и передаётся единственному `AuditSink`:

```java
public record HotReloadAuditEntry(String sourceId, Instant timestamp, String actor,
                                   Map<String, ValueChange> changes) {
    public record ValueChange(Object oldValue, Object newValue) { }
    public AuditEvent toAuditEvent() { ... }        // action: config.reloaded
}
```

**`HotReloadAuditLogger` и `Slf4jHotReloadAuditLogger` удалены.** Это были два из девяти механизмов
аудита, накопившихся в платформе, и журнал теперь идёт туда же, куда журнал любого другого модуля — в лог,
в таблицу `audit_event` (только на добавление), в SIEM через транзакционный outbox или в несколько мест
сразу. См. [`audit-core`](../audit-core). Для регулируемой среды публикуйте `@Bean AuditSink` вместо
`HotReloadAuditLogger` — и через тот же бин вы получите журнал всех остальных модулей, а не только этого:

```java
@Bean
AuditSink auditSink(MySiemClient siem) {
    return event -> siem.ship(event);               // все категории, не только config
}
```

**Одна перезагрузка теперь одно событие, а не событие на ключ.** Изменившиеся ключи — это атрибут: карта
`ключ -> {old, new}` плюс отсортированный список `changedKeys`; потому что перезагрузка *и есть* одно
произошедшее: кто-то один раз отредактировал ConfigMap. `Slf4jHotReloadAuditLogger` действительно писал
строку на ключ, а разбиение перезагрузки на N событий превратило бы вопрос «что изменила эта
перезагрузка» в задачу корреляции вместо одной строки. Неудачная перезагрузка — отдельное действие
`config.reload-failed` с исходом `FAILURE`, а не запись с пустым набором изменений: «ничего не
изменилось» и «сломалось» не должны быть одной формой.

**Что такое `actor` и чем он не является.** `actor` идентифицирует *этот экземпляр приложения* (по
умолчанию локальное имя хоста, переопределяется через `ludwig.hotreload.audit.actor`) — то есть «какой
экземпляр заметил и применил изменение, и когда». Это **не** та личность выше по потоку, которая
изменение сделала. Ни смонтированный файл, ни обычное чтение из Vault не раскрывают, кто его записал — для
этого есть собственное аудит-устройство Vault (или `git blame` по манифесту ConfigMap/Secret). Журнал этой
библиотеки — дополняющая запись «кто/когда заметил и применил» на стороне потребителя, а не замена
аудит-логу Vault. Именно поэтому он записывается с `principalType` `INSTANCE`: журнал, в котором имя хоста
стоит в той же колонке, что идентификатор человека, и их нечем различить, — это журнал, который прочитают
неверно.

**Значения секретов всегда скрываются до того, как запись возникнет**: каждый ключ, пришедший из Vault,
плюс любой ключ, чьё имя подходит под распространённые секретные шаблоны (`password`, `secret`, `token`,
`credential`, `apiKey`, …), маскируется как `***REDACTED***` независимо от того, какой приёмник настроен, —
чтобы свой приёмник, пишущий в базу, не мог случайно занести туда учётные данные. Если нужна полная
аудиторская видимость **значений** секретов, она должна идти из собственного аудит-устройства Vault,
доступ к которому ограничен именно для этого; библиотека намеренно не пытается им быть.

Оба правила классификации этого модуля пережили консолидацию как композируемые классификаторы, а префиксы
`vault:` / `vault-lease:` теперь конфигурация, а не исходный код: см.
`ludwig.audit.redaction.sensitive-provenance-prefixes`. Развёртывание с иначе названным хранилищем
секретов добавляет свой префикс там, а не правит этот модуль.

Полностью выключить вклад этого модуля в журнал аудита: `ludwig.hotreload.audit.enabled=false`.
**Что заметит развёртывание:** логгера `ru.ludwigandreas.hotreload.audit` больше нет; перезагрузки — в
`ru.ludwigandreas.audit` с `category=config`.

## Метрики

Опционально (только при наличии Micrometer в classpath, включено по умолчанию через
`ludwig.hotreload.metrics.enabled`): `ludwig.hotreload.reload.succeeded` и
`ludwig.hotreload.reload.failed` (с тегом `source`, а для неудач ещё и `exception`) считают каждую
перезагрузку; `ludwig.hotreload.reload.changed.keys` — распределение числа изменённых ключей на
перезагрузку; `ludwig.hotreload.reload.seconds.since.last.success` — gauge на каждый источник,
отвечающий на вопрос «насколько этот источник сейчас протух». Именно на него стоит вешать алерт:
источник, переставший перезагружаться, по построению падает молча — он просто продолжает отдавать
последнее валидное содержимое.

## Общие уведомления о перезагрузке

Каждая успешная перезагрузка — файла, KV-секрета или динамического секрета — публикует в
`ApplicationContext` событие `ConfigurationRefreshedEvent`, независимо от того, пользуется ли кто-то
`RefreshableConfig` для этого источника:

```java
@EventListener
void onConfigChanged(ConfigurationRefreshedEvent event) {
    log.info("{} изменился: {}", event.getSourceId(), event.getChangedKeys());
}
```
