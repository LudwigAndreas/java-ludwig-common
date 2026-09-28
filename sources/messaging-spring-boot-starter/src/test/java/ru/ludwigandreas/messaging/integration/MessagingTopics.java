package ru.ludwigandreas.messaging.integration;

/** The topics the container suite uses, one per consumer so no test observes another's records. */
final class MessagingTopics {

    static final String TYPED = "it.typed";
    static final String TEXT = "it.text";
    static final String ORDERED = "it.ordered";
    static final String DEDUPED = "it.deduped";
    static final String QUIET = "it.quiet";

    private MessagingTopics() {
    }
}
