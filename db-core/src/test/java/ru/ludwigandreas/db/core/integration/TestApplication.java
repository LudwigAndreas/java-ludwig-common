package ru.ludwigandreas.db.core.integration;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import ru.ludwigandreas.db.core.integration.testmodel.TestOrder;
import ru.ludwigandreas.db.core.repository.BaseRepositoryImpl;

@SpringBootApplication
@EntityScan(basePackageClasses = TestOrder.class)
@EnableJpaRepositories(basePackageClasses = TestOrder.class, repositoryBaseClass = BaseRepositoryImpl.class)
public class TestApplication {

    public static void main(String[] args) {
        SpringApplication.run(TestApplication.class, args);
    }
}
