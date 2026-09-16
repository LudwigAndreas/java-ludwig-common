package ru.ludwigandreas.observability.logging;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggerConfiguration;
import org.springframework.boot.logging.LoggingSystem;
import ru.ludwigandreas.hotreload.core.SourceChangeEvent;
import ru.ludwigandreas.hotreload.core.SourceChangeListener;

/**
 * Applies {@code logging.level.*} keys from a hot-reloaded source to the running logging system.
 *
 * <h2>Why this is worth having</h2>
 *
 * <p>The moment you most need DEBUG on one package is the moment you least want to restart: the
 * process holding the symptom is the process a restart destroys, along with its connection pools,
 * its caches and whatever state made the fault reproducible. The alternatives are all worse than
 * they look - shipping a config change and rolling the deployment takes minutes and kills the
 * evidence, and POSTing to {@code /actuator/loggers} has to be repeated against every replica
 * individually, is not recorded anywhere, and silently reverts on the next restart.
 *
 * <p>Driving it from a watched file or a Vault secret makes the change fleet-wide, reviewable, and
 * durable in the same place the rest of the configuration lives.
 *
 * <h2>Reverting matters as much as applying</h2>
 *
 * <p>Whenever a level is raised, the level it had beforehand is remembered, and removing the key
 * puts it back. Without that, the realistic sequence is: someone raises a chatty package to DEBUG at
 * three in the morning, removes the line the next day, and the service keeps logging at DEBUG - at
 * full volume, into a billed log pipeline - until its next restart, which might be weeks away. The
 * symptom is a log bill, and nothing points at the cause.
 */
public class LogLevelReloader implements SourceChangeListener {

    private static final Logger log = LoggerFactory.getLogger(LogLevelReloader.class);

    private static final String LEVEL_KEY_PREFIX = "logging.level.";

    private final LoggingSystem loggingSystem;
    private final boolean revertOnRemoval;

    /**
     * The level each logger had before this class first touched it.
     *
     * <p>Captured lazily on first change rather than eagerly for every logger, because "every
     * logger" is not an enumerable set - loggers come into existence as classes are loaded, so a
     * snapshot taken at startup would be missing exactly the ones a later change is about to name.
     *
     * <p>A null value is meaningful and is stored as such: it records "this logger had no configured
     * level of its own and inherited from its parent", which reverting must restore by clearing the
     * level, not by setting it to whatever the parent happened to be at the time.
     */
    private final Map<String, LogLevel> baselineLevels = new HashMap<>();

    /** Loggers currently overridden by a reloaded source, so removals can be detected. */
    private final Set<String> appliedLoggers = new LinkedHashSet<>();

    public LogLevelReloader(LoggingSystem loggingSystem, boolean revertOnRemoval) {
        this.loggingSystem = loggingSystem;
        this.revertOnRemoval = revertOnRemoval;
    }

    @Override
    public synchronized void onChange(SourceChangeEvent event) {
        Set<String> loggersInSource = new LinkedHashSet<>();

        for (Map.Entry<String, Object> entry : event.content().entrySet()) {
            String loggerName = loggerNameOf(entry.getKey());
            if (loggerName == null) {
                continue;
            }
            loggersInSource.add(loggerName);
            applyLevel(loggerName, entry.getValue());
        }

        if (revertOnRemoval) {
            revertLoggersMissingFrom(loggersInSource);
        }
        appliedLoggers.retainAll(loggersInSource);
        appliedLoggers.addAll(loggersInSource);
    }

    @Override
    public void onError(String sourceId, Exception exception) {
        // The watcher keeps serving the last good content, so nothing has changed and nothing needs
        // undoing here. Logged so that a source which has been failing to reload for hours is
        // visible, rather than presenting as "my log level change did nothing".
        log.warn("Hot-reload source '{}' failed to reload; log levels are unchanged", sourceId, exception);
    }

    /**
     * Restores every logger this class had overridden that the new content no longer mentions.
     *
     * <p>Iterating over a copy: {@link #revert} mutates {@link #appliedLoggers}, and removing from a
     * set while iterating it throws.
     */
    private void revertLoggersMissingFrom(Set<String> loggersInSource) {
        for (String loggerName : Set.copyOf(appliedLoggers)) {
            if (!loggersInSource.contains(loggerName)) {
                revert(loggerName);
            }
        }
    }

    private void applyLevel(String loggerName, Object rawLevel) {
        LogLevel level = parseLevel(rawLevel);
        if (level == null) {
            log.warn("Ignoring hot-reloaded log level '{}' for logger '{}': not a valid level", rawLevel, loggerName);
            return;
        }
        rememberBaseline(loggerName);
        try {
            loggingSystem.setLogLevel(loggerName, level);
            log.info("Hot-reloaded log level: {} is now {}", loggerName, level);
        } catch (RuntimeException e) {
            // A logging system may reject a name it cannot resolve. That must not propagate into the
            // watcher thread, where it would abort the rest of this reload - leaving the other keys
            // in the same file unapplied, for a reason nobody would connect to this one.
            log.warn("Could not set log level {} for logger '{}'", level, loggerName, e);
        }
    }

    private void revert(String loggerName) {
        if (!baselineLevels.containsKey(loggerName)) {
            return;
        }
        LogLevel baseline = baselineLevels.get(loggerName);
        try {
            loggingSystem.setLogLevel(loggerName, baseline);
            log.info("Hot-reloaded log level removed: {} reverted to {}", loggerName,
                    baseline == null ? "its inherited level" : baseline);
        } catch (RuntimeException e) {
            log.warn("Could not revert log level for logger '{}'", loggerName, e);
        }
        baselineLevels.remove(loggerName);
        appliedLoggers.remove(loggerName);
    }

    /**
     * Records the pre-change level the first time a logger is touched, and never again.
     *
     * <p>Re-capturing on a later change would record a level this class itself had set, so the
     * baseline would drift towards the overridden value and reverting would stop restoring anything.
     */
    private void rememberBaseline(String loggerName) {
        if (baselineLevels.containsKey(loggerName)) {
            return;
        }
        LoggerConfiguration configuration = loggingSystem.getLoggerConfiguration(loggerName);
        baselineLevels.put(loggerName, configuration == null ? null : configuration.getConfiguredLevel());
    }

    /**
     * {@code logging.level.com.example} yields {@code com.example}; anything else yields null.
     *
     * <p>{@code logging.level.root} is special-cased to the canonical {@code ROOT}. Spring's
     * logging systems match the root logger's name exactly, so the lower-case spelling that
     * everybody writes in {@code application.yml} - and that Spring Boot itself accepts there -
     * would otherwise be taken for an ordinary logger called "root", create one, and change nothing
     * that anybody is logging through.
     */
    private String loggerNameOf(String key) {
        if (key == null || !key.startsWith(LEVEL_KEY_PREFIX)) {
            return null;
        }
        String loggerName = key.substring(LEVEL_KEY_PREFIX.length());
        if (loggerName.isBlank()) {
            return null;
        }
        return loggerName.equalsIgnoreCase(LoggingSystem.ROOT_LOGGER_NAME)
                ? LoggingSystem.ROOT_LOGGER_NAME
                : loggerName;
    }

    private LogLevel parseLevel(Object rawLevel) {
        if (rawLevel == null) {
            return null;
        }
        try {
            return LogLevel.valueOf(rawLevel.toString().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
