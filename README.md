# ludwig-common

> A modular Java 17 library for common parts 

## 📦 Overview

This project is a personal Java library built with **Java 17** and **Maven**, organized as a **multi-module Maven project**.  
Each module provides a focused set of utilities or functionality and can be reused independently in various Java projects.

## 📁 Project Structure

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

Each module has:
- Its own `pom.xml` and dependencies
- A well-defined and isolated purpose
- No unnecessary coupling with other modules (unless explicitly required)

## 📚 Modules

| Module Name    | Description                                     |
|----------------|-------------------------------------------------|
| `common-utils` | General-purpose helper functions and utilities  |

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