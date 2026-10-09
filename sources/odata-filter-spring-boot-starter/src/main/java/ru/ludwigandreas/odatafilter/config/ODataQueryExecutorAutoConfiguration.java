package ru.ludwigandreas.odatafilter.config;

import com.querydsl.jpa.impl.JPAQueryFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import ru.ludwigandreas.odatafilter.core.ODataFilterService;
import ru.ludwigandreas.odatafilter.execution.ODataQueryExecutor;

/**
 * Registers {@link ODataQueryExecutor}, which is the only part of this module that touches the
 * database.
 *
 * <p>Separate from {@link ODataFilterAutoConfiguration} and conditional on a {@link JPAQueryFactory}
 * bean on purpose. {@link ODataFilterService} must keep working with no persistence unit at all -
 * {@code export-spring-boot-starter} parses a requester's filter on a worker thread with no
 * {@code EntityManager} and no HTTP request, and a batch job replaying a saved filter does the same.
 * Putting the executor in the core configuration would make a module that only wanted to parse a
 * filter fail to start for want of a bean it never asked about.
 */
@AutoConfiguration(after = ODataFilterAutoConfiguration.class)
@ConditionalOnClass(JPAQueryFactory.class)
@ConditionalOnBean({JPAQueryFactory.class, ODataFilterService.class})
public class ODataQueryExecutorAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ODataQueryExecutor odataQueryExecutor(JPAQueryFactory queryFactory, ODataFilterService filterService) {
        return new ODataQueryExecutor(queryFactory, filterService);
    }
}
