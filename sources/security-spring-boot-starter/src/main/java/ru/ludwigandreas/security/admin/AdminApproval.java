package ru.ludwigandreas.security.admin;

import java.time.Instant;
import java.util.UUID;
import ru.ludwigandreas.security.principal.PrincipalType;

/**
 * A privileged administrative request held for a second person's approval, and what became of it.
 *
 * @param id            the approval's id
 * @param operation     the operation the request performs
 * @param request       the stored request; its body is gone once the approval is terminal
 * @param requestedBy   the requester's subject
 * @param requesterType the requester's principal type, for re-checking their authority at approval
 * @param requestedAt   when it was requested
 * @param expiresAt     when it expires undecided
 * @param state         where it stands
 * @param decidedBy     who approved, rejected or withdrew it, or null
 * @param decidedAt     when, or null
 * @param outcome       how the executed request was answered, or null
 */
// SUPPRESS CHECKSTYLE ParameterNumber - a record's canonical constructor; the store and the service name every
// component, and grouping them further would split one stored row across holder types.
@SuppressWarnings("checkstyle:ParameterNumber")
public record AdminApproval(UUID id, String operation, StoredRequest request, String requestedBy,
                            PrincipalType requesterType, Instant requestedAt, Instant expiresAt,
                            AdminApprovalState state, String decidedBy, Instant decidedAt, Outcome outcome) {

    /** The most of a request body that is held for approval; a larger one is refused with 413. */
    public static final int MAX_REQUEST_BODY = 64 * 1024;

    /** The most of an executed request's response body that is kept. */
    public static final int MAX_OUTCOME_BODY = 16 * 1024;

    /**
     * The request as it will be executed, and only what a handler may read of it.
     *
     * @param method         the HTTP method
     * @param path           the path, without the context path
     * @param query          the raw query string, or null
     * @param contentType    the request's {@code Content-Type}, or null
     * @param acceptLanguage the request's {@code Accept-Language}, or null
     * @param reason         the stated {@code Admin-Reason}
     * @param body           the body, empty when there was none or once the approval is terminal
     */
    public record StoredRequest(String method, String path, String query, String contentType,
                                String acceptLanguage, String reason, byte[] body) {

        /** Normalises the body. */
        public StoredRequest {
            body = body == null ? new byte[0] : body;
        }

        /**
         * This request without its body, as kept once the approval is terminal or shown in a listing.
         *
         * @return the copy
         */
        public StoredRequest withoutBody() {
            return new StoredRequest(method, path, query, contentType, acceptLanguage, reason, new byte[0]);
        }
    }

    /**
     * How the executed request was answered.
     *
     * @param status      the HTTP status
     * @param contentType its {@code Content-Type}, or null
     * @param body        its body, at most {@link #MAX_OUTCOME_BODY} bytes
     */
    public record Outcome(int status, String contentType, byte[] body) {

        /** Normalises and caps the body. */
        public Outcome {
            body = body == null ? new byte[0]
                    : body.length > MAX_OUTCOME_BODY ? java.util.Arrays.copyOf(body, MAX_OUTCOME_BODY) : body;
        }
    }
}
