package ru.ludwigandreas.ingest.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ContextConfiguration;
import ru.ludwigandreas.ingest.api.IngestRunStatus;
import ru.ludwigandreas.ingest.engine.IngestPass;
import ru.ludwigandreas.storage.api.ByteRange;
import ru.ludwigandreas.storage.api.ObjectStore;
import ru.ludwigandreas.storage.api.ObjectSummary;
import ru.ludwigandreas.storage.api.PutOptions;
import ru.ludwigandreas.storage.api.StoredObject;
import ru.ludwigandreas.storage.s3.S3ObjectStore;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * The headline property: a run killed mid-file resumes and the target holds every record exactly once.
 *
 * <h2>How the kill is staged, and why it is not a {@code Thread.stop}</h2>
 *
 * <p>The interruption has to happen at a point the checkpoint invariant is actually exposed to -
 * between one committed batch and the next - and it has to be reproducible. So the store is wrapped in
 * a decorator that throws after a fixed number of bytes have been read. That is indistinguishable,
 * from the engine's point of view, from the network dropping: the read fails, the run is marked
 * {@code FAILED}, and the batches committed before the failure are in staging with a checkpoint that
 * accounts for exactly them.
 *
 * <p>Killing the JVM would be more literal and would prove less: the assertion that matters is about
 * what the database holds afterwards, and a test that could not then query the database would have to
 * assert it from outside, in a second process, which is a great deal of machinery to reach the same
 * conclusion.
 *
 * <h2>The third test is the one that makes the resume worth having</h2>
 *
 * <p>Resuming <em>correctly</em> is necessary and not sufficient: an implementation that re-read the
 * object from byte zero and skipped forward would pass the first two tests and would have thrown away
 * the entire reason {@code ObjectStore} has a ranged read. So the decorator records the ranges it was
 * asked for, and the third test asserts that the resumed run issued a <strong>ranged</strong> GET
 * starting at the committed offset - not a full one.
 */
@ContextConfiguration(classes = CheckpointResumeIT.FailingStoreConfiguration.class)
class CheckpointResumeIT extends FileIngestTestBase {

    /** Rows in the fixture: enough for several batches at the configured 100 per batch. */
    private static final int ROWS = 1000;

    @TempDir
    Path work;

    @Autowired
    private IngestPass pass;

    @Autowired
    private FailingObjectStore store;

    @Test
    @DisplayName("a run killed mid-file resumes and every record lands exactly once")
    void resumesAndAppliesEveryRecordOnce() throws IOException {
        String key = dropKey("catalogue-resume.csv");
        put(key, writeCsv(work.resolve("resume.csv"), ROWS, 40));

        // Fail part way through, after several batches have committed.
        store.failAfterBytes(8_000);
        assertThatThrownBy(() -> pass.runOnce(CatalogueIngest.TASK))
                .hasRootCauseInstanceOf(IOException.class);

        Map<String, Object> interrupted = jdbc().queryForMap(
                "SELECT status, checkpoint_position, records_committed FROM file_ingest_run");
        assertThat(interrupted.get("status")).isEqualTo(IngestRunStatus.FAILED.name());
        long checkpoint = (Long) interrupted.get("checkpoint_position");
        long committed = (Long) interrupted.get("records_committed");
        assertThat(checkpoint)
                .as("the interrupted run must have committed something, or the test proves nothing")
                .isGreaterThan(0);
        assertThat(committed).isGreaterThan(0).isLessThan(ROWS);

        // Now let it through, and resume.
        store.doNotFail();
        store.clearRanges();
        pass.runOnce(CatalogueIngest.TASK);

        assertThat(jdbc().queryForObject("SELECT status FROM file_ingest_run", String.class))
                .isEqualTo(IngestRunStatus.COMPLETED.name());
        // Exactly once: the count, and the absence of any duplicate sku. The second assertion is the
        // one that would catch a resume that re-read from zero into a staging table it did not
        // truncate - the count alone would be right after the merge collapsed the duplicates.
        assertThat(jdbc().queryForObject("SELECT count(*) FROM catalogue", Long.class))
                .isEqualTo(ROWS);
        assertThat(jdbc().queryForObject(
                "SELECT count(*) FROM (SELECT sku FROM staging_catalogue GROUP BY sku"
                        + " HAVING count(*) > 1) d", Long.class))
                .as("no record may be staged twice: that is what the checkpoint is for")
                .isZero();
    }

    @Test
    @DisplayName("a resumed run does not re-read what the checkpoint already accounts for")
    void doesNotReReadFromZero() throws IOException {
        String key = dropKey("catalogue-bytes.csv");
        Path file = writeCsv(work.resolve("bytes.csv"), ROWS, 40);
        put(key, file);
        long size = java.nio.file.Files.size(file);

        store.failAfterBytes(8_000);
        assertThatThrownBy(() -> pass.runOnce(CatalogueIngest.TASK)).isInstanceOf(RuntimeException.class);
        long checkpoint = jdbc().queryForObject(
                "SELECT checkpoint_position FROM file_ingest_run", Long.class);

        store.doNotFail();
        store.clearRanges();
        pass.runOnce(CatalogueIngest.TASK);

        long transferred = store.bytesRead();
        assertThat(transferred)
                .as("the resumed run transferred %d bytes of a %d byte object from offset %d; a run"
                        + " that re-read from zero would have transferred the whole object, and the"
                        + " checkpoint would be decoration", transferred, size, checkpoint)
                .isLessThan(size);
    }

    @Test
    @DisplayName("the resumed run issues a ranged GET starting at the committed offset")
    void resumeIsARangedGet() throws IOException {
        put(dropKey("catalogue-ranged.csv"), writeCsv(work.resolve("ranged.csv"), ROWS, 40));

        store.failAfterBytes(8_000);
        assertThatThrownBy(() -> pass.runOnce(CatalogueIngest.TASK)).isInstanceOf(RuntimeException.class);
        long checkpoint = jdbc().queryForObject(
                "SELECT checkpoint_position FROM file_ingest_run", Long.class);

        store.doNotFail();
        store.clearRanges();
        pass.runOnce(CatalogueIngest.TASK);

        assertThat(store.ranges())
                .as("the resume must be a ranged read; a full GET here means the ranged API in"
                        + " ObjectStore is being carried and not used, and a 1GB drop would"
                        + " re-download everything already consumed")
                .isNotEmpty();
        assertThat(store.ranges().get(0).start())
                .as("and it must start at the committed checkpoint, not near it: an off-by-one here"
                        + " duplicates or drops exactly one record per resume")
                .isEqualTo(checkpoint);
    }

    /** Replaces the module's store with one that can be made to fail part way through a read. */
    @TestConfiguration
    static class FailingStoreConfiguration {

        /**
         * The decorated store.
         *
         * <p>The delegate is constructed here rather than injected, and that is not incidental: the
         * storage module's own {@code ObjectStore} bean is {@code @ConditionalOnMissingBean}, so
         * declaring this decorator suppresses it - there would be nothing to inject. Building a second
         * {@code S3ObjectStore} over the same client is the arrangement that leaves the real
         * implementation under test rather than replacing it with a stub.
         *
         * @param client the S3 client the storage module built against the container
         * @return the decorator
         */
        @Bean
        @Primary
        FailingObjectStore failingObjectStore(S3Client client) {
            return new FailingObjectStore(new S3ObjectStore(client));
        }
    }

    /**
     * An {@link ObjectStore} that records the ranges it is asked for and can fail mid-stream.
     *
     * <p>A decorator rather than a mock: every method that is not being interfered with goes to the
     * real store against the real container, so the test still exercises the S3 implementation - which
     * is where a ranged read either works or does not.
     */
    static class FailingObjectStore implements ObjectStore {

        private final ObjectStore delegate;
        private final List<ByteRange> ranges = new ArrayList<>();
        private final AtomicInteger bytesRead = new AtomicInteger();

        private volatile long failAfter = -1;

        FailingObjectStore(ObjectStore delegate) {
            this.delegate = delegate;
        }

        void failAfterBytes(long bytes) {
            this.failAfter = bytes;
            this.bytesRead.set(0);
        }

        void doNotFail() {
            this.failAfter = -1;
            this.bytesRead.set(0);
        }

        void clearRanges() {
            ranges.clear();
        }

        List<ByteRange> ranges() {
            return List.copyOf(ranges);
        }

        long bytesRead() {
            return bytesRead.get();
        }

        @Override
        public StoredObject head(String uri) {
            return delegate.head(uri);
        }

        @Override
        public InputStream open(String uri) {
            return counting(delegate.open(uri));
        }

        @Override
        public InputStream open(String uri, ByteRange range) {
            ranges.add(range);
            return counting(delegate.open(uri, range));
        }

        @Override
        public java.util.stream.Stream<ObjectSummary> list(String prefix) {
            return delegate.list(prefix);
        }

        @Override
        public StoredObject put(String uri, Path file, PutOptions options) {
            return delegate.put(uri, file, options);
        }

        @Override
        public StoredObject copy(String sourceUri, String targetUri) {
            return delegate.copy(sourceUri, targetUri);
        }

        @Override
        public void delete(String uri) {
            delegate.delete(uri);
        }

        @Override
        public boolean exists(String uri) {
            return delegate.exists(uri);
        }

        private InputStream counting(InputStream in) {
            return new java.io.FilterInputStream(in) {
                @Override
                public int read() throws IOException {
                    int value = super.read();
                    if (value >= 0) {
                        consumed(1);
                    }
                    return value;
                }

                @Override
                public int read(byte[] buffer, int offset, int length) throws IOException {
                    int read = super.read(buffer, offset, length);
                    if (read > 0) {
                        consumed(read);
                    }
                    return read;
                }

                private void consumed(int count) throws IOException {
                    int total = bytesRead.addAndGet(count);
                    long limit = failAfter;
                    if (limit >= 0 && total >= limit) {
                        // Indistinguishable, from the engine's point of view, from the network
                        // dropping mid-response.
                        throw new IOException("simulated read failure after " + total + " bytes");
                    }
                }
            };
        }
    }
}
