package ru.ludwigandreas.testsupport.local.fixture;

import java.util.List;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Makes {@link SeedFixtures} available over whatever fixtures the context holds.
 *
 * <p>The local application imports this itself. An integration test imports it next to the fixtures
 * it wants:
 *
 * <pre>{@code
 * @SpringBootTest
 * @Import({SeedFixtureConfiguration.class, SupplierFixture.class, ProductFixture.class})
 * class CatalogueIT {
 *
 *     @Autowired
 *     SeedFixtures fixtures;
 *
 *     @BeforeEach
 *     void seed() {
 *         fixtures.reset();
 *     }
 * }
 * }</pre>
 */
@Configuration(proxyBeanMethods = false)
public class SeedFixtureConfiguration {

    /**
     * The runner over every {@link SeedFixture} bean.
     *
     * @param fixtures           the registered fixtures
     * @param dataSource         the application's database, when it has one
     * @param transactionManager the application's transaction manager, when it has one
     * @return the runner
     */
    @Bean
    public SeedFixtures ludwigSeedFixtures(List<SeedFixture> fixtures,
                                           ObjectProvider<DataSource> dataSource,
                                           ObjectProvider<PlatformTransactionManager> transactionManager) {
        return new SeedFixtures(fixtures, dataSource.getIfAvailable(), transactionManager.getIfAvailable());
    }
}
