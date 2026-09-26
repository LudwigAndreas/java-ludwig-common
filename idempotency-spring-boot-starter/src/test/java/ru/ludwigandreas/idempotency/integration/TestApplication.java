package ru.ludwigandreas.idempotency.integration;

import java.time.Clock;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import ru.ludwigandreas.job.core.lock.JdbcRunLock;
import ru.ludwigandreas.job.core.lock.RunLock;
import javax.sql.DataSource;

/**
 * The application the integration suites run inside.
 *
 * <p>Carries no entities and no repositories of its own: the point is that the starter's
 * {@code @EntityScan} and {@code @EnableJpaRepositories} find the claim table without the application
 * scanning for it, which is what a real consumer relies on.
 *
 * <p>It does publish a {@link Clock}, which a real application usually does not. That is what lets the TTL
 * and lease cases move time instead of sleeping: a test that waited out a 24-hour window would not exist,
 * and one that waited out a one-second window would be the flakiest test in the repository.
 */
@SpringBootApplication
public class TestApplication {

    /** The clock every component in the context judges windows against. */
    @Bean
    public Clock clock() {
        return MutableClock.frozen();
    }

    /**
     * The platform's leased lock, over the same data source.
     *
     * <p>Published here rather than expected from an autoconfiguration because {@code job-core}'s own
     * autoconfiguration needs a {@code JobInstanceIdentity} and a handful of properties this suite has no
     * interest in; what the purge case needs is a real lock over a real row, which is this.
     *
     * @param dataSource the container's data source
     * @return the lock
     */
    @Bean
    public RunLock runLock(DataSource dataSource) {
        return new JdbcRunLock(dataSource, "test-replica");
    }
}
