package ru.ludwigandreas.example.catalog.config;

import com.querydsl.jpa.impl.JPAQueryFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.ludwigandreas.example.catalog.client.SupplierDirectoryApi;
import ru.ludwigandreas.example.catalog.service.report.ProductRowSource;
import ru.ludwigandreas.example.catalog.service.report.SupplierEnricher;

/**
 * Wires the catalogue report's two collaborators, and nothing else.
 *
 * <p>Note what is absent, because it is the same observation {@link NotificationClientConfig} invites:
 * there is no scheduler, no thread pool, no temp directory, no writer, no sink and no controller here.
 * All of those are the export starter's, configured under {@code ludwig.export} and inert until a
 * {@code ReportDefinitionSource} bean exists. This service's whole contribution is a definition and the
 * two objects it is built from.
 *
 * <p>The row source and the enricher are beans rather than constructed inside the definition so that
 * each is independently testable and independently replaceable - and so that the enricher receives the
 * declarative {@link SupplierDirectoryApi} proxy the rest-client starter registered, rather than
 * opening anything of its own.
 */
@Configuration(proxyBeanMethods = false)
public class CatalogReportConfig {

    /**
     * The keyset-paginated base query.
     *
     * @param jpaQueryFactory the service's single factory, over a shared entity manager
     * @return the row source
     */
    @Bean
    public ProductRowSource productRowSource(JPAQueryFactory jpaQueryFactory) {
        return new ProductRowSource(jpaQueryFactory);
    }

    /**
     * The supplier directory join.
     *
     * @param suppliers the declarative client for the {@code suppliers} partner
     * @return the enricher
     */
    @Bean
    public SupplierEnricher supplierEnricher(SupplierDirectoryApi suppliers) {
        return new SupplierEnricher(suppliers);
    }
}
