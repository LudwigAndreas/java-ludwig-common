package ru.ludwigandreas.observability.correlation;

import java.security.SecureRandom;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns whatever a caller sent into a correlation id that is safe to echo, log and forward.
 *
 * <h2>The inbound value is untrusted input</h2>
 *
 * <p>A correlation id arrives in a header from outside, and it then gets written into an HTTP
 * response header, into every log line of the request, and onto a Kafka record header. Each of those
 * is an injection point: a {@code \r\n} in a response header splits the response, a control
 * character corrupts the aggregator's JSON record, and an unbounded length multiplies the log volume
 * of one request by whatever the attacker chose to send.
 *
 * <p>So the value is validated rather than sanitized. A value that does not match the allow-list is
 * replaced wholesale with a generated id, and is not repaired by stripping the offending characters:
 * repairing lets an attacker control the surviving part, and there is nothing to preserve - a
 * malformed id correlates with nothing anyway.
 */
public class CorrelationIdResolver {

    /** 128 bits, the width of a W3C trace id, rendered as two hex characters per byte. */
    private static final int ID_BYTES = 16;

    private static final int BYTE_MASK = 0xFF;
    private static final int NIBBLE_MASK = 0x0F;
    private static final int NIBBLE_BITS = 4;

    private static final Logger log = LoggerFactory.getLogger(CorrelationIdResolver.class);

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    /**
     * Not {@code Random}: ids end up in support tickets and log queries as handles to one customer's
     * request, so a predictable sequence would let one caller guess - and then fetch by searching -
     * the ids of requests it did not make.
     */
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Pattern allowedPattern;
    private final int maxLength;
    private final boolean generateIfAbsent;

    /**
     * @throws IllegalArgumentException if {@code maxLength} is not positive
     * @throws java.util.regex.PatternSyntaxException if {@code allowedPattern} is not a valid regex
     */
    public CorrelationIdResolver(String allowedPattern, int maxLength, boolean generateIfAbsent) {
        // Both checks exist to fail the context refresh rather than the first request. A non-positive
        // cap rejects every inbound id and silently generates a fresh one per hop, which does not look
        // broken from inside one service - it looks like callers are not sending ids, while the chain
        // that joins services together is severed. A bad regex would likewise only surface on the
        // first request that carried a header.
        if (maxLength <= 0) {
            throw new IllegalArgumentException(
                    "ludwig.observability.correlation.max-length must be positive but was " + maxLength
                            + "; a non-positive cap rejects every inbound correlation id");
        }
        this.allowedPattern = Pattern.compile(allowedPattern);
        this.maxLength = maxLength;
        this.generateIfAbsent = generateIfAbsent;
    }

    /**
     * Resolves the id for one unit of work.
     *
     * @param inboundValues candidate header values in priority order; nulls and blanks are skipped,
     *                      so a caller can pass "the configured header, then the fallbacks" without
     *                      filtering first
     * @param traceId       supplier of the current trace id, consulted before generating a random id
     * @return a non-null id, or {@code null} only when nothing was sent and generation is disabled
     */
    public String resolve(List<String> inboundValues, Supplier<String> traceId) {
        for (String candidate : inboundValues) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            if (isAcceptable(candidate)) {
                return candidate;
            }
            // Logged once per rejection at DEBUG, never at WARN: a noisy client would otherwise let
            // an outsider drive this service's log volume, which is the same problem the length cap
            // exists to prevent. The rejected value itself is never logged, for the same reason it is
            // not echoed - it is unvalidated bytes.
            log.debug("Rejected inbound correlation id: it does not match {} or exceeds {} characters",
                    allowedPattern.pattern(), maxLength);
            break;
        }
        if (!generateIfAbsent) {
            return null;
        }
        // Reusing the trace id when there is one is what makes the two ids line up for a request that
        // originated here: an engineer holding a correlation id from a log line can paste it straight
        // into the tracing backend instead of having to find the trace by timestamp.
        String currentTraceId = traceId.get();
        if (currentTraceId != null && !currentTraceId.isBlank() && isAcceptable(currentTraceId)) {
            return currentTraceId;
        }
        return generate();
    }

    private boolean isAcceptable(String candidate) {
        return candidate.length() <= maxLength && allowedPattern.matcher(candidate).matches();
    }

    /**
     * A fresh 128-bit id as 32 hex characters - the same shape and entropy as a W3C trace id, so the
     * two are visually interchangeable in a log line and neither is mistaken for the other's format.
     */
    public String generate() {
        byte[] bytes = new byte[ID_BYTES];
        RANDOM.nextBytes(bytes);
        char[] out = new char[ID_BYTES * 2];
        for (int i = 0; i < bytes.length; i++) {
            int b = bytes[i] & BYTE_MASK;
            out[i * 2] = HEX[b >>> NIBBLE_BITS];
            out[i * 2 + 1] = HEX[b & NIBBLE_MASK];
        }
        return new String(out);
    }
}
