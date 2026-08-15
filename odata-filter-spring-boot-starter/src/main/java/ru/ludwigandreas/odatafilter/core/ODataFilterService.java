package ru.ludwigandreas.odatafilter.core;

import com.querydsl.core.types.Predicate;
import com.querydsl.core.types.dsl.Expressions;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import ru.ludwigandreas.odatafilter.ast.FilterNode;
import ru.ludwigandreas.odatafilter.audit.FilterAppliedEvent;
import ru.ludwigandreas.odatafilter.config.ODataFilterProperties;
import ru.ludwigandreas.odatafilter.exception.FilterSyntaxException;
import ru.ludwigandreas.odatafilter.exception.PageSizeExceededException;
import ru.ludwigandreas.odatafilter.metrics.ODataFilterMetrics;
import ru.ludwigandreas.odatafilter.parser.ODataFilterParser;
import ru.ludwigandreas.odatafilter.parser.ODataOrderByParser;
import ru.ludwigandreas.odatafilter.parser.OrderByTerm;
import ru.ludwigandreas.odatafilter.policy.EntityFilterPolicy;
import ru.ludwigandreas.odatafilter.querydsl.PredicateBuilder;
import ru.ludwigandreas.odatafilter.security.FilterPrincipalResolver;
import ru.ludwigandreas.odatafilter.validation.DepthValidator;
import ru.ludwigandreas.odatafilter.validation.FieldAccessValidator;
import ru.ludwigandreas.odatafilter.validation.FilterValidationContext;
import ru.ludwigandreas.odatafilter.validation.FilterValidator;

/**
 * Single entry point of the library: turns raw OData query-option strings into a validated,
 * policy-enforced, role-checked {@link ODataQuery}. This is what both the Spring MVC argument
 * resolver and any consumer wanting to call the library directly (e.g. from a GraphQL resolver,
 * a batch job replaying a saved filter, or a non-web service layer) should use.
 *
 * <p>Order of operations, all of which can reject the request: raw-length guard, syntax parsing,
 * depth check, field/operator/role check ({@code @Filterable}), any registered
 * {@link FilterValidator} beans, page-size check, then translation to a QueryDSL predicate.
 */
public class ODataFilterService {

    private final ODataFilterProperties properties;
    private final ru.ludwigandreas.odatafilter.policy.FilterPolicyRegistry policyRegistry;
    private final PredicateBuilder predicateBuilder;
    private final FilterPrincipalResolver principalResolver;
    private final List<FilterValidator> customValidators;
    private final ApplicationEventPublisher eventPublisher;
    private final ODataFilterMetrics metrics;

    private final ODataFilterParser filterParser = new ODataFilterParser();
    private final ODataOrderByParser orderByParser = new ODataOrderByParser();

    public ODataFilterService(
            ODataFilterProperties properties,
            ru.ludwigandreas.odatafilter.policy.FilterPolicyRegistry policyRegistry,
            PredicateBuilder predicateBuilder,
            FilterPrincipalResolver principalResolver,
            List<FilterValidator> customValidators,
            ApplicationEventPublisher eventPublisher,
            ODataFilterMetrics metrics) {
        this.properties = properties;
        this.policyRegistry = policyRegistry;
        this.predicateBuilder = predicateBuilder;
        this.principalResolver = principalResolver;
        this.customValidators = List.copyOf(customValidators);
        this.eventPublisher = eventPublisher;
        this.metrics = metrics;
    }

    public <T> ODataQuery<T> parse(
            Class<T> entityType, String filter, Integer top, Integer skip, String orderBy, Boolean count) {
        long startNanos = System.nanoTime();
        try {
            ODataQuery<T> result = doParse(entityType, filter, top, skip, orderBy, count);
            metrics.recordFilterApplied(entityType.getSimpleName());
            return result;
        } catch (RuntimeException e) {
            metrics.recordFilterRejected(entityType.getSimpleName(), e.getClass().getSimpleName());
            throw e;
        } finally {
            metrics.recordParseDuration(entityType.getSimpleName(), Duration.ofNanos(System.nanoTime() - startNanos));
        }
    }

    private <T> ODataQuery<T> doParse(
            Class<T> entityType, String filter, Integer top, Integer skip, String orderBy, Boolean count) {
        EntityFilterPolicy policy = policyRegistry.policyFor(entityType);
        Set<String> callerRoles = principalResolver.resolveRoles();

        Predicate predicate = Expressions.TRUE;
        if (filter != null && !filter.isBlank()) {
            if (filter.length() > properties.getMaxExpressionLength()) {
                throw new FilterSyntaxException(
                        "$filter exceeds the maximum allowed length of " + properties.getMaxExpressionLength());
            }
            FilterNode root = filterParser.parse(filter);
            DepthValidator.validate(root, policy.maxDepth());
            FieldAccessValidator.validate(policy, root, callerRoles);
            for (FilterValidator validator : customValidators) {
                validator.validate(new FilterValidationContext(entityType, filter, root, callerRoles));
            }
            predicate = predicateBuilder.build(policy, root);
        }

        List<OrderByTerm> orderByTerms = orderByParser.parse(orderBy);
        FieldAccessValidator.validateOrderBy(policy, orderByTerms, callerRoles);

        int pageSize = resolvePageSize(policy, top);
        long offset = resolveOffset(skip);
        Sort sort = toSort(orderByTerms);
        Pageable pageable = new OffsetPageRequest(offset, pageSize, sort);

        boolean effectiveCount = count == null || count;

        if (eventPublisher != null) {
            eventPublisher.publishEvent(new FilterAppliedEvent(entityType, filter, predicate.toString(), callerRoles));
        }

        return new ODataQuery<>(predicate, pageable, effectiveCount, filter);
    }

    private int resolvePageSize(EntityFilterPolicy policy, Integer top) {
        int requested = top != null ? top : policy.defaultPageSize();
        if (requested <= 0) {
            throw new FilterSyntaxException("$top must be a positive integer");
        }
        if (requested > policy.maxPageSize()) {
            if (properties.getPageSizeExceededStrategy() == ODataFilterProperties.PageSizeExceededStrategy.CLAMP) {
                return policy.maxPageSize();
            }
            throw new PageSizeExceededException(requested, policy.maxPageSize());
        }
        return requested;
    }

    private long resolveOffset(Integer skip) {
        if (skip == null) {
            return 0;
        }
        if (skip < 0) {
            throw new FilterSyntaxException("$skip must not be negative");
        }
        return skip;
    }

    private Sort toSort(List<OrderByTerm> terms) {
        if (terms.isEmpty()) {
            return Sort.unsorted();
        }
        List<Sort.Order> orders = terms.stream()
                .map(term -> {
                    String jpaPath = term.propertyPath().replace('/', '.');
                    return term.descending() ? Sort.Order.desc(jpaPath) : Sort.Order.asc(jpaPath);
                })
                .toList();
        return Sort.by(orders);
    }
}
