package ru.ludwigandreas.odatafilter.core;

import com.querydsl.core.types.Predicate;
import com.querydsl.core.types.dsl.Expressions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
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
import ru.ludwigandreas.odatafilter.policy.FilterPolicyRegistry;
import ru.ludwigandreas.odatafilter.querydsl.PredicateBuilder;
import ru.ludwigandreas.odatafilter.security.FilterPrincipalResolver;
import ru.ludwigandreas.odatafilter.validation.DepthValidator;
import ru.ludwigandreas.odatafilter.validation.FieldAccessValidator;
import ru.ludwigandreas.odatafilter.validation.FilterValidationContext;
import ru.ludwigandreas.odatafilter.validation.FilterValidator;

/**
 * Single entry point of the library: turns raw OData query-option strings into a validated,
 * policy-enforced, role-checked {@link ODataQuery}.
 *
 * <p>Call it from the layer that owns the entity - the repository. The OData property paths are
 * resolved against the JPA entity and the result is a QueryDSL {@link Predicate}, so both the
 * input vocabulary and the output type belong to the persistence layer; parsing anywhere above it
 * puts the entity in a service or controller signature and the query plan in the hands of a layer
 * that cannot run it. The service layer passes the caller's raw option strings down unparsed.
 *
 * <p>Order of operations, all of which can reject the request: raw-length guard, syntax parsing,
 * depth check, field/operator/role check ({@code @Filterable}), any registered
 * {@link FilterValidator} beans, page-size check, then translation to a QueryDSL predicate.
 */
public class ODataFilterService {

    private final ODataFilterProperties properties;
    private final FilterPolicyRegistry policyRegistry;
    private final PredicateBuilder predicateBuilder;
    private final FilterPrincipalResolver principalResolver;
    private final List<FilterValidator> customValidators;
    private final ApplicationEventPublisher eventPublisher;
    private final ODataFilterMetrics metrics;

    private final ODataFilterParser filterParser = new ODataFilterParser();
    private final ODataOrderByParser orderByParser = new ODataOrderByParser();

    public ODataFilterService(
            ODataFilterProperties properties,
            FilterPolicyRegistry policyRegistry,
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

    public <T> ODataQuery<T> parse(Class<T> entityType, String filter, Integer top, Integer skip, String orderBy) {
        long startNanos = System.nanoTime();
        try {
            ODataQuery<T> result = doParse(entityType, filter, top, skip, orderBy);
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
            Class<T> entityType, String filter, Integer top, Integer skip, String orderBy) {
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

        List<OrderByTerm> requestedOrderBy = orderByParser.parse(orderBy);
        FieldAccessValidator.validateOrderBy(policy, requestedOrderBy, callerRoles);

        int pageSize = resolvePageSize(policy, top);
        long offset = resolveOffset(skip);
        Sort sort = toSort(requestedOrderBy, policy.defaultOrderBy());
        Pageable pageable = new OffsetPageRequest(offset, pageSize, sort);

        if (eventPublisher != null) {
            eventPublisher.publishEvent(new FilterAppliedEvent(entityType, filter, predicate.toString(), callerRoles));
        }

        return new ODataQuery<>(predicate, pageable, filter);
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

    /**
     * The caller's ordering first, then the entity's {@code defaultOrderBy} for any path the caller
     * did not already name - so the server's tie-breaker keeps paging deterministic without ever
     * overriding what the client asked for.
     */
    private Sort toSort(List<OrderByTerm> requested, List<OrderByTerm> entityDefault) {
        List<Sort.Order> orders = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (OrderByTerm term : requested) {
            if (seen.add(term.propertyPath())) {
                orders.add(toOrder(term));
            }
        }
        for (OrderByTerm term : entityDefault) {
            if (seen.add(term.propertyPath())) {
                orders.add(toOrder(term));
            }
        }
        return orders.isEmpty() ? Sort.unsorted() : Sort.by(orders);
    }

    private Sort.Order toOrder(OrderByTerm term) {
        String jpaPath = term.propertyPath().replace('/', '.');
        return term.descending() ? Sort.Order.desc(jpaPath) : Sort.Order.asc(jpaPath);
    }
}
