package ru.ludwigandreas.odatafilter.integration;

import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
@EntityScan(basePackageClasses = ru.ludwigandreas.odatafilter.testmodel.Employee.class)
public class TestApplication {

    public static void main(String[] args) {
        SpringApplication.run(TestApplication.class, args);
    }

    /**
     * The one bean a consuming application must supply for {@code ODataQueryExecutor} to be wired up.
     * Its auto-configuration is conditional on this, so a module that only parses filters and has no
     * persistence unit still starts.
     */
    @Bean
    JPAQueryFactory jpaQueryFactory(EntityManager entityManager) {
        return new JPAQueryFactory(entityManager);
    }
}
