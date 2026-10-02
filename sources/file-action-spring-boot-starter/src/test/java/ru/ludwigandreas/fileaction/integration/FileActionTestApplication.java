package ru.ludwigandreas.fileaction.integration;

import java.nio.file.Path;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import ru.ludwigandreas.audit.ActorResolver;
import ru.ludwigandreas.audit.Actor;
import ru.ludwigandreas.job.core.lock.JdbcRunLock;
import ru.ludwigandreas.job.core.lock.RunLock;
import ru.ludwigandreas.storage.api.ObjectStore;

/**
 * The application the integration tests run in.
 *
 * <p>{@code RecordingOrderHandler} is not declared here: {@code @FileAction} is itself a {@code @Component}, so
 * component scanning picks it up - and under the bean name the annotation's {@code value} gives it, which is the
 * action name. Declaring it again produced two beans of the same type and an ambiguous injection in every test.
 *
 * <p>Filesystem storage rather than LocalStack. {@code object-storage}'s own suite holds both implementations to one
 * contract - including the ranged reads this module does not use - so starting a second container here would test
 * that module again rather than this one, and the filesystem store has real ranged reads precisely so it can stand
 * in.
 */
@SpringBootApplication
public class FileActionTestApplication {

    /** The filesystem store's root, which every test's configured prefixes sit under. */
    public static final String ROOT = "/tmp/ludwig-file-action-it";

    /**
     * The clock every component in this context judges windows against.
     *
     * <p>Declared here rather than taken from the module, because the module deliberately publishes no
     * {@code Clock} bean: a library publishing one guarded by {@code @ConditionalOnMissingBean} collides with
     * {@code rest-client}'s, and anything injecting a clock by type then fails to start on an ambiguity neither
     * module caused alone. An application that wants one declares it - which is what this is, and what a real
     * deployment does.
     *
     * @return the clock
     */
    @Bean
    public java.time.Clock clock() {
        // systemUTC, never systemDefaultZone: RuleGroup.PRESENTATION forbids the latter.
        return java.time.Clock.systemUTC();
    }

    /**
     * A filesystem object store rooted in the temp directory.
     *
     * @return the store
     */
    @Bean
    public ObjectStore objectStore() throws java.io.IOException {
        // Created here rather than assumed: FilesystemObjectStore refuses a root that is not there, which is
        // correct - a store silently creating its own root would hide a misconfigured path in production.
        //
        // A literal path rather than java.io.tmpdir, because the configured prefixes in each test are literal
        // strings and the two have to agree: on macOS java.io.tmpdir is /var/folders/..., so a store rooted there
        // rejects every file:///tmp/... URI as outside its root. That cost a confusing invalid-uri failure.
        Path root = java.nio.file.Files.createDirectories(Path.of(ROOT));
        return new ru.ludwigandreas.storage.fs.FilesystemObjectStore(root);
    }

    /**
     * The platform's one distributed lock, over the test's own database.
     *
     * @param dataSource the container's data source
     * @return the lock
     */
    @Bean
    public RunLock runLock(DataSource dataSource) {
        return new JdbcRunLock(dataSource, "test-replica");
    }

    /**
     * A fixed actor, so the submission's {@code submitted_by} and the dedup constraint have something to key on.
     *
     * @return the resolver
     */
    @Bean
    public ActorResolver actorResolver() {
        return () -> java.util.Optional.of(Actor.of("it-user"));
    }
}
