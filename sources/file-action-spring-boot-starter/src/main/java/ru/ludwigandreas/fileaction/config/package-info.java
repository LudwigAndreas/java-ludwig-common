/**
 * The module's configuration surface and its wiring.
 *
 * <p>Three autoconfigurations, deliberately separate: {@code FileActionAutoConfiguration} wires the engine,
 * {@code FileActionPersistenceAutoConfiguration} registers the entities and repositories, and
 * {@code FileActionLiquibaseAutoConfiguration} applies the schema. The split is not cosmetic - persistence has to
 * be registered before anything injecting a repository is created, and the Liquibase bean has to be ordered after
 * Spring Boot's own or it suppresses the application's migrations.
 */
package ru.ludwigandreas.fileaction.config;
