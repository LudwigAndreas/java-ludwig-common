package ru.ludwigandreas.odatafilter.config;

import jakarta.persistence.EntityManagerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import ru.ludwigandreas.odatafilter.metadata.FilterMetadataRegistry;
import ru.ludwigandreas.odatafilter.metrics.ODataFilterMetrics;
import ru.ludwigandreas.odatafilter.policy.FilterPolicyRegistry;
import ru.ludwigandreas.odatafilter.properties.ODataFilterProperties;
import ru.ludwigandreas.odatafilter.security.FilterPrincipalResolver;
import ru.ludwigandreas.odatafilter.web.FilterMetadataController;

/**
 * Registers filter-policy discovery - but only when a deployment has asked for it.
 *
 * <h2>Four conditions, each load-bearing</h2>
 *
 * <ul>
 *   <li><b>A configured base path.</b> Unset means the endpoint does not exist, rather than existing and
 *       refusing. A metadata document maps the queryable surface, which is useful to a client and equally
 *       useful to someone enumerating it, so the surface should not be there until somebody decided it
 *       should. An endpoint that answered 403 would still confirm the feature and still be one
 *       misconfiguration away from answering.
 *   <li><b>{@code enabled}, defaulting to true.</b> Separate from the path on purpose - see
 *       {@link MountedConfiguration}.
 *   <li><b>An {@link EntityManagerFactory} bean.</b> The registry is built from the JPA metamodel, and an
 *       entity with no persistence unit has no endpoint to describe. This is also what keeps
 *       {@code ODataFilterService} usable with no {@code EntityManager} at all, which
 *       {@code export-spring-boot-starter} relies on.
 *   <li><b>A servlet web application.</b> Pulling this starter into a batch job must not drag Spring MVC in.
 * </ul>
 *
 * <p>Each bean is additionally {@code @ConditionalOnMissingBean}, so an application that wants to serve the
 * document differently - a different shape, an index endpoint, its own authorization - replaces the
 * controller and keeps the registry.
 */
@AutoConfiguration(after = ODataFilterAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass({WebMvcConfigurer.class, EntityManagerFactory.class})
@ConditionalOnBean(EntityManagerFactory.class)
@ConditionalOnProperty(prefix = "odata.filter.metadata", name = "enabled", matchIfMissing = true)
public class ODataFilterMetadataAutoConfiguration {

    /**
     * The beans, all of which need a configured base path.
     *
     * <p>That is a separate condition from {@code enabled} because the two mean different things: no path
     * means the deployment never asked for discovery, while {@code enabled=false} means it asked and then
     * turned it off - typically in one environment, without editing the path out of a shared configuration
     * file, which is how a path gets lost and silently not restored.
     *
     * <p>A nested class because {@code @ConditionalOnProperty} is not repeatable, so two property conditions
     * cannot sit on one declaration.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "odata.filter.metadata", name = "base-path")
    public static class MountedConfiguration {

        private static final Logger log = LoggerFactory.getLogger(MountedConfiguration.class);

        /**
         * Indexes the published entities, and says on startup what is exposed.
         *
         * <p>The log line exists because "nothing is published" and "discovery is not mounted" look identical
         * from outside and have different fixes: an entity missing its {@code metadataName}, or a missing
         * base path.
         */
        @Bean
        @ConditionalOnMissingBean
        public FilterMetadataRegistry filterMetadataRegistry(
                EntityManagerFactory entityManagerFactory, ODataFilterProperties properties) {
            FilterMetadataRegistry registry = new FilterMetadataRegistry(entityManagerFactory.getMetamodel());
            log.info("Filter-policy discovery mounted at {}; {} entit{} published: {}",
                    properties.getMetadata().getBasePath(), registry.size(),
                    registry.size() == 1 ? "y" : "ies", registry.publishedNames());
            return registry;
        }

        @Bean
        @ConditionalOnMissingBean
        public FilterMetadataController filterMetadataController(
                FilterMetadataRegistry metadataRegistry,
                FilterPolicyRegistry policyRegistry,
                FilterPrincipalResolver principalResolver,
                ODataFilterMetrics metrics) {
            return new FilterMetadataController(metadataRegistry, policyRegistry, principalResolver, metrics);
        }

        /**
         * Mounts the controller under the configured base path.
         *
         * <p>A path prefix rather than a literal in the controller's {@code @RequestMapping}, because the
         * path is the deployment's choice: a service already serving {@code /api/v1} wants discovery beside
         * its resources, and a starter cannot know where that is.
         */
        @Bean
        @ConditionalOnBean(FilterMetadataController.class)
        public WebMvcConfigurer odataFilterMetadataPathConfigurer(ODataFilterProperties properties) {
            String basePath = properties.getMetadata().getBasePath();
            return new WebMvcConfigurer() {
                @Override
                public void configurePathMatch(PathMatchConfigurer configurer) {
                    configurer.addPathPrefix(basePath, FilterMetadataController.class::equals);
                }
            };
        }
    }
}
