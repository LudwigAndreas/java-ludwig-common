# ludwig-grpc-api-parent

*[English](README.md) · **Русский***

Родительский POM каждого модуля контракта `<service>-grpc-api`. Поставляет зависимости gRPC и protobuf,
[`grpc-core`](../../sources/grpc-core) (чтобы контракт мог импортировать `ludwig/operation/v1/operation.proto`) и
генерацию кода protobuf, поэтому POM модуля контракта - это родитель, `artifactId`, имя и описание.

| | |
|---|---|
| Роль | родитель контрактов (POM) |
| Зависит от (в репозитории) | [`grpc-core`](../../sources/grpc-core); родитель - корень реактора `common`; импортирует `ludwig-bom` |
| Используется | каждым модулем `*-grpc-api` (сейчас [`catalog-grpc-api`](../../sources/catalog-grpc-api)) |
| Поверхности | нет |

## Использование

Создайте `sources/<service>-grpc-api/pom.xml`:

```xml
<parent>
    <groupId>ru.ludwigandreas</groupId>
    <artifactId>ludwig-grpc-api-parent</artifactId>
    <version>${revision}</version>
    <!-- Обязательно: без него Maven молча берёт устаревшего родителя из ~/.m2. -->
    <relativePath>../../build/ludwig-grpc-api-parent/pom.xml</relativePath>
</parent>
<artifactId>billing-grpc-api</artifactId>
<name>Billing gRPC API</name>
<description>The gRPC contract of billing.</description>
```

Положите `.proto` в `src/main/proto/ludwig/<service>/v<N>/`. Затем зарегистрируйте модуль:

1. `<module>sources/<service>-grpc-api</module>` в корневом `pom.xml`;
2. запись `<dependency>` в `build/ludwig-bom/pom.xml`;
3. зависимость в `build/jacoco-aggregate/pom.xml`;
4. `scripts/manifest.sh build`.

Правила proto (package, `java_package`, `java_multiple_files`, `idempotency_level` на каждом rpc) описаны в
[grpc-core](../../sources/grpc-core/README.ru.md#модуль-контракта) и проверяются `scripts/check_protos.sh`.

**Модуль контракта не объявляет зависимостей, плагинов и `dependencyManagement`** и не содержит Java (capability
`pom-topology`). Если кажется, что что-то из этого нужно, - это уже не только контракт.

## Конфигурация

| Что даёт родитель | Значение |
|---|---|
| Зависимости | `io.grpc:grpc-protobuf`, `io.grpc:grpc-stub`, `com.google.protobuf:protobuf-java`, `ru.ludwigandreas:grpc-core` (версии из `ludwig-bom`) |
| Генерация | профиль `ludwig-grpc-contract`, активен при наличии `${basedir}/src/main/proto`; запускает `io.github.ascopes:protobuf-maven-plugin` |
| Решения сборки | собственных нет: наследуются от корня реактора, как у любой библиотеки (Checkstyle, JaCoCo, revapi, enforcer) |

`protoc` - Maven-артефакт версии `protoc.version` (корневой POM), которая должна совпадать с `protobuf.version` в
`ludwig-bom`.

## Развёртывание

Публикуется с каждым релизом, как остальные POM библиотек. Jar-ы контрактов используются серверами и клиентами
как зависимости.

## Наблюдаемость

Не применяется (артефакт сборки).

## Аудит

Не применяется.

## Runbook

| Симптом | Вероятная причина | Что делать |
|---|---|---|
| `manifest.sh layout` падает с именем модуля `-grpc-api` | Не тот родитель, нет регистрации, есть Java или нет `.proto` | Исправить, как сказано в сообщении |
| Jar контракта пуст | Нет `src/main/proto` (профиль генерации неактивен) | Добавить proto; `layout` такой модуль и так не пропустит |
| `check_protos.sh` сообщает о расхождении версий | `protoc.version` ≠ `protobuf.version` | Выровнять свойство корневого POM с BOM |

## Тестирование

Собственных тестов нет; `scripts/manifest.sh layout` и `scripts/check_protos.sh` (оба в гейте) проверяют каждый
модуль, использующий этот родитель.

## Проектные решения

- **Родитель, а не скрипт-генератор**: то, что нужно каждому контракту, сказано один раз и наследуется;
  оставшиеся строки модуля проверяет гейт.
- **Родитель - корень реактора**: контракт - публикуемая библиотека и получает ровно сборку библиотеки.
- **`grpc-core` всегда на пути**: он кладёт `operation.proto` на путь импорта, и каждый потребитель уже зависит от
  него через стартер.
