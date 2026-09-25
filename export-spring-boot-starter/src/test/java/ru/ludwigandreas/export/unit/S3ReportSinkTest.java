package ru.ludwigandreas.export.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.ludwigandreas.export.api.StandardReportFormats;
import ru.ludwigandreas.export.api.StoredOutput;
import ru.ludwigandreas.export.sink.S3ReportSink;
import ru.ludwigandreas.storage.api.ObjectStore;
import ru.ludwigandreas.storage.fs.FilesystemObjectStore;

/**
 * The object-storage sink, exercised against the filesystem {@link ObjectStore}.
 *
 * <p>Not a mock. The storage module holds both its implementations to one contract, so a test of
 * this adapter against the filesystem store is a test of the behaviour the S3 store is separately
 * proven to share - which is most of the value of having written that contract test. A mock would
 * only assert that this class calls the methods it calls.
 */
class S3ReportSinkTest {

    private static final String BUCKET = "reports";

    @TempDir
    Path work;

    private ObjectStore store;
    private S3ReportSink sink;

    @BeforeEach
    void createSink() throws IOException {
        store = new FilesystemObjectStore(Files.createDirectories(work.resolve("bucket-root")));
        sink = new S3ReportSink(store, BUCKET, "reports/");
    }

    @Test
    void storesUnderAKeyThatCarriesTheRunIdAndItsFanOutSegment() throws IOException {
        UUID runId = UUID.fromString("7f3c1a2e-0000-4000-8000-000000000001");

        StoredOutput output = sink.store(runId, "catalogue.csv", StandardReportFormats.CSV, fileOf("a,b\n1,2"));

        assertThat(output.uri())
                .isEqualTo("s3://reports/reports/7f/7f3c1a2e-0000-4000-8000-000000000001/catalogue.csv");
        assertThat(output.sizeBytes()).isEqualTo("a,b\n1,2".length());
        assertThat(output.sha256()).hasSize(64);
    }

    @Test
    void readsBackWhatItStored() throws IOException {
        StoredOutput output = sink.store(UUID.randomUUID(), "report.csv", StandardReportFormats.CSV,
                fileOf("x,y\n3,4"));

        try (InputStream in = sink.open(output.uri())) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("x,y\n3,4");
        }
    }

    @Test
    void reportsAMissingOutputAsAMissingFileRatherThanAnUnavailableStore() {
        // The download endpoint distinguishes "expired" from "the bucket is down", and it can only do
        // that if the sink does not flatten the two into one IOException.
        assertThatThrownBy(() -> sink.open("s3://reports/reports/00/gone/report.csv"))
                .isInstanceOf(FileNotFoundException.class);
    }

    @Test
    void deletingAnOutputTwiceIsASuccessBecauseThePurgeReRunsAPassItHalfCompleted() throws IOException {
        StoredOutput output = sink.store(UUID.randomUUID(), "report.csv", StandardReportFormats.CSV, fileOf("z"));

        sink.delete(output.uri());

        assertThatCode(() -> sink.delete(output.uri())).doesNotThrowAnyException();
    }

    @Test
    void normalisesThePrefixSoThatSlashesAreNotPartOfTheConfigurationContract() throws IOException {
        UUID runId = UUID.fromString("7f3c1a2e-0000-4000-8000-000000000001");

        for (String prefix : new String[] {"reports", "/reports", "reports/", "/reports/"}) {
            StoredOutput output = new S3ReportSink(store, BUCKET, prefix)
                    .store(runId, "r.csv", StandardReportFormats.CSV, fileOf("body"));
            assertThat(output.uri()).startsWith("s3://reports/reports/7f/");
        }
    }

    @Test
    void refusesAFileNameThatWouldSilentlyBecomeExtraKeyNesting() {
        assertThatThrownBy(() -> sink.store(UUID.randomUUID(), "sub/report.csv", StandardReportFormats.CSV,
                fileOf("body"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesToStartWithoutABucketRatherThanFailingOnTheFirstFinishedReport() {
        assertThatThrownBy(() -> new S3ReportSink(store, null, ""))
                .hasMessageContaining("ludwig.export.sink.bucket");
    }

    private Path fileOf(String content) {
        try {
            Path file = Files.createTempFile(work, "report", ".csv");
            Files.writeString(file, content, StandardCharsets.UTF_8);
            return file;
        } catch (IOException e) {
            throw new IllegalStateException("Could not stage a test file", e);
        }
    }
}
