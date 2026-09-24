package ru.ludwigandreas.reconciliation.integration;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import ru.ludwigandreas.db.core.repository.BaseRepositoryImpl;
import ru.ludwigandreas.reconciliation.integration.testmodel.StubPartner;

/**
 * The application the integration tests run against.
 *
 * <p>Declares its own {@code @EntityScan} and {@code @EnableJpaRepositories} exactly as a consuming
 * service must: {@code db-core} requires every {@code BaseRepository} subinterface to be created with
 * {@code repositoryBaseClass = BaseRepositoryImpl.class}, and a library that declares either
 * annotation turns off Spring Boot's auto-detected scanning for the application's own types. Pointing
 * both at this package is what a real consumer writes, so the tests exercise that arrangement rather
 * than a simplification of it - the module's own packages are covered by its autoconfiguration, and
 * the two declarations accumulate.
 */
@SpringBootApplication
@EntityScan(basePackageClasses = ReconciliationTestApplication.class)
@EnableJpaRepositories(basePackageClasses = ReconciliationTestApplication.class,
        repositoryBaseClass = BaseRepositoryImpl.class)
public class ReconciliationTestApplication {

    /**
     * Entry point, so the class is a usable application rather than only a test fixture.
     *
     * @param args command-line arguments
     */
    public static void main(String[] args) {
        SpringApplication.run(ReconciliationTestApplication.class, args);
    }

    /** The in-process partner the tasks talk to. */
    @Bean
    public StubPartner stubPartner() {
        return new StubPartner();
    }

    // The three task beans are NOT declared here. @ReconciliationTask is meta-annotated @Component, so
    // component scanning creates them - and declaring them again would give each task two beans, which
    // is exactly what the startup validator refuses. That duplication is easy to write by accident,
    // and this fixture is the shape a real service has.
}
