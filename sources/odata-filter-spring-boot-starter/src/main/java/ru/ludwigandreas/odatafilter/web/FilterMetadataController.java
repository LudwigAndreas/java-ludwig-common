package ru.ludwigandreas.odatafilter.web;

import java.util.Set;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import ru.ludwigandreas.odatafilter.exception.FilterMetadataNotPublishedException;
import ru.ludwigandreas.odatafilter.metadata.FilterMetadata;
import ru.ludwigandreas.odatafilter.metadata.FilterMetadataFactory;
import ru.ludwigandreas.odatafilter.metadata.FilterMetadataRegistry;
import ru.ludwigandreas.odatafilter.metrics.ODataFilterMetrics;
import ru.ludwigandreas.odatafilter.policy.FilterPolicyRegistry;
import ru.ludwigandreas.odatafilter.security.FilterPrincipalResolver;

/**
 * Serves one entity's filter policy to the caller asking for it.
 *
 * <p>Mounted only when {@code odata.filter.metadata.base-path} is set - the path comes from configuration
 * rather than from a {@code @RequestMapping} literal, which is why the mapping below is a bare
 * {@code @GetMapping} and the prefix is applied by the auto-configuration.
 *
 * <h2>It answers per caller, not per entity</h2>
 *
 * <p>The roles come from the same {@link FilterPrincipalResolver} the query path uses, and the document is
 * projected against them, so two callers asking the same question get different answers and neither learns
 * anything about the other's. A path this caller may not use is absent - not present and marked forbidden,
 * which would hand it a column name and a privilege name from an endpoint meant to tell it less.
 *
 * <h2>What it does not do</h2>
 *
 * <p>No caching. The policy is already memoized by {@link FilterPolicyRegistry}, so what remains per request
 * is a stream over a few dozen map entries. A cache here would have to be keyed on the caller's resolved
 * role set rather than on the entity - anything else serves an administrator's field list to an
 * unprivileged caller - and that means a {@code CachePurpose.SECURITY} declaration whose TTL is how long a
 * revoked role keeps seeing a field. Paying that correctness cost to save a map traversal is the wrong
 * trade; if a future change needs one anyway, it goes through {@code LudwigCacheRegistry} with that purpose
 * and a role-set key, never a {@code Caffeine} builder.
 *
 * <p>No authentication of its own. Whether this endpoint requires a role is the application's decision, made
 * the way it is made for every other endpoint; a starter does not decide a service's security.
 */
@RestController
public class FilterMetadataController {

    private final FilterMetadataRegistry metadataRegistry;
    private final FilterPolicyRegistry policyRegistry;
    private final FilterPrincipalResolver principalResolver;
    private final ODataFilterMetrics metrics;

    public FilterMetadataController(
            FilterMetadataRegistry metadataRegistry,
            FilterPolicyRegistry policyRegistry,
            FilterPrincipalResolver principalResolver,
            ODataFilterMetrics metrics) {
        this.metadataRegistry = metadataRegistry;
        this.policyRegistry = policyRegistry;
        this.principalResolver = principalResolver;
        this.metrics = metrics;
    }

    /**
     * The filter policy published under {@code entity}, as this caller may see it.
     *
     * @param entity the published name, from {@code @FilterPolicy(metadataName = ...)}
     * @return the document, carrying only what this caller may use
     * @throws FilterMetadataNotPublishedException if nothing publishes under that name - a 404, and the same
     *     answer for an entity that exists but publishes nothing
     */
    @GetMapping("/{entity}")
    public FilterMetadata metadata(@PathVariable String entity) {
        Class<?> entityType = metadataRegistry.entityFor(entity)
                .orElseThrow(() -> new FilterMetadataNotPublishedException(entity));
        Set<String> callerRoles = principalResolver.resolveRoles();
        FilterMetadata metadata =
                FilterMetadataFactory.of(entity, policyRegistry.policyFor(entityType), callerRoles);
        metrics.recordMetadataServed(entity);
        return metadata;
    }
}
