package ru.ludwigandreas.example.catalog.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.ludwigandreas.example.catalog.repository.entity.ProductEntity;
import ru.ludwigandreas.example.catalog.repository.entity.QProductEntity;
import ru.ludwigandreas.security.data.DataAction;
import ru.ludwigandreas.security.data.DataScope;
import ru.ludwigandreas.security.data.DataScopeMapping;
import ru.ludwigandreas.security.data.DataScopeProvider;
import ru.ludwigandreas.security.data.ScopeDimension;

/**
 * The only security code this service writes: which columns carry which scope dimension for the one
 * resource it owns, and the one rule that configuration cannot express.
 *
 * <p>Everything else - who the caller is, where roles come from, how a 403 is rendered, how a scope is
 * turned into a predicate - is autoconfigured by {@code security-spring-boot-starter} and driven by
 * {@code ludwig.security.data.policies} in {@code application.yml}. What cannot be configured is this:
 * the module has no way to know that "the owner of a product" means its {@code createdBy} column, so the
 * service states it once, here, in compile-time-checked terms.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    /** The axis for "products I am named on", as opposed to "products I created". */
    static final ScopeDimension WATCHING = ScopeDimension.of("watching");

    private static final String RESOURCE_TYPE = "product";

    /**
     * Three kinds of binding, one per shape of rule this service actually has.
     *
     * <p>Each binds a query-time path and a load-time accessor together. The path is what the module ANDs
     * into every scoped query - the pre-filter that keeps paging and counts correct. The accessor is what
     * it calls to judge a single already-loaded product - the post-check that covers {@code findById} and
     * every other path a query predicate never sees. Declared together, they cannot drift apart, and
     * renaming either field breaks the build rather than silently un-scoping the catalog.
     */
    @Bean
    public DataScopeMapping<ProductEntity> productDataScopeMapping() {
        QProductEntity product = QProductEntity.productEntity;
        return DataScopeMapping.forResource(RESOURCE_TYPE, ProductEntity.class)
                // Ownership: one column, one value. db-core's auditing already fills created_by from the
                // authenticated principal's subject, which is exactly what an OWN policy compares against.
                .owner(product.createdBy, ProductEntity::getCreatedBy)
                .partner(product.supplierPartnerId, ProductEntity::getSupplierPartnerId)
                // Membership: one product, many watchers. `any()` becomes a correlated EXISTS, not a
                // join - a join would multiply the product by its watchers and inflate both the page and
                // its total.
                .bindCollection(WATCHING, product.watcherSubjects.any(), ProductEntity::getWatcherSubjects)
                .build();
    }

    /**
     * "A watcher sees every product they are named on."
     *
     * <p>In code rather than in {@code ludwig.security.data.policies} because the configured policy
     * language only knows how to compare a dimension against a value the principal already carries
     * (its subject, its tenant, its partner). This rule needs the caller's own subject as the value of a
     * <em>custom</em> axis, which is exactly the seam {@link DataScopeProvider} exists for.
     *
     * <p>It composes rather than replaces: the module unions every provider's answer, so a user who is
     * both an editor and a watcher sees the editor's products <em>and</em> the ones they watch. A
     * provider can only ever widen access - denial is expressed by no provider granting.
     */
    @Bean
    public DataScopeProvider watcherDataScopeProvider() {
        return (principal, resourceType, action) -> {
            boolean applies = RESOURCE_TYPE.equals(resourceType)
                    && DataAction.READ.equals(action)
                    && principal.hasRole("ROLE_CATALOG_WATCHER");
            return applies
                    ? DataScope.restrictedTo(WATCHING, principal.subject())
                    : DataScope.none();
        };
    }
}
