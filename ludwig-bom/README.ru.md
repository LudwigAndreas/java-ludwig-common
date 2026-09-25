# ludwig-bom

*[English](README.md) · **Русский***

Реестр версий платформы: каждый модуль `ru.ludwigandreas` в одной версии плюс каждая сторонняя версия,
которую платформа закрепляет поверх того, чем управляет Spring Boot. Импортируйте его — и не называйте
версий вовсе.

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>ru.ludwigandreas</groupId>
            <artifactId>ludwig-bom</artifactId>
            <version>1.1.0</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <dependency>
        <groupId>ru.ludwigandreas</groupId>
        <artifactId>db-core</artifactId>
    </dependency>
    <dependency>
        <groupId>com.querydsl</groupId>
        <artifactId>querydsl-jpa</artifactId>
        <classifier>jakarta</classifier>
    </dependency>
</dependencies>
```

Сервис, наследующий [`ludwig-service-parent`](../ludwig-service-parent/README.ru.md), получает этот
BOM автоматически и **не должен импортировать его второй раз**.

---

## Что это такое и чем намеренно не является

`<packaging>pom</packaging>`, один блок `<dependencyManagement>` и больше ничего. **Ни `<build>`, ни
`<plugins>`, ни `<modules>`.** Этот артефакт отвечает ровно на один вопрос — **какая версия**, — и
потребитель, импортирующий его, не должен вместе с этим приобретать мнение о том, как что-либо
компилировать, тестировать или упаковывать. Для этого есть родитель, и разделены они как раз затем,
чтобы сервис мог взять одно без другого.

**У публикуемого POM нет элемента `<parent>`.** Внутри репозитория он есть — исключительно чтобы
унаследовать общие свойства реактора и настроенное здесь выполнение flatten; `flatten-maven-plugin`
работает в режиме `bom`, поэтому POM, устанавливаемый и публикуемый под этими координатами, не имеет
родителя, а каждая версия в нём — литерал. Это важно: потребитель, импортирующий BOM, не должен быть
вынужден разрешать `ru.ludwigandreas:common` из репозитория, которого у него может не быть.

---

## Что закреплено

### Собственные модули платформы

Все модули этого репозитория, все в `${project.version}` — которое во flattened-POM превращается в
литерал, так что потребитель никогда не видит неразрешённого выражения:

`db-core` · `web-core-spring-boot-starter` · `odata-filter-spring-boot-starter` ·
`outbox-spring-boot-starter` · `security-spring-boot-starter` ·
`identity-projection-spring-boot-starter` · `hot-reload-spring-boot-starter` ·
`observability-spring-boot-starter` · `user-settings-spring-boot-starter` · `architecture-rules` ·
`checkstyle-rules`

### Сторонние версии, которыми Spring Boot не управляет или управляет иначе

| Область | Артефакты |
|---|---|
| Кодогенерация | Lombok, `lombok-mapstruct-binding`, MapStruct, QueryDSL (`core`, `jpa:jakarta`, `apt:jakarta`), `jakarta.persistence-api` |
| OData | Olingo `odata-server-api`, `odata-server-core` |
| Устойчивость и конфигурация | Resilience4j, Spring Vault, FreeMarker |
| Обмен сообщениями | `spring-kafka`, `spring-kafka-test` |
| Наблюдаемость | `opentelemetry-api-incubator` |
| Документация API | springdoc OpenAPI |
| Тестирование | Testcontainers (вложенным импортом BOM), ArchUnit, GreenMail, `jakarta.el` |

Две записи снабжены предупреждением в POM, и его стоит повторить здесь.

**Lombok закреплён намеренно выше версии Spring Boot.** Управляемая Boot-ом 1.18.34 падает на текущих
патч-релизах JDK 17 с `ExceptionInInitializerError: com.sun.tools.javac.code.TypeTag :: UNKNOWN` —
Lombok лезет во внутренности javac, и ему нужна сборка, знающая компилятор, под которым он работает.
Не «выравнивайте» обратно.

**`opentelemetry-api-incubator` обязан следовать за `opentelemetry.version`, которой управляет Spring
Boot.** Incubator-API на минорную версию впереди SDK, для которого он инкубируется, — это
`NoSuchMethodError`, ждущий своего часа.

---

## Порядок несущий

Maven разрешает управляемую версию, беря **первую** подходящую запись в эффективном
`dependencyManagement`, а импортированный BOM разворачивается **на месте**, в позиции своего элемента
`<dependency>`. Поэтому файл устроен так:

1. собственные модули платформы и её явные сторонние переопределения — **первыми, и потому
   побеждают**;
2. `testcontainers-bom` и `spring-boot-dependencies` — **последними**, заполняют всё остальное.

Именно поэтому переопределениям платформы (Lombok, Testcontainers, FreeMarker) не нужен трюк с
property-override, который работает только при наследовании `spring-boot-starter-parent`. Этот BOM его
не наследует, поэтому переопределения выражены записями.

Перенос импорта выше явной записи молча меняет то, какая версия побеждает. Редактируя файл, сохраняйте
порядок разделов.

---

## Как переопределить версию в сервисе

Одного переобъявления свойства **недостаточно**:

```xml
<!-- Не делает ничего. -->
<properties>
    <mapstruct.version>1.6.0</mapstruct.version>
</properties>
```

Свойство, заданное у потребителя, не дотягивается до уже проинтерполированных записей импортированного
BOM: к моменту импорта `${mapstruct.version}` уже разрешено в то, что сказал этот файл.
Переопределяйте оба:

```xml
<properties>
    <mapstruct.version>1.6.0</mapstruct.version>
</properties>

<dependencies>
    <dependency>
        <groupId>org.mapstruct</groupId>
        <artifactId>mapstruct</artifactId>
        <version>${mapstruct.version}</version>
    </dependency>
</dependencies>
```

Либо объявите собственную запись `dependencyManagement` **до** импорта — побеждает первая.

---

## Артефакты с классификатором

Управляемая запись ключуется по `groupId:artifactId:type:classifier`, поэтому Jakarta-варианты
QueryDSL управляются отдельно:

```xml
<dependency>
    <groupId>com.querydsl</groupId>
    <artifactId>querydsl-jpa</artifactId>
    <classifier>jakarta</classifier>
</dependency>
```

Без записей с классификатором `querydsl-jpa:jakarta` оказался бы неуправляемым, и версию пришлось бы
повторять в каждом месте использования — ровно тот разброд, ради предотвращения которого этот файл и
существует.

---

## Два артефакта, один релизный поезд

`ludwig-bom` и `ludwig-service-parent` разделены намеренно. Они тянут в противоположные стороны: «все
версии в одном месте» хочет единый BOM, за которым все следят, а «один родитель для каждого сервиса»
означает, что изменение размером с метку jib вынуждает поднять версию родителя, которую каждому
сервису рано или поздно придётся принять.

Разделение позволяет сервису взять **новый BOM без новой конфигурации сборки** или наоборот — см.
[README родителя](../ludwig-service-parent/README.ru.md#взять-новый-bom-без-нового-build-конфига).

---

## Как это проверяется

Главное свойство — что BOM разрешается, когда больше ничего нет, — сборкой реактора не проверяется,
потому что внутри реактора есть всё. Оно проверяется разрешением опубликованного BOM из пустого
проекта вне этого репозитория. Если вы меняете режим flatten или добавляете выражение, зависящее от
`<parent>`, сделайте эту проверку руками.
