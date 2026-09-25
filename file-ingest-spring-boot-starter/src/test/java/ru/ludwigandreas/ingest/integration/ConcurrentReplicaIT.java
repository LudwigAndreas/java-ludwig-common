package ru.ludwigandreas.ingest.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ru.ludwigandreas.ingest.api.IngestRunStatus;
import ru.ludwigandreas.ingest.engine.IngestPass;

/**
 * Two replicas offered the same object produce exactly one run.
 *
 * <h2>Two mechanisms, and the test needs both to be doing their job</h2>
 *
 * <p>The lock is what stops two replicas doing the work at the same time; the identity constraint is
 * what stops the work being done twice at all, including across a lock that expired because an
 * instance died. Neither replaces the other, and this test exercises them together: the two threads
 * start from a barrier, so one takes the lock and the other finds it held, and then the loser's
 * second attempt - had it made one - would meet the constraint.
 *
 * <p>Two threads in one JVM rather than two processes, deliberately. The lock is a database row and
 * the constraint is a database constraint, so both behave identically whichever process asks; running
 * two JVMs would add a great deal of machinery to reach the same assertion, and the thing under test
 * would be no more exercised than it is here.
 */
class ConcurrentReplicaIT extends FileIngestTestBase {

    @Autowired
    private IngestPass pass;

    @Test
    @DisplayName("two replicas racing the same object produce exactly one run and one set of rows")
    void exactlyOneRunWins() throws Exception {
        put(dropKey("catalogue-race.csv"), """
                SKU-1,widget,100
                SKU-2,gadget,200
                SKU-3,gizmo,300
                """);

        CyclicBarrier startTogether = new CyclicBarrier(2);
        List<Callable<Boolean>> replicas = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            replicas.add(() -> {
                startTogether.await(10, TimeUnit.SECONDS);
                return pass.runOnce(CatalogueIngest.TASK);
            });
        }

        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Boolean> outcomes = new ArrayList<>();
        try {
            for (Future<Boolean> result : pool.invokeAll(replicas)) {
                outcomes.add(result.get(30, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(outcomes)
                .as("exactly one replica may hold the lock; the other must report that it did not run")
                .containsExactlyInAnyOrder(true, false);
        assertThat(jdbc().queryForObject("SELECT count(*) FROM file_ingest_run", Long.class))
                .as("one run, guaranteed by the unique constraint on (container, object_key,"
                        + " content_identity) rather than by a flag somebody remembers to check")
                .isEqualTo(1);
        assertThat(jdbc().queryForObject("SELECT status FROM file_ingest_run", String.class))
                .isEqualTo(IngestRunStatus.COMPLETED.name());
        assertThat(jdbc().queryForObject("SELECT count(*) FROM catalogue", Long.class)).isEqualTo(3);
        assertThat(jdbc().queryForObject("SELECT count(*) FROM staging_catalogue", Long.class))
                .as("and no record staged twice")
                .isEqualTo(3);
    }

    @Test
    @DisplayName("a second pass after the first completed does no work at all")
    void theLoserFindsTheWorkAlreadyDone() {
        put(dropKey("catalogue-race2.csv"), "SKU-1,widget,100\n");
        pass.runOnce(CatalogueIngest.TASK);

        // What the loser of the race would do on its next scheduled pass: find a COMPLETED run for the
        // identity triple and stand down without opening the object.
        pass.runOnce(CatalogueIngest.TASK);

        assertThat(jdbc().queryForObject("SELECT count(*) FROM file_ingest_run", Long.class))
                .isEqualTo(1);
        assertThat(jdbc().queryForObject("SELECT records_read FROM file_ingest_run", Long.class))
                .isEqualTo(1);
    }
}
