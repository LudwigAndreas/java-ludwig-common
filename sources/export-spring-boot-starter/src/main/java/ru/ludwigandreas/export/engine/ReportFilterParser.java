package ru.ludwigandreas.export.engine;

import com.querydsl.core.types.Predicate;
import java.util.Optional;
import ru.ludwigandreas.export.api.ReportDefinition;

/**
 * Turns a requester's {@code $filter} expression into a predicate.
 *
 * <p>A seam over {@code odata-filter-spring-boot-starter}, which is an optional dependency: a service
 * that does not offer filtering should not have to carry a parser, and a service that does should not
 * get a second filter dialect written here. There is deliberately no parsing code in this module.
 *
 * <p>The default refuses any filter at all rather than ignoring one. A request whose filter was
 * silently dropped would produce a file covering far more data than was asked for - which for a
 * report is not a smaller failure than an error, it is a larger one.
 */
@FunctionalInterface
public interface ReportFilterParser {

    /**
     * Parses a filter.
     *
     * @param definition which report, whose declared filterable columns bound what is allowed
     * @param filter     the expression, never null or blank when this is called
     * @return the predicate
     */
    Optional<Predicate> parse(ReportDefinition<?, ?> definition, String filter);
}
