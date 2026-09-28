package ru.ludwigandreas.notification.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.SocketTimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.ResourceAccessException;
import ru.ludwigandreas.notification.service.channel.HttpFailureClassifier;
import ru.ludwigandreas.notification.service.model.FailureClass;

/**
 * Getting this wrong in one direction retries a malformed payload eight times over two hours; in the
 * other it dead-letters everything in flight because a server was restarting.
 */
class HttpFailureClassifierTest {

    @ParameterizedTest(name = "{0} is retryable")
    @ValueSource(ints = {500, 502, 503, 504})
    @DisplayName("a 5xx is the server saying it failed, not us")
    void serverErrorsRetry(int status) {
        assertThat(HttpFailureClassifier.classify(HttpStatusCode.valueOf(status)))
                .isEqualTo(FailureClass.RETRYABLE);
    }

    /**
     * The three 4xx codes that literally mean "try this again later". Treating them as terminal is
     * the classic way a throttled integration dead-letters its whole backlog.
     */
    @ParameterizedTest(name = "{0} is retryable despite being a 4xx")
    @ValueSource(ints = {408, 425, 429})
    @DisplayName("408, 425 and 429 mean later, not no")
    void retryableClientErrors(int status) {
        assertThat(HttpFailureClassifier.classify(HttpStatusCode.valueOf(status)))
                .isEqualTo(FailureClass.RETRYABLE);
    }

    @ParameterizedTest(name = "{0} is terminal")
    @ValueSource(ints = {400, 401, 403, 404, 409, 422})
    @DisplayName("every other 4xx will be exactly as wrong in ten minutes")
    void otherClientErrorsAreTerminal(int status) {
        assertThat(HttpFailureClassifier.classify(HttpStatusCode.valueOf(status)))
                .isEqualTo(FailureClass.TERMINAL);
    }

    @Test
    @DisplayName("a redirect on an API endpoint is a misconfiguration, not a transient fault")
    void redirectsAreTerminal() {
        assertThat(HttpFailureClassifier.classify(HttpStatus.MOVED_PERMANENTLY))
                .isEqualTo(FailureClass.TERMINAL);
    }

    /**
     * The interesting exception is almost never the one thrown: a read timeout arrives as a
     * ResourceAccessException wrapping a SocketTimeoutException, and matching only on the outer type
     * would classify every transport failure by the same rule as a serialization bug.
     */
    @Test
    @DisplayName("a transport failure is found by walking the cause chain")
    void transportFailuresRetry() {
        Throwable wrapped = new ResourceAccessException("read timed out",
                new SocketTimeoutException("Read timed out"));

        assertThat(HttpFailureClassifier.classify(wrapped)).isEqualTo(FailureClass.RETRYABLE);
        assertThat(HttpFailureClassifier.classify(new IOException("connection reset")))
                .isEqualTo(FailureClass.RETRYABLE);
    }

    @Test
    @DisplayName("an unclassified escape is retried rather than dead-lettered")
    void unclassifiedFailuresRetry() {
        // A pointless retry costs less than a silently dead-lettered notification somebody was
        // waiting for, so the default leans towards trying again.
        assertThat(HttpFailureClassifier.classify(new IllegalStateException("bug")))
                .isEqualTo(FailureClass.RETRYABLE);
    }

    @Test
    @DisplayName("a self-referential cause chain terminates instead of looping")
    void handlesSelfReferentialCause() {
        RuntimeException looping = new RuntimeException("loops") {
            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };

        assertThat(HttpFailureClassifier.classify(looping)).isEqualTo(FailureClass.RETRYABLE);
    }
}
