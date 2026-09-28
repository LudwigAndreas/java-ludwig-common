package ru.ludwigandreas.example.catalog.config;

import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.orm.jpa.SharedEntityManagerCreator;

/**
 * The single {@link JPAQueryFactory} every repository query in this service is built with.
 *
 * <p>It is created over a <em>shared</em> entity manager: a proxy that delegates to whatever
 * transaction-bound persistence context the calling thread is in, which is what makes one
 * application-scoped factory bean safe to share.
 */
@Configuration(proxyBeanMethods = false)
public class QuerydslConfig {

    @Bean
    public JPAQueryFactory jpaQueryFactory(EntityManagerFactory entityManagerFactory) {
        return new JPAQueryFactory(SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory));
    }
}
