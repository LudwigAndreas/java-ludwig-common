package ru.ludwigandreas.restclient.core;

import java.net.URI;

/**
 * Remembers the URI template of the call the current thread is about to make.
 *
 * <h2>Why a second mechanism exists</h2>
 *
 * <p>{@link UriTemplates} reads the template from the current Micrometer {@link
 * io.micrometer.observation.Observation}, which is exact - and only present when the application has
 * an observation registry <em>with a handler</em>. A service with no metrics and no tracing has
 * neither, and would silently fall back to writing expanded paths into its audit records and its
 * exception messages, which is the one place an unbounded, caller-chosen value must not appear.
 *
 * <p>So the blocking clients also install a {@code UriBuilderFactory} that records the template as it
 * expands it. Expansion happens on the calling thread, immediately before the exchange, which is why
 * a {@code ThreadLocal} is sound here and would not be anywhere else in this module.
 *
 * <h2>The exactness rule</h2>
 *
 * <p>A recorded template is only used when the expanded URI it produced is the URI actually being
 * sent. Without that check, a {@code uri(...)} call whose request was never executed would leave a
 * stale template on a pooled thread, and the next unrelated call would be attributed to it - a
 * wrong-but-plausible value, which is worse than no value.
 *
 * <p>The entry is not cleared after use. Clearing would cost a second {@code ThreadLocal} write on
 * every request to protect against a case the URI check already handles, and a stale entry that can
 * never match is inert.
 */
public final class UriTemplateCapture {

    private static final ThreadLocal<Captured> CURRENT = new ThreadLocal<>();

    private UriTemplateCapture() {
    }

    /** Records that {@code template} expanded to {@code uri} on this thread. */
    public static void record(String template, URI uri) {
        CURRENT.set(new Captured(template, uri));
    }

    /** The template that produced {@code uri} on this thread, or {@code null}. */
    public static String templateFor(URI uri) {
        Captured captured = CURRENT.get();
        return captured != null && captured.uri().equals(uri) ? captured.template() : null;
    }

    /** Drops the recorded entry; used by tests that assert on the absence of one. */
    public static void clear() {
        CURRENT.remove();
    }

    private record Captured(String template, URI uri) {
    }
}
