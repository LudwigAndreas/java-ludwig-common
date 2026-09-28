package ru.ludwigandreas.messaging.error;

/**
 * The problem codes this module emits.
 *
 * <p>Fewer than most starters here have, and that is the honest shape of the module: a consumer's
 * failures are answered by a dead-letter topic, not by an HTTP response. These two exist because a
 * service may legitimately re-raise them from its own surface - a replay endpoint that re-feeds a
 * dead-lettered record and wants to tell the operator why it will not load, most obviously - and
 * because a failure with no message at all is a failure whose log line says
 * {@code UnsupportedEventVersionException} and nothing more.
 *
 * <p>The keys in {@code i18n/ludwig-messaging-messages.properties} are exactly these values, plus a
 * {@code .title} sibling for each.
 */
public final class MessagingProblemCodes {

    /** Namespace prefix for every code below. */
    public static final String PREFIX = "ludwig.messaging.error.";

    /**
     * The record's payload schema version is outside what this consumer accepts.
     *
     * <p>A 422: the record is well-formed and addressed to the right consumer, and its content cannot be
     * acted on. Not retryable - the version will not change on a redelivery - which is why the
     * deserializer's failure is registered non-retryable and the record goes straight to the dead-letter
     * topic.
     */
    public static final String UNSUPPORTED_EVENT_VERSION = PREFIX + "unsupported-event-version";

    /**
     * A consumer declared both that it cares about ordering and that it wants non-blocking retries.
     *
     * <p>Reported as a startup failure rather than a request failure - a deployment cannot be allowed to
     * run in this configuration - but it carries a code and a message because the sentence is the whole
     * value of the check. See {@code MessagingConfigurationValidator}.
     */
    public static final String ORDERING_CONFLICTS_WITH_RETRY_TOPICS =
            PREFIX + "ordering-conflicts-with-retry-topics";

    private MessagingProblemCodes() {
    }
}
