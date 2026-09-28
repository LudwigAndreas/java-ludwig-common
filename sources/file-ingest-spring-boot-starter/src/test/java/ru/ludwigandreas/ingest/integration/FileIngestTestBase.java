package ru.ludwigandreas.ingest.integration;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.localstack.LocalStackContainer;
import ru.ludwigandreas.testsupport.container.Containers;
import ru.ludwigandreas.testsupport.container.LocalStackS3;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * The CSV, table and bucket fixtures every ingest test needs.
 *
 * <p>Both containers come from {@link ru.ludwigandreas.testsupport.container.Containers}, shared with
 * the rest of the reactor and pinned by digest there. The pinning rule and the reason the S3 container
 * is LocalStack rather than MinIO are recorded on
 * {@link ru.ludwigandreas.testsupport.image.LudwigTestImages}; this class used to restate both.
 *
 * <h2>The target and staging tables belong to the test, not to the module</h2>
 *
 * <p>The module ships {@code file_ingest_run} and {@code file_ingest_quarantine} and nothing else. A
 * per-task staging table and the target it merges into are the author's schema - only the author
 * knows what a record looks like - so the test creates them exactly as a service would.
 */
@SpringBootTest(classes = IngestTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
abstract class FileIngestTestBase {

    /** The bucket every test's drop prefix lives in. */
    protected static final String BUCKET = "partner-drop";

    /**
     * The shared containers.
     *
     * <p>Not {@code @Container}, and the reason was a real failure rather than a preference: the JUnit
     * extension stops a static container when its test class finishes, so the second IT class would get
     * new containers on new ports - while Spring, whose context cache key has not changed, happily
     * reuses the context wired to the old ones. The symptom is "connection refused" in the second class
     * and nowhere else, which is a long way from its cause. That reasoning now lives on
     * {@link ru.ludwigandreas.testsupport.container.Containers}, which credits this class; these two
     * fields are only local names for it.
     *
     * <p>Tests isolate themselves by truncating in {@link #prepare()} rather than by getting a fresh
     * database, which is faster as well as correct.
     */
    private static final PostgreSQLContainer<?> POSTGRES = Containers.postgres();

    private static final LocalStackContainer LOCALSTACK = Containers.localStack();

    @Autowired
    private DataSource dataSource;

    /**
     * Points the module at both containers.
     *
     * @param registry the property registry
     */
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.liquibase.enabled", () -> "false");

        registry.add("ludwig.storage.type", () -> "s3");
        registry.add("ludwig.storage.s3.endpoint", () -> LOCALSTACK.getEndpoint().toString());
        registry.add("ludwig.storage.s3.region", LOCALSTACK::getRegion);
        registry.add("ludwig.storage.s3.path-style-access", () -> "true");
        registry.add("ludwig.storage.s3.credentials.source", () -> "static");
        registry.add("ludwig.storage.s3.credentials.access-key", LOCALSTACK::getAccessKey);
        registry.add("ludwig.storage.s3.credentials.secret-key", LOCALSTACK::getSecretKey);
    }

    // ludwig.ingest.scheduler-enabled is deliberately NOT set here. It lives in
    // src/test/resources/application.yml, because @DynamicPropertySource outranks @TestPropertySource:
    // set here, a test that wanted the scheduler on could not turn it on, and the symptom would be a
    // missing bean rather than anything naming the precedence rule.

    /**
     * Creates the bucket and the author's own tables, once per class.
     */
    @BeforeEach
    void prepare() {
        LocalStackS3.ensureBucket(BUCKET);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS catalogue (
                    sku         VARCHAR(64) PRIMARY KEY,
                    -- TEXT rather than VARCHAR(n): the heap test pads each row to several kilobytes
                    -- so that a 200MB fixture needs tens of thousands of rows rather than millions,
                    -- and a length-limited column would make that fixture fail on its own width
                    -- instead of exercising the byte bound it is there to exercise.
                    name        TEXT NOT NULL,
                    price_cents BIGINT NOT NULL
                )""");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS staging_catalogue (
                    sku         VARCHAR(64) NOT NULL,
                    name        TEXT NOT NULL,
                    price_cents BIGINT NOT NULL
                )""");
        jdbc.execute("TRUNCATE TABLE catalogue");
        jdbc.execute("TRUNCATE TABLE staging_catalogue");
        jdbc.execute("DELETE FROM file_ingest_quarantine");
        jdbc.execute("DELETE FROM file_ingest_run");
        LocalStackS3.clearBucket(BUCKET);
    }

    /**
     * The S3 client the tests write fixtures with, which is deliberately not the module's own - see
     * {@link LocalStackS3} for why a fixture written with the code under test proves less.
     *
     * @return a client pointed at the container
     */
    protected static S3Client client() {
        return LocalStackS3.client();
    }

    /**
     * Puts an object into the drop prefix.
     *
     * @param key  the key under the bucket
     * @param body the content
     */
    protected static void put(String key, String body) {
        client().putObject(PutObjectRequest.builder().bucket(BUCKET).key(key).build(),
                RequestBody.fromString(body, StandardCharsets.UTF_8));
    }

    /**
     * Puts an object from a local file, for a fixture too large to build as a string.
     *
     * @param key  the key under the bucket
     * @param file the local file
     */
    protected static void put(String key, Path file) {
        client().putObject(PutObjectRequest.builder().bucket(BUCKET).key(key).build(),
                RequestBody.fromFile(file));
    }

    /**
     * Writes a CSV of {@code rows} product rows to a local file, without building it in memory.
     *
     * <p>Streamed for the same reason the module streams: a 200 MB fixture assembled as a
     * {@code String} would make the heap-ceiling test fail in the test's own setup rather than in the
     * code it is measuring.
     *
     * @param file  where to write it
     * @param rows  how many rows
     * @param width how many bytes of padding each row's name carries, so the file can be made large
     *              without needing millions of rows
     * @return the file
     * @throws IOException if it could not be written
     */
    protected static Path writeCsv(Path file, int rows, int width) throws IOException {
        String padding = "x".repeat(Math.max(width, 1));
        try (OutputStream out = Files.newOutputStream(file)) {
            for (int i = 0; i < rows; i++) {
                out.write(("SKU-" + i + ",widget-" + i + "-" + padding + "," + (100 + i) + "\n")
                        .getBytes(StandardCharsets.UTF_8));
            }
        }
        return file;
    }

    /**
     * A JDBC template over the test's own database.
     *
     * @return the template
     */
    protected JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }

    /**
     * A unique object key under the drop prefix, so tests do not collide.
     *
     * @param name the file name
     * @return the key
     */
    protected static String dropKey(String name) {
        return "catalogue/" + name;
    }

    /**
     * A name nothing else in the suite will use.
     *
     * @return a unique file name
     */
    protected static String uniqueName() {
        return "catalogue-" + UUID.randomUUID() + ".csv";
    }

}
