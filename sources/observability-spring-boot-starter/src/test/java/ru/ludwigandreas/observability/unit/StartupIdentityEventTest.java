package ru.ludwigandreas.observability.unit;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.support.GenericApplicationContext;
import ru.ludwigandreas.observability.core.BuildIdentity;
import ru.ludwigandreas.observability.core.ServiceIdentity;
import ru.ludwigandreas.observability.logging.StartupIdentityLogger;

/** One event at startup says what this process is and what it was built from. */
class StartupIdentityEventTest {

    private static final ServiceIdentity SERVICE =
            new ServiceIdentity("catalog", "commerce", "1.4.2", "prod", "catalog-7d9f-xk2");

    private static final BuildIdentity BUILD = new BuildIdentity(
            "c1fc5b81ecfa20e1b3418002a5ace90473d6734c", "c1fc5b8", "release/1.4", "2026-10-10T00:00:00Z",
            "4711", false);

    private final ListAppender<ILoggingEvent> events = new ListAppender<>();
    private Logger logger;
    private Level originalLevel;

    @BeforeEach
    void attach() {
        logger = (Logger) LoggerFactory.getLogger(StartupIdentityLogger.class);
        originalLevel = logger.getLevel();
        logger.setLevel(Level.INFO);
        events.start();
        logger.addAppender(events);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(events);
        logger.setLevel(originalLevel);
    }

    @Test
    void emitsOneEventCarryingTheFullApplicationAndBuildIdentity() {
        started(new StartupIdentityLogger(SERVICE, BUILD));

        assertThat(events.list).hasSize(1);
        ILoggingEvent event = events.list.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.INFO);
        // Readable as text, so the event answers the question under the human-readable format too.
        assertThat(event.getFormattedMessage()).contains(
                "service=catalog", "version=1.4.2", "environment=prod", "instance=catalog-7d9f-xk2",
                "commit=c1fc5b81ecfa20e1b3418002a5ace90473d6734c", "branch=release/1.4",
                "built=2026-10-10T00:00:00Z", "ci-build=4711", "tree=clean");
        // And as fields, so it is a filter rather than a text search in an aggregator.
        assertThat(keyValues(event)).containsOnly(
                Map.entry("build.commit.id", "c1fc5b81ecfa20e1b3418002a5ace90473d6734c"),
                Map.entry("build.branch", "release/1.4"),
                Map.entry("build.timestamp", "2026-10-10T00:00:00Z"),
                Map.entry("build.ci.build-number", "4711"),
                Map.entry("build.dirty", "false"));
    }

    @Test
    void emitsItExactlyOnceHoweverManyTimesTheContextReportsStarted() {
        StartupIdentityLogger listener = new StartupIdentityLogger(SERVICE, BUILD);

        started(listener);
        started(listener);

        assertThat(events.list).hasSize(1);
    }

    @Test
    void omitsWhatCouldNotBeResolvedRatherThanWritingAPlaceholder() {
        // A local build from a checkout: no CI build number, and nobody set an environment.
        ServiceIdentity service = new ServiceIdentity("catalog", null, "1.4.2", null, null);
        BuildIdentity build = new BuildIdentity(null, "c1fc5b8", "master", null, null, null);

        started(new StartupIdentityLogger(service, build));

        ILoggingEvent event = events.list.get(0);
        assertThat(event.getFormattedMessage())
                .contains("service=catalog", "commit=c1fc5b8", "branch=master")
                .doesNotContain("environment=", "instance=", "built=", "ci-build=", "tree=", "null", "unknown");
        assertThat(keyValues(event)).containsOnlyKeys("build.branch");
    }

    @Test
    void stillEmitsTheEventForAProcessWithNoProvenanceAtAll() {
        started(new StartupIdentityLogger(new ServiceIdentity("catalog", null, null, null, null),
                BuildIdentity.absent()));

        assertThat(events.list).hasSize(1);
        assertThat(events.list.get(0).getFormattedMessage()).contains("service=catalog").doesNotContain("commit=");
        assertThat(events.list.get(0).getKeyValuePairs()).isNullOrEmpty();
    }

    @Test
    void makesADirtyBuildVisible() {
        BuildIdentity dirty = new BuildIdentity(null, "c1fc5b8", null, null, null, true);

        started(new StartupIdentityLogger(SERVICE, dirty));

        ILoggingEvent event = events.list.get(0);
        assertThat(event.getFormattedMessage()).contains("tree=dirty");
        assertThat(keyValues(event)).containsEntry("build.dirty", "true");
    }

    private void started(StartupIdentityLogger listener) {
        listener.onApplicationEvent(new ApplicationStartedEvent(
                new SpringApplication(), new String[0], new GenericApplicationContext(), Duration.ZERO));
    }

    private Map<String, String> keyValues(ILoggingEvent event) {
        if (event.getKeyValuePairs() == null) {
            return Map.of();
        }
        return event.getKeyValuePairs().stream()
                .collect(Collectors.toMap(pair -> pair.key, pair -> String.valueOf(pair.value)));
    }
}
