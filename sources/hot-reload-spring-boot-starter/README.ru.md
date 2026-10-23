# hot-reload-spring-boot-starter

*[English](README.md) · **Русский***

Конфигурация, меняющаяся без перезапуска: файлы properties и YAML с перезагрузкой на лету, секреты HashiCorp
Vault (опрос KV, динамические секреты с продлением аренды, аутентификация Kubernetes), шаблоны FreeMarker и
типизированные валидируемые объекты конфигурации, всегда хранящие последнее корректное значение. Переменные
окружения и системные свойства всегда сильнее перезагружаемого значения. Каждая перезагрузка аудируется с
отредактированными секретами.

| | |
|---|---|
| Роль | starter |
| Зависит от (в репозитории) | [`audit-core`](../audit-core) |
| Используется | стартерами `export`, `observability`, `user-settings`; `notification-service` |
| Поверхности | нет (читает файлы и Vault) |

| Возможность | Пакет |
|---|---|
| Движок перезагрузки (diff, last-known-good при ошибке, слушатели) | `core`, `core.watch` |
| Файлы properties/YAML на лету | `properties`, `propertysource` |
| Типизированная валидируемая конфигурация | `binding` (`RefreshableConfig`, `HotReloadTypedConfigFactory`) |
| Секреты Vault | `secrets` |
| Шаблоны FreeMarker | `template` |
| Уведомление о перезагрузке | `event` (`ConfigurationRefreshedEvent`) |
| Аудит | `audit` (`HotReloadAuditEntry`, `AuditingSourceChangeListener`) |
| Метрики | `metrics` |

## Использование

```xml
<dependency>
    <groupId>ru.ludwigandreas</groupId>
    <artifactId>hot-reload-spring-boot-starter</artifactId>
</dependency>
```

### Файлы на лету

```properties
ludwig.hotreload.files[0].path=/etc/config/app.properties
```

Каждый ключ файла - живое свойство Spring (`@Value`, `Environment.getProperty`,
`@ConfigurationProperties`). Правки - включая обновления смонтированных ConfigMap/Secret в Kubernetes -
подхватываются автоматически. Наблюдатель следит за каталогом, поэтому атомарная замена symlink (ConfigMap,
Vault Agent) обрабатывается.

**Приоритет.** Перезагружаемые источники имеют **наименьший** приоритет: ниже `application.yml`, системных
свойств и переменных окружения. Явное переопределение (`MYAPP_MYSERVICE_NAME=local-override`) всегда
побеждает.

### Типизированная конфигурация, обновляемая на месте

```java
@Service
class MyService {

    private final RefreshableConfig<MyServiceProperties> config;

    MyService(HotReloadTypedConfigFactory typedConfigFactory) {
        this.config = typedConfigFactory.create("myapp.myservice", MyServiceProperties.class);
    }

    void doWork() {
        MyServiceProperties current = config.get(); // всегда последнее корректное значение
    }
}
```

Ограничения `jakarta.validation` на классе проверяются при каждой перезагрузке; перезагрузка, не прошедшая
валидацию, отклоняется, и продолжает работать предыдущее значение. Добавьте `spring-boot-starter-validation`
(Hibernate Validator плюс реализация EL; один `hibernate-validator` падает с `HV000183`). Без валидатора
связывание работает, а ограничения не проверяются.

### Vault в Kubernetes

1. **Vault Agent Injector (предпочтительно, если есть).** Sidecar рендерит секреты в файлы на `emptyDir`;
   укажите их в `ludwig.hotreload.files`. Ни клиента Vault, ни сетевых вызовов из приложения.
2. **Прямой API Vault (`ludwig.hotreload.vault.*`).** Для кластеров без injector-а или для динамических
   (арендуемых) секретов. KV-секреты опрашиваются; динамические секреты (учётные данные БД, PKI) продлевает и
   ротирует `SecretLeaseContainer`. Значения Vault могут отсутствовать при создании первых бинов
   (аутентификация - сетевой вызов), поэтому читайте их через `RefreshableConfig` или используйте вариант 1,
   если значение нужно с первой строки `main()`.

### FreeMarker

```properties
ludwig.hotreload.freemarker.template-directory=/etc/templates
```

С `freemarker` в classpath бин `freemarker.template.Configuration` перезагружает шаблон сразу при изменении
каталога. Если у приложения свой `Configuration`:

```java
@Bean
Configuration freeMarkerConfiguration(HotReloadableTemplateLoader templateLoader) {
    Configuration configuration = new Configuration(Configuration.VERSION_2_3_32);
    configuration.setTemplateLoader(templateLoader);
    templateLoader.bindTo(configuration); // обязательно, чтобы invalidateAll() очищал кэш этого Configuration
    return configuration;
}
```

### Уведомления о перезагрузке

Каждая успешная перезагрузка публикует `ConfigurationRefreshedEvent`:

```java
@EventListener
void onConfigChanged(ConfigurationRefreshedEvent event) {
    log.info("{} changed: {}", event.getSourceId(), event.getChangedKeys());
}
```

[observability](../observability-spring-boot-starter/README.ru.md) использует это, чтобы применять
`logging.level.*` на лету.

## Конфигурация

```properties
ludwig.hotreload.enabled=true

# Файлы
ludwig.hotreload.files[0].path=/etc/config/app.properties
ludwig.hotreload.files[0].key-prefix=
ludwig.hotreload.file-watch.debounce=500ms

# Vault
ludwig.hotreload.vault.enabled=true
ludwig.hotreload.vault.uri=https://vault.vault.svc:8200
ludwig.hotreload.vault.poll-interval=30s
# Аутентификация Kubernetes (в кластере: обменивает JWT service account пода на токен Vault)
ludwig.hotreload.vault.kubernetes.role=my-app
ludwig.hotreload.vault.kubernetes.auth-path=kubernetes
# ludwig.hotreload.vault.kubernetes.service-account-token-file по умолчанию - путь projected-токена
# Статический токен - только для локальной разработки
# ludwig.hotreload.vault.token=...
# Статический KV-секрет (v1 или v2), перечитывается каждые poll-interval
ludwig.hotreload.vault.secrets[0].mount=secret
ludwig.hotreload.vault.secrets[0].path=myapp/config
ludwig.hotreload.vault.secrets[0].kv-version=2
ludwig.hotreload.vault.secrets[0].key-prefix=
# Динамический секрет, продлевается/ротируется через аренды Vault
ludwig.hotreload.vault.dynamic-secrets[0].path=database/creds/my-role
ludwig.hotreload.vault.dynamic-secrets[0].rotating=true
ludwig.hotreload.vault.dynamic-secrets[0].key-prefix=db.

# FreeMarker
ludwig.hotreload.freemarker.template-directory=/etc/templates

# Аудит
ludwig.hotreload.audit.enabled=true
ludwig.hotreload.audit.actor=            # по умолчанию: локальный hostname

# Метрики
ludwig.hotreload.metrics.enabled=true
```

## Развёртывание

- **Файлы**: монтируйте ConfigMap/Secret как тома (не `subPath`, такие монтирования Kubernetes не обновляет).
- **Vault, аутентификация Kubernetes**: роль Vault, привязанная к service account, с политиками на
  перечисленные пути; projected-токен service account смонтирован (путь по умолчанию).
- **Vault, статический токен**: только локальная разработка; никогда в развёрнутом профиле.
- Не держите секреты в `application.yml` и образе; кладите их в Vault или смонтированный Secret.

## Наблюдаемость

**Метрики** (если есть Micrometer):

| Метрика | Тип | Теги | Смысл |
|---|---|---|---|
| `ludwig.hotreload.reload.succeeded` | counter | `source` | Успешные перезагрузки |
| `ludwig.hotreload.reload.failed` | counter | `source`, `exception` | Неудачные перезагрузки (продолжает работать last-known-good) |
| `ludwig.hotreload.reload.changed.keys` | summary | `source` | Изменённых ключей за перезагрузку |
| `ludwig.hotreload.reload.seconds.since.last.success` | gauge | `source` | Устаревание источника. **Алертить на неё**: отказывающий источник молчит по замыслу |

**Логи.** `INFO Source '<id>' <action> (<n> key(s) changed)`. `WARN`, когда источник сообщает об ошибке
(сохраняя last-known-good), когда типизированная конфигурация не проходит валидацию (сохраняя предыдущее
значение), когда не удалась первая загрузка файла и при ошибках аренды Vault. `ERROR`, когда слушатель
бросает исключение.

**Рекомендуемые алерты:**

| Условие | Почему |
|---|---|
| `ludwig_hotreload_reload_seconds_since_last_success` > ожидаемый интервал изменений + интервал опроса, для источников Vault | Источник перестал обновляться; секреты могут истечь |
| `increase(ludwig_hotreload_reload_failed_total[15m]) > 0` | Плохая правка или недоступный Vault |

## Аудит

| Поле | Применено | Ошибка |
|---|---|---|
| category | `config` | `config` |
| action | `config.reloaded` | `config.reload-failed` |
| actor | этот экземпляр (hostname или `ludwig.hotreload.audit.actor`), тип принципала `INSTANCE` | то же |
| outcome | `SUCCESS` | `FAILURE` |
| attributes | `changes` (`key → {old, new}`), отсортированный `changedKeys` | ошибка |

- **Одна перезагрузка = одно событие** со всеми изменёнными ключами.
- Actor - экземпляр, который **применил** изменение, а не человек, который его сделал. Авторство изменений -
  в audit device Vault или в истории git ConfigMap.
- **Значения секретов редактируются до создания события**: каждый ключ из Vault (источник `vault:` /
  `vault-lease:`) и каждый ключ, чьё имя совпадает с шаблоном секрета, становятся `***REDACTED***`.
  Расширяется через `ludwig.audit.redaction.*` (см. [audit-core](../audit-core/README.ru.md#редактирование)).
- `ludwig.hotreload.audit.enabled=false` отключает события аудита этого модуля.

## Runbook

| Симптом | Вероятная причина | Что делать |
|---|---|---|
| Изменённый ConfigMap не подхватывается | Монтирование через `subPath` или ключ переопределён переменной окружения / `application.yml` | Монтировать том целиком; проверить приоритеты (`/actuator/env` не экспонирован - смотрите env развёртывания) |
| `Refresh of configuration ... failed validation` | Неверное значение в источнике | Исправить источник; используется предыдущее значение |
| Растёт `seconds_since_last_success` для источника Vault | Vault недоступен, токен/роль истекли, изменилась политика | Проверить связность с Vault и привязку роли; последний секрет используется до истечения его аренды |
| `Vault lease error` | Аренду динамического секрета не удалось продлить или ротировать | Проверить secrets engine Vault; учётные данные истекают в конце аренды |
| Изменение шаблона не видно | Собственный бин `Configuration` не привязан к загрузчику | Вызвать `templateLoader.bindTo(configuration)` |
| Значения из Vault нет при старте | Прочитано до завершения аутентификации в Vault | Использовать `RefreshableConfig` или файловый вариант с Vault Agent |

## Тестирование

Только модульные тесты, без Docker: координация перезагрузки и last-known-good, источники properties и YAML,
наблюдение за файлами (`FileResourceWatcherTest`, `PollingResourceWatcherTest`), связка с окружением,
типизированное связывание и валидация, редактирование записи аудита, метрики, автоконфигурация.

**Не покрыто тестами:** источники Vault (опрос KV, аренды динамических секретов, аутентификация Kubernetes) не
имеют автоматических тестов. Проверяйте изменения, связанные с Vault, вручную на настоящем Vault до релиза.

## Проектные решения

- **Наименьший приоритет** перезагружаемых значений: внешне управляемая конфигурация никогда не должна молча
  перекрывать явное переопределение.
- **Last-known-good**: сломанная правка никогда не заменяет работающее значение.
- **Редактирование при построении**: приёмник аудита никогда не получит секрет.
- **Не аудит-лог Vault**: модуль записывает, кто применил изменение, а не кто его сделал.
