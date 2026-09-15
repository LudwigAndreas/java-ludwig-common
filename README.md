# ludwig-common

> A modular Java 17 library for common parts 

## 📦 Overview

This project is a personal Java library built with **Java 17** and **Maven**, organized as a **multi-module Maven project**.  
Each module provides a focused set of utilities or functionality and can be reused independently in various Java projects.

## 📁 Project Structure

```text
ludwig-common/
│
├── pom.xml # Root POM – aggregates all modules
├── common-utils/ # common java utils
│ └── pom.xml
│
├── <module-2>/
│ └── pom.xml
│
├── <module-n>/
│ └── pom.xml
│
└── README.md
```

Each module has:
- Its own `pom.xml` and dependencies
- A well-defined and isolated purpose
- No unnecessary coupling with other modules (unless explicitly required)

## 📚 Modules

| Module Name    | Description                                     |
|----------------|-------------------------------------------------|
| `common-utils` | General-purpose helper functions and utilities  |
| [`odata-filter-spring-boot-starter`](odata-filter-spring-boot-starter/README.md) | Enterprise-ready OData `$filter`/`$top`/`$skip`/`$orderby` support for Spring Boot + Spring Data JPA REST APIs |
| [`db-core`](db-core/README.md) | Base entity classes, auditing, soft delete, exceptions and QueryDSL/Spring Data JPA utilities for Java 17 + Postgres + Spring Boot services |
| [`outbox-spring-boot-starter`](outbox-spring-boot-starter/README.md) | Enterprise-ready transactional outbox for Java 17 + Postgres + Spring Boot: `FOR UPDATE SKIP LOCKED` polling, retry/backoff, dead-letter handling, ordering, idempotency, Kafka/REST dispatch with multi-destination routing, metrics and audit |
| [`security-spring-boot-starter`](security-spring-boot-starter/README.md) ([ru](security-spring-boot-starter/README.ru.md)) | Enterprise-ready authentication and authorization for Spring Boot microservices behind an nginx/Envoy edge: one principal for browser users (session cookie exchanged for a JWT at the edge), mTLS partners (Envoy `x-forwarded-client-cert`) and peer services; roles resolved per service instead of from token claims; data-level authorization compiled into type-safe QueryDSL predicates plus a single-object `PermissionEvaluator`; localized RFC 7807 401/403, audit trail and metrics |
| [`identity-projection-spring-boot-starter`](identity-projection-spring-boot-starter/README.md) ([ru](identity-projection-spring-boot-starter/README.ru.md)) | Local projection of the OIDC provider's Kafka user stream and the database-backed `AuthorityResolver`/`DataScopeProvider`/`PartnerIdentityResolver` that read from it: idempotent, order-tolerant consumption, users/roles/partner registry/time-boxed data grants in Postgres, authority-cache eviction on change |
| [`hot-reload-spring-boot-starter`](hot-reload-spring-boot-starter/README.md) | Enterprise-ready hot reload for Java 17 + Spring Boot: typed/validated configuration, live-reloading property/YAML files and FreeMarker templates, HashiCorp Vault secrets (KV polling, lease-renewed dynamic secrets, Kubernetes auth), environment variables always able to override a reloaded value |
| [`crud-service-example`](crud-service-example/README.md) | Reference CRUD microservice built on the modules above: three model layers (DTO/domain/entity) wired by MapStruct, Lombok instead of boilerplate, compile-time-checked QueryDSL-JPA queries only, OData search, i18n validation and RFC 7807 errors, transactional outbox events |

[//]: # (| `kafka-tools`  | Kafka-related producers, consumers, and helpers |)

[//]: # (| `json-support` | JSON &#40;de&#41;serialization helpers using Jackson    |)

[//]: # (| `grpc-client`  | gRPC client utilities and wrappers              |)
[//]: # (| *...add more*  |                                                 |)

## 🚀 Getting Started

### Prerequisites

- Java 17+
- Maven 3.6+

### Cloning the Project

```bash
git clone https://github.com/LudwigAndreas/java-ludwig-common.git
cd java-ludwig-common 
```

### Building the Library

```bash
mvn clean install
```

This will compile and install all modules into your local Maven repository.

### Using a Module in Your Project

To use a module (e.g., common-utils) in your Maven project:

```xml
<dependency>
    <groupId>ru.ludwigandreas</groupId>
    <artifactId>common-utils</artifactId>
    <version>1.0.0</version>
</dependency>
```

## Testing

Each module contains its own test suite. To run all tests:

```bash
mvn test
```

## Documentation

Each module contains JavaDoc comments. You can generate documentation with:

```bash
mvn javadoc:javadoc
```

For module-specific docs, navigate to the module directory and run the same command.

## Contributing

This library is currently maintained as a personal toolkit. If you want to contribute or suggest improvements:

- Fork the repo
- Create a feature branch (feature/xyz)
- Open a pull request

> Guidelines: follow clean code practices and keep modules focused.

## License

This project is licensed under the MIT License – see the LICENSE file for details.

## Author

Ludwig Andreas

[GitHub]() • [LinkedIn]()