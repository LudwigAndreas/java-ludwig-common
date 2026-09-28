package ru.ludwigandreas.observability.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggerConfiguration;
import org.springframework.boot.logging.LoggingSystem;
import ru.ludwigandreas.hotreload.core.SourceChangeEvent;
import ru.ludwigandreas.observability.logging.LogLevelReloader;

/** Raising a package to DEBUG during an incident - and, just as importantly, putting it back. */
class LogLevelReloaderTest {

    private final RecordingLoggingSystem loggingSystem = new RecordingLoggingSystem();

    @Test
    void appliesLoggingLevelKeysFromAReloadedSource() {
        reloader(true).onChange(event(Map.of("logging.level.com.example.payments", "DEBUG")));

        assertThat(loggingSystem.levels).containsEntry("com.example.payments", LogLevel.DEBUG);
    }

    @Test
    void ignoresKeysThatAreNotLogLevels() {
        reloader(true).onChange(event(Map.of("some.other.property", "value")));

        assertThat(loggingSystem.levels).isEmpty();
    }

    @Test
    void ignoresAnUnparseableLevelWithoutAbandoningTheRestOfTheReload() {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("logging.level.com.example.a", "NOT_A_LEVEL");
        content.put("logging.level.com.example.b", "WARN");

        reloader(true).onChange(event(content));

        assertThat(loggingSystem.levels).doesNotContainKey("com.example.a");
        assertThat(loggingSystem.levels).containsEntry("com.example.b", LogLevel.WARN);
    }

    @Test
    void normalisesTheRootLoggerToTheNameTheLoggingSystemActuallyMatches() {
        reloader(true).onChange(event(Map.of("logging.level.root", "WARN")));

        // "root" as written in application.yml would otherwise create an ordinary logger by that name
        // and change nothing anybody logs through.
        assertThat(loggingSystem.levels).containsEntry(LoggingSystem.ROOT_LOGGER_NAME, LogLevel.WARN);
        assertThat(loggingSystem.levels).doesNotContainKey("root");
    }

    @Test
    void revertsToTheStartupLevelWhenTheKeyIsRemoved() {
        loggingSystem.configured.put("com.example.payments", LogLevel.INFO);
        LogLevelReloader reloader = reloader(true);

        reloader.onChange(event(Map.of("logging.level.com.example.payments", "DEBUG")));
        reloader.onChange(event(Map.of()));

        // Without this, a level raised at 3am and removed the next morning stays raised - at full
        // volume into a billed pipeline - until the next restart.
        assertThat(loggingSystem.levels).containsEntry("com.example.payments", LogLevel.INFO);
    }

    @Test
    void revertsToAnInheritedLevelByClearingRatherThanGuessing() {
        LogLevelReloader reloader = reloader(true);

        reloader.onChange(event(Map.of("logging.level.com.example.payments", "TRACE")));
        reloader.onChange(event(Map.of()));

        assertThat(loggingSystem.levels).containsEntry("com.example.payments", null);
    }

    @Test
    void keepsTheOriginalBaselineAcrossSeveralChangesRatherThanDriftingTowardsTheOverride() {
        loggingSystem.configured.put("com.example.payments", LogLevel.INFO);
        LogLevelReloader reloader = reloader(true);

        reloader.onChange(event(Map.of("logging.level.com.example.payments", "DEBUG")));
        reloader.onChange(event(Map.of("logging.level.com.example.payments", "TRACE")));
        reloader.onChange(event(Map.of()));

        // Re-capturing the baseline on the second change would have recorded DEBUG - a level this
        // class itself had set - and reverting would then never restore anything.
        assertThat(loggingSystem.levels).containsEntry("com.example.payments", LogLevel.INFO);
    }

    @Test
    void leavesTheLevelInPlaceWhenRevertingIsDisabled() {
        loggingSystem.configured.put("com.example.payments", LogLevel.INFO);
        LogLevelReloader reloader = reloader(false);

        reloader.onChange(event(Map.of("logging.level.com.example.payments", "DEBUG")));
        reloader.onChange(event(Map.of()));

        assertThat(loggingSystem.levels).containsEntry("com.example.payments", LogLevel.DEBUG);
    }

    @Test
    void leavesLevelsUntouchedWhenTheSourceFailedToReload() {
        LogLevelReloader reloader = reloader(true);
        reloader.onChange(event(Map.of("logging.level.com.example.payments", "DEBUG")));

        reloader.onError("payments-config", new IllegalStateException("vault unreachable"));

        assertThat(loggingSystem.levels).containsEntry("com.example.payments", LogLevel.DEBUG);
    }

    private LogLevelReloader reloader(boolean revertOnRemoval) {
        return new LogLevelReloader(loggingSystem, revertOnRemoval);
    }

    private SourceChangeEvent event(Map<String, Object> content) {
        return new SourceChangeEvent("logging-config", content, Map.of(), Set.copyOf(content.keySet()));
    }

    /** Records what was asked of the logging system instead of reconfiguring the test JVM's logging. */
    private static final class RecordingLoggingSystem extends LoggingSystem {

        private final Map<String, LogLevel> levels = new HashMap<>();
        private final Map<String, LogLevel> configured = new HashMap<>();

        @Override
        public void beforeInitialize() {
        }

        @Override
        public void setLogLevel(String loggerName, LogLevel level) {
            levels.put(loggerName, level);
        }

        @Override
        public LoggerConfiguration getLoggerConfiguration(String loggerName) {
            return new LoggerConfiguration(loggerName, configured.get(loggerName), LogLevel.INFO);
        }
    }
}
