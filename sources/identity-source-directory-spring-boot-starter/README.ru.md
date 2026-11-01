# identity-source-directory-spring-boot-starter

*[English](README.md) · **Русский***

Наполняет [`identity-projection-spring-boot-starter`](../identity-projection-spring-boot-starter) от провайдеров,
которые отдают пользователей через API. Сервис реализует **один интерфейс**, `IdentityDirectory`, против своего
провайдера; стартер синхронизирует изменения инкрементально от сохранённой водяной отметки, полностью обходит каждый
источник по расписанию, выполняет и то, и другое как задачи [`job-core`](../job-core) под блокировками запуска и
изолирует сбои по источникам.

| | |
|---|---|
| Роль | starter |
| Зависит от (в репозитории) | [`identity-projection-spring-boot-starter`](../identity-projection-spring-boot-starter), [`job-core`](../job-core) |
| Поверхности | исходящие вызовы провайдера (собственный клиент сервиса) |

## Использование

```xml
<dependency>
    <groupId>ru.ludwigandreas</groupId>
    <artifactId>identity-source-directory-spring-boot-starter</artifactId>
</dependency>
```

```yaml
ludwig:
  identity:
    sources:
      staff:
        role-mapping:
          catalog-admins: [CATALOG_ADMIN]
        unmapped-roles: DROP
```

### Как реализовать каталог

```java
@Component
class StaffDirectory implements IdentityDirectory {

    private final ProviderUsersClient users;   // собственный @LudwigRestClient сервиса

    StaffDirectory(ProviderUsersClient users) {
        this.users = users;
    }

    @Override
    public String source() {
        return "staff";
    }

    @Override
    public DirectoryPage fetch(DirectoryQuery query) {
        ProviderPage page = users.list(query.cursor(), query.changedSince(), query.pageSize());
        List<IdentityChange> changes = page.users().stream().map(user -> user.active()
                ? (IdentityChange) new IdentityChange.Upsert("staff", user.id(), user.updatedAt(), user.etag(),
                        new UserProfile(user.name(), user.orgId(), user.groups(), null))
                : new IdentityChange.Disable("staff", user.id(), user.updatedAt(), user.etag())).toList();
        Instant watermark = page.users().isEmpty() ? null : page.users().get(page.users().size() - 1).updatedAt();
        return new DirectoryPage(changes, page.nextCursor(), watermark);
    }
}
```

**Контракт.** Если задан `changedSince`, возвращайте только пользователей, изменённых не раньше него, **в порядке
возрастания времени изменения**, и ставьте `watermark` страницы равным последнему времени изменения на ней. Если
`changedSince` равен null, перечисляйте всех пользователей источника. `nextCursor`, равный null, завершает
перечисление. Провайдер, который не умеет фильтровать по изменению, переопределяет `filtersByChange()` и возвращает
`false`; тогда каждая синхронизация - это обход. Таймауты, повторы и автоматический выключатель - забота клиента
([`rest-client`](../rest-client-spring-boot-starter)).

### Синхронизация и обход

| | Инкрементальная синхронизация | Обход |
|---|---|---|
| Задача (и блокировка запуска) | `ludwig-identity-<source>-sync` | `ludwig-identity-<source>-sweep` |
| Запрашивает | изменения с сохранённой водяной отметки | всех пользователей |
| Фиксирует | изменения каждой страницы **и** её водяную отметку вместе, поэтому остановленная синхронизация продолжается после последней зафиксированной страницы | изменения каждой страницы; перечисленные субъекты откладываются |
| В конце | записывает результат | отключает активных пользователей источника, которых не перечислил, - если порог `max-disable-ratio` проекции не прервёт обход, - и продвигает водяную отметку |
| При сбое | сохраняет водяную отметку и пользователей; записывает тип ошибки; следующий запуск повторяет | прерывается и никого не отключает |

Источник без водяной отметки обходится его первой синхронизацией. Обе задачи видны в `GET /admin/v1/jobs` и
запускаются сразу через `job.definition.run`; каждую выполняет не более одной реплики. Сбой одного источника никогда
не задерживает другой.

## Конфигурация

| Свойство | По умолчанию | Значение |
|---|---|---|
| `ludwig.identity.directory.sources.<source>.initial-delay` | `PT15S` | До первой синхронизации после старта |
| `ludwig.identity.directory.sources.<source>.sync-interval` | `PT5M` | Между инкрементальными синхронизациями |
| `ludwig.identity.directory.sources.<source>.sweep-interval` | `P1D` | Между обходами (и до первого) |
| `ludwig.identity.directory.sources.<source>.page-size` | `500` | Размер страницы, запрашиваемой у каталога (1-10000) |
| `ludwig.identity.directory.sources.<source>.lease` | `PT1H` | Аренда запуска; дольше самого долгого запуска |
| `ludwig.identity.directory.liquibase.enabled` | `true` | `false`, если changelog подключается из вашего мастер-файла |

Сам источник и его сопоставление ролей объявляются в `ludwig.identity.sources` (см. проекцию). Два каталога для
одного источника или каталог для необъявленного источника роняют старт.

## Развёртывание

- **Схема**: `db/changelog/identity-directory/identity-directory-changelog.xml`, отдельно или через включение:
  `identity_directory_state` - строка на источник с водяной отметкой и последними синхронизацией и обходом (время,
  результат, тип ошибки, страницы, счётчики). Никаких данных пользователей.
- **job-core**: задачам нужна его таблица блокировок запуска.

## Наблюдаемость

| Метрика | Теги | Значение |
|---|---|---|
| `ludwig_identity_directory_run_seconds` | `source`, `kind` (`sync`, `sweep`), `outcome` (`succeeded`, `failed`, `aborted`) | Длительность каждого запуска |

Сами изменения учитывает проекция (`ludwig_identity_changes_total`, `ludwig_identity_sweeps_total`).
`GET /admin/v1/identity/sources` показывает водяную отметку каждого каталога и последние синхронизацию и обход.

**Журнал.** `WARN ludwig-identity-<source>-sync failed: <exception>`; `INFO Swept identity source ...`. Сообщение
об ошибке провайдера не сохраняется (в нём могут быть данные пользователя, на которых он упал); сохраняется тип.

**Рекомендуемые оповещения:** `outcome="failed"` для источника в течение нескольких интервалов; любой обход с
`outcome="aborted"`; водяная отметка, которая перестала двигаться, хотя у провайдера есть изменения.

## Runbook

| Симптом | Вероятная причина | Что делать |
|---|---|---|
| Синхронизации источника падают | Провайдер недоступен, истекли учётные данные | `GET /admin/v1/identity/sources` - тип ошибки; исправьте клиент; следующий запуск повторит |
| Обход прерван | Пустое или усечённое перечисление либо реальное массовое увольнение | Проверьте провайдера; для реального увольнения поднимите `max-disable-ratio` проекции на один запуск |
| Удалённый пользователь всё ещё активен | Фильтр изменений провайдера не сообщает об удалениях | Следующий обход отключит его; запустите `ludwig-identity-<source>-sweep` сейчас через область задач |
| Изменения пропущены | Каталог вернул изменения не по порядку | Исправьте порядок в каталоге; обход исправит данные |

## Тестирование

`DirectorySyncIntegrationTest` против PostgreSQL с провайдером в памяти (первая синхронизация обходит, продолжение
после прерванной страницы, изоляция сбоев, отключение обходом, неудачный обход ничего не меняет, задачи под
блокировкой запуска, прогресс и метрики), `DirectoryValidationTest`, `DirectoryArchitectureTest`.

## Проектные решения

- **Один интерфейс для сервиса, всё остальное здесь**: провайдер - это чтение страниц, а не копия этого модуля.
- **Водяная отметка и страница в одной транзакции**: продолжение без повторного чтения и без пропусков.
- **Обходы по расписанию**: фильтр изменений редко сообщает об удалениях; полное перечисление сообщает, под защитой
  порога проекции.
- **Задачи job-core под блокировками запуска**: одна реплика на источник, видны и запускаются из области задач, без
  второго планировщика.
