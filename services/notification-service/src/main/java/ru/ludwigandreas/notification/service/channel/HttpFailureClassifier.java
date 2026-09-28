package ru.ludwigandreas.notification.service.channel;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import ru.ludwigandreas.notification.service.model.FailureClass;

/**
 * Whether an HTTP outcome is worth attempting again.
 *
 * <p>Shared by the chat and webhook channels, because the rule is about HTTP rather than about
 * either provider - and a rule stated twice is a rule that will differ in one of the two the first
 * time somebody adjusts it.
 *
 * <h2>The rule</h2>
 *
 * <ul>
 *   <li><b>Transport failure</b> - connect refused, read timeout, DNS - is retryable. It says nothing
 *       about the request.</li>
 *   <li><b>5xx</b> is retryable. The server is telling us it failed, not that we did.</li>
 *   <li><b>408, 425, 429</b> are retryable despite being 4xx. Each of them literally means "try this
 *       again later", and treating them as terminal is the classic way a rate-limited integration
 *       dead-letters its whole backlog the first time it is throttled.</li>
 *   <li><b>Every other 4xx</b> is terminal. A malformed payload, a rejected address or a revoked
 *       token will be exactly as malformed, rejected and revoked in ten minutes.</li>
 *   <li><b>3xx</b> is terminal. A redirect on an API endpoint is a misconfiguration; following it
 *       would post a notification to an unverified location.</li>
 * </ul>
 *
 * <p>401 and 403 are the uncomfortable case and are deliberately terminal. A rotated credential is
 * the one scenario where retrying would help, and it is much rarer than a permanently wrong one -
 * dead-lettering makes a broken credential visible in minutes, whereas retrying hides it behind a
 * queue that slowly stops moving. The dead letters are replayable from the admin API once the
 * credential is fixed.
 */
public final class HttpFailureClassifier {

    /** 4xx codes that mean "later", not "no". */
    private static final Set<Integer> RETRYABLE_CLIENT_ERRORS = Set.of(
            HttpStatus.REQUEST_TIMEOUT.value(),
            HttpStatus.TOO_EARLY.value(),
            HttpStatus.TOO_MANY_REQUESTS.value());

    private HttpFailureClassifier() {
    }

    public static FailureClass classify(HttpStatusCode status) {
        if (status.is5xxServerError()) {
            return FailureClass.RETRYABLE;
        }
        if (RETRYABLE_CLIENT_ERRORS.contains(status.value())) {
            return FailureClass.RETRYABLE;
        }
        return FailureClass.TERMINAL;
    }

    /**
     * Classifies a thrown failure.
     *
     * <p>Walks the cause chain because the interesting exception is almost never the one thrown: a
     * read timeout arrives as a {@code ResourceAccessException} wrapping a
     * {@link SocketTimeoutException}, and matching only on the outer type would classify every
     * transport failure by the same rule as a serialization bug.
     */
    public static FailureClass classify(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof IOException) {
                return FailureClass.RETRYABLE;
            }
            if (cause == cause.getCause()) {
                break;
            }
        }
        // Anything else that escaped a channel is unclassified, and an unclassified failure is more
        // likely a transient fault or a bug than a permanent rejection by the provider.
        return FailureClass.RETRYABLE;
    }
}
