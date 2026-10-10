package ru.ludwigandreas.observability.logging;

import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.ApplicationListener;
import ru.ludwigandreas.observability.core.BuildIdentity;
import ru.ludwigandreas.observability.core.ServiceIdentity;

/**
 * Logs, once, who this process is and what it was built from.
 *
 * <h2>Why a single event and not more fields on every line</h2>
 *
 * <p>Every log event already carries the service name, the version and the abbreviated commit. The
 * rest of the provenance - the full hash, the branch, the build timestamp, the CI build number,
 * whether the tree was dirty - is needed exactly once per process: when someone asks "what is this
 * pod running". A log line is written millions of times and this event once, so those fields belong
 * here, where they cost one line, and not on the stream.
 *
 * <h2>Readable in both formats</h2>
 *
 * <p>The identity is written twice on purpose. It is in the message, so the event answers the
 * question as plain text under the human-readable format and under a logging configuration the
 * service wrote itself; and the build fields are attached as key-value pairs, which the JSON encoder
 * writes as fields, so the same event is a filter rather than a text search in an aggregator.
 *
 * <p>A field that could not be resolved is left out of both. A process started from an IDE has no
 * provenance, and saying nothing about the commit is the truthful rendering of that.
 */
public class StartupIdentityLogger implements ApplicationListener<ApplicationStartedEvent> {

    /** Key-value names for the build fields. Fixed, so a query for them works across services. */
    public static final String KEY_COMMIT_ID = "build.commit.id";
    public static final String KEY_BRANCH = "build.branch";
    public static final String KEY_BUILD_TIMESTAMP = "build.timestamp";
    public static final String KEY_CI_BUILD_NUMBER = "build.ci.build-number";
    public static final String KEY_DIRTY = "build.dirty";

    private static final Logger log = LoggerFactory.getLogger(StartupIdentityLogger.class);

    private final ServiceIdentity service;
    private final BuildIdentity build;

    /**
     * A started event reaches every listener in the context hierarchy, and a context can be
     * published to more than once in a test JVM. One process, one identity event.
     */
    private final AtomicBoolean logged = new AtomicBoolean();

    public StartupIdentityLogger(ServiceIdentity service, BuildIdentity build) {
        this.service = service;
        this.build = build;
    }

    @Override
    public void onApplicationEvent(ApplicationStartedEvent event) {
        if (!logged.compareAndSet(false, true)) {
            return;
        }
        StringBuilder message = new StringBuilder("Application identity:");
        LoggingEventBuilder entry = log.atInfo();

        describe(message, "service", service.name());
        describe(message, "version", service.version());
        describe(message, "environment", service.environment());
        describe(message, "instance", service.instance());

        describe(message, "commit", build.commitId() != null ? build.commitId() : build.abbreviatedCommitId());
        entry = attach(entry, KEY_COMMIT_ID, build.commitId());
        describe(message, "branch", build.branch());
        entry = attach(entry, KEY_BRANCH, build.branch());
        describe(message, "built", build.buildTimestamp());
        entry = attach(entry, KEY_BUILD_TIMESTAMP, build.buildTimestamp());
        describe(message, "ci-build", build.ciBuildNumber());
        entry = attach(entry, KEY_CI_BUILD_NUMBER, build.ciBuildNumber());
        if (build.dirty() != null) {
            // Spelled out rather than true/false: "dirty" on the first line of a pod's log is the
            // thing a reader must not miss, and "dirty=false" is one character from "dirty=true".
            describe(message, "tree", build.dirty() ? "dirty" : "clean");
            entry = attach(entry, KEY_DIRTY, build.dirty().toString());
        }

        entry.log(message.toString());
    }

    private static void describe(StringBuilder message, String label, String value) {
        if (value != null) {
            message.append(' ').append(label).append('=').append(value);
        }
    }

    private static LoggingEventBuilder attach(LoggingEventBuilder entry, String key, String value) {
        return value != null ? entry.addKeyValue(key, value) : entry;
    }
}
