# object-storage-spring-boot-starter

*[English](README.md) · **Русский***

Клиент бакетов платформы: один интерфейс `ObjectStore`, центральный метод которого - **чтение диапазона**,
ленивый постраничный листинг, один парсер для `s3://bucket/key` и `file:///path`, реализация на AWS SDK v2
(AWS, MinIO, Ceph RGW, LocalStack) и файловая реализация с настоящим чтением диапазонов - обе проверяются одним
контрактным тестом. Внутренних повторов нет; учётные данные - только из цепочки провайдеров SDK.

| | |
|---|---|
| Роль | starter |
| Зависит от (в репозитории) | [`web-core-spring-boot-starter`](../web-core-spring-boot-starter) |
| Используется | стартерами `export`, `file-action`, `file-ingest` |
| Поверхности | исходящие S3 / файловая система |

| Элемент | Что это |
|---|---|
| `ObjectStore` | head, open, **open диапазона**, ленивый постраничный list, put, copy, delete, exists |
| `ObjectUri` | Единственный парсер `s3://bucket/key` и `file:///path`; ни один метод не принимает bucket и key раздельно |
| `ByteRange` | HTTP-окно с включённым концом, преобразуемое из Java-длин с исключённым концом в одном месте |
| `StoredObject` / `ObjectSummary` / `PutOptions` | Что знают head, элемент листинга и put |
| `S3ObjectStore` | AWS SDK v2 |
| `FilesystemObjectStore` | Каталог с настоящим чтением диапазонов |
| Семейство `ObjectStoreException` | Четыре исхода, отрисовываются web-core |

## Использование

```xml
<dependency>
    <groupId>ru.ludwigandreas</groupId>
    <artifactId>object-storage-spring-boot-starter</artifactId>
</dependency>
```

AWS SDK опционален; сервис с хранилищем S3 добавляет его (версии из `ludwig-bom`):

```xml
<dependency>
    <groupId>software.amazon.awssdk</groupId>
    <artifactId>s3</artifactId>
</dependency>
<dependency>
    <groupId>software.amazon.awssdk</groupId>
    <artifactId>apache-client</artifactId>
</dependency>
<dependency>
    <groupId>software.amazon.awssdk</groupId>
    <artifactId>auth</artifactId>
</dependency>
```

### Чтение диапазона

`open(uri, ByteRange)` позволяет продолжить прерванное чтение файла в 1 GB с байта 800 M, не скачивая заново
800 MB, - именно это делает контрольные точки file-ingest осмысленными. S3 отправляет заголовок `Range`;
файловое хранилище позиционирует `SeekableByteChannel`. **Диапазон, начинающийся за концом, возвращает пустой
поток** в обеих реализациях (включая 416 от S3): именно такой диапазон запрашивает запуск, продолженный после
полного прочтения объекта.

### Ленивый листинг

```java
try (Stream<ObjectSummary> drops = store.list("s3://partner-drop/catalogue/")) {
    drops.filter(o -> o.name().endsWith(".csv.gz"))
         .findFirst()
         .ifPresent(this::ingest);
}
```

Страницы загружаются по требованию через continuation token S3. **Поток держит соединение между страницами и
должен быть закрыт** (try-with-resources).

### Файловое хранилище

Для ноутбуков без бакета, одноузловых развёртываний и собственных контрактных тестов модуля. Обе схемы
разрешаются под настроенным корнем:

- `file:///<root>/some/name` - путь уже должен лежать под корнем;
- `s3://bucket/key` → `<root>/bucket/key` - задача, настроенная на `s3://partner-drop/...`, без изменений работает
  с локальным каталогом.

Файл, записанный на экземпляре A, есть только на экземпляре A.

**Его etag - размер + время изменения (нс), а не хэш содержимого.** Исправленная перезаливка получает новый
etag; перезапись с сохранением времени изменения (`cp -p`, восстановление из архива) - **нет**, и file-ingest
пропустит её как дубликат. Используйте S3, если exactly-once должен переживать такие восстановления.

### Ошибки

`LocalizedException`, отрисовываемые web-core (собственного advice нет); тексты в
`i18n/ludwig-storage-messages[_ru].properties`.

| Код | Статус | Когда |
|---|---|---|
| `ludwig.storage.error.object-not-found` | 404 | По адресу ничего нет |
| `ludwig.storage.error.invalid-uri` | 400 | Не `s3://…` / `file:///…` или содержит `..` |
| `ludwig.storage.error.access-denied` | 502 | Хранилище отказало принципалу **этого сервиса**; не временно |
| `ludwig.storage.error.store-unavailable` | 503 | Всё остальное |

502, а не 403, для отказа в доступе: 403 сказал бы *вызывающему*, что недостаточно его учётных данных.

## Конфигурация

```yaml
ludwig:
  storage:
    enabled: true
    type: s3                       # или filesystem (по умолчанию)
    s3:
      region: us-east-1            # обязателен даже для не-AWS (его подписывает SigV4)
      endpoint: http://minio:9000  # не задаётся для AWS
      path-style-access: true      # нужен почти для любого self-hosted хранилища
      max-attempts: 2              # попыток SDK, включая первую
      connect-timeout: 5s
      socket-timeout: 60s          # пауза между байтами, а не длительность передачи
      connection-acquire-timeout: 10s
      max-connections: 50
      list-page-size: 0            # 0 = по умолчанию хранилища (1000 у S3)
      credentials:
        source: default            # или 'static' - только локальный MinIO/LocalStack
    filesystem:
      root: /var/lib/ludwig/objects
```

| Свойство | По умолчанию | Смысл |
|---|---|---|
| `ludwig.storage.enabled` | `true` | Главный выключатель |
| `ludwig.storage.type` | `filesystem` | `s3` или `filesystem` |
| `ludwig.storage.s3.region` | `us-east-1` | Подписывается SigV4, поэтому задавайте и для не-AWS хранилищ |
| `ludwig.storage.s3.endpoint` | не задан | Эндпоинт не-AWS |
| `ludwig.storage.s3.path-style-access` | `false` | `true` для self-hosted; неверное значение выглядит как ошибка DNS (`bucket.10.0.0.5`) |
| `ludwig.storage.s3.max-attempts` | `2` | Один повтор SDK: он нужен для редиректов региона и переподписи при расхождении часов; бюджет backoff - у вызывающего |
| `ludwig.storage.s3.connect-timeout` / `socket-timeout` / `connection-acquire-timeout` | `5s` / `60s` / `10s` | |
| `ludwig.storage.s3.max-connections` | `50` | Размер пула |
| `ludwig.storage.s3.list-page-size` | `0` | Размер страницы листинга |
| `ludwig.storage.s3.credentials.source` | `default` | `default` = цепочка провайдеров SDK; `static` пишет предупреждение |
| `ludwig.storage.filesystem.root` | временный каталог | Если задан, должен существовать (никогда не создаётся) |

## Развёртывание

- **Учётные данные** - из цепочки `DefaultCredentialsProvider` SDK: переменные окружения, web identity token
  Kubernetes (IRSA и аналоги), профиль, метаданные экземпляра. Ничего в YAML и POM.
  `credentials.source: static` - только для локальных MinIO/LocalStack.
- **Политика бакета**: принципалу сервиса нужны операции, которые используют его модули (get/head/list для
  ingest; put/delete для export; copy для архивирования).
- **Файловое хранилище**: `root` должен существовать, лежать на постоянном томе и бэкапиться; он не создаётся.
- **Зависимости**: `netty-nio-client` и `apache5-client` исключены из SDK (`apache5-client` требует httpclient5
  5.5 и падает с `NoClassDefFoundError` на 5.3.x, которым управляет Spring Boot). `apache-client` даёт пул и
  отдельный таймаут получения соединения.

## Наблюдаемость

**Логи.** `INFO` с временным корнем, если корень файлового хранилища не задан; `WARN` при статических учётных
данных.

**Метрики.** Собственных нет. Метрики AWS SDK не публикуются.

**Известные пробелы:** нет метрик и трасс операций хранилища (задержка, ошибки по исходам, переданные байты) и
нет health-индикатора доступности бакета. Медленный или отказывающий бакет виден только через метрики
вызывающего модуля (export, file-ingest) и проблемы 502/503. Закрыть пробел мог бы Micrometer
`ExecutionInterceptor` на S3-клиенте с тегами операции и исхода.

## Аудит

Не применяется: операции хранилища аудирует вызывающий модуль (запуски экспорта, загрузки, файловые
действия), который знает, кто действовал и зачем.

## Runbook

| Симптом | Вероятная причина | Что делать |
|---|---|---|
| `ludwig.storage.error.access-denied` (502) | Политика бакета / IAM-роль не разрешает операцию принципалу сервиса | Исправить политику; не временно - повтор не поможет |
| `store-unavailable` (503) | Эндпоинт недоступен, таймауты, троттлинг | Проверить сеть до эндпоинта, здоровье хранилища; `socket-timeout` для медленных передач |
| `UnknownHostException` для `bucket.<host>` | `path-style-access=false` для self-hosted хранилища | Выставить `path-style-access: true` |
| `NoClassDefFoundError` с упоминанием `TlsSocketStrategy` | `apache5-client` снова в classpath | Исключить `software.amazon.awssdk:apache5-client` |
| Старт падает: корень файлового хранилища не существует | Опечатка или том не смонтирован | Исправить путь или монтирование |
| Ingest пропустил исправленный файл (файловое хранилище) | Перезапись с сохранением времени оставила etag | Обновить время файла (touch) или использовать S3 |
| Пул соединений исчерпан | Незакрытые потоки `list` или слишком много одновременных передач | Закрывать потоки; поднять `max-connections` |

## Тестирование

- `mvn test`: `ObjectUriTest` (разбор и отказы), `ByteRangeTest` (арифметика включённых/исключённых границ).
- `mvn verify` (Docker): `ObjectStoreContractTest` выполняется дважды - `FilesystemObjectStoreIT` и
  `S3ObjectStoreIT` (LocalStack). Покрывает круговую запись/чтение, метаданные head, чтение диапазонов с
  проверкой **побайтно**, диапазон за концом, листинг через границу continuation token (малый
  `list-page-size`), copy, удаление отсутствующего объекта (дважды), open отсутствующего объекта и перезаливку
  того же имени и длины с новым etag.
- **LocalStack, а не MinIO**: образы MinIO нельзя скачать анонимно, значит нельзя закрепить по digest.
  Закрепление живёт только в `images.properties` модуля `test-support` (`LudwigTestImages.LOCALSTACK`).

## Проектные решения

- **Один клиент на платформу**, поэтому на вопросы «удаление отсутствующего объекта - успех?», «помещается ли
  листинг в память?» и «кто повторяет?» один ответ. Удаление отсутствующего объекта - успех.
- **Без повторов в хранилище**: у вызывающего свой бюджет backoff; вложенные бюджеты перемножаются.
- **`ObjectUri` везде**: одно место для разбора схем и отказа в path traversal.
- **Только синхронный клиент**: без пула потоков Netty в каждом сервисе, сохраняющем файл.
