## ADDED Requirements

### Requirement: A module ships no fallback advice for the pipeline's absence
A module whose failures can reach a web response SHALL depend on `web-core-spring-boot-starter`
non-optionally and contribute its mappers and bundle unconditionally. It SHALL NOT ship a
`@RestControllerAdvice` for when that starter is absent.

#### Scenario: odata-filter in a servlet application
- **WHEN** a servlet application uses `odata-filter-spring-boot-starter`
- **THEN** its query errors are rendered by the shared pipeline through `ODataFilterProblemMapper`,
  and the context contains no advice of that module's own

#### Scenario: A module adds an advice of its own
- **WHEN** a class in `ru.ludwigandreas.odatafilter` is annotated `@RestControllerAdvice`
- **THEN** that module's architecture test fails the build
