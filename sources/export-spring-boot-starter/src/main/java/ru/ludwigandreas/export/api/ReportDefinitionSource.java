package ru.ludwigandreas.export.api;

import java.util.Collection;

/**
 * How a service hands its report definitions to the registry.
 *
 * <p>A bean per feature area, each returning the definitions that area owns:
 *
 * <pre>{@code
 * @Component
 * class CatalogReports implements ReportDefinitionSource {
 *     @Override
 *     public Collection<ReportDefinition<?, ?>> definitions() {
 *         return List.of(ORDERS, STOCK_LEVELS);
 *     }
 * }
 * }</pre>
 *
 * <p>Contributed as beans rather than discovered by scanning the classpath for
 * {@link ReportDefinition} constants, for the reason {@code SettingDefinitionSource} takes the same
 * approach: a scan would also find the definitions a shared library declared and this service
 * deliberately does not offer, and "which reports does this service publish" would become a
 * question about what happens to be on the classpath.
 *
 * <p>A source is consulted once, at startup. The registry is immutable afterwards.
 */
@FunctionalInterface
public interface ReportDefinitionSource {

    /**
     * The definitions this source contributes.
     *
     * @return the definitions; never null, and never containing null
     */
    Collection<ReportDefinition<?, ?>> definitions();
}
