package ru.ludwigandreas.jira.error;

import java.util.List;
import java.util.Map;

/**
 * Jira answered with a non-2xx status.
 *
 * <p>Carries the parsed {@code errorMessages}/{@code errors} pair that every Jira REST endpoint returns on
 * failure, so a caller can tell "summary is required" from "you may not edit this issue" without
 * re-parsing the body. When the body was not a Jira error collection at all - a proxy's HTML page, an empty
 * 502 - the collection is empty and {@link #getBody()} holds the raw text.
 */
public class JiraApiException extends JiraException {

    private static final long serialVersionUID = 1L;

    /** How much of an unrecognised response body is quoted in the exception message. */
    private static final int BODY_EXCERPT_LIMIT = 512;

    private final int status;
    private final String method;
    private final String uri;
    private final ErrorCollection errors;
    private final String body;

    /**
     * Creates the exception for a non-2xx response.
     *
     * @param status the HTTP status Jira answered with
     * @param method the failed request's HTTP method
     * @param uri the failed request's absolute URI
     * @param errors the parsed Jira error collection, or {@code null} when the body was not one
     * @param body the raw response body
     */
    public JiraApiException(int status, String method, String uri, ErrorCollection errors, String body) {
        super(buildMessage(status, method, uri, errors, body));
        this.status = status;
        this.method = method;
        this.uri = uri;
        this.errors = errors == null ? ErrorCollection.empty() : errors;
        this.body = body;
    }

    private static String buildMessage(int status, String method, String uri, ErrorCollection errors, String body) {
        String detail = errors == null || errors.isEmpty() ? abbreviate(body) : errors.describe();
        return "Jira returned HTTP " + status + " for " + method + " " + uri
                + (detail.isEmpty() ? "" : ": " + detail);
    }

    private static String abbreviate(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String oneLine = text.strip().replaceAll("\\s+", " ");
        return oneLine.length() <= BODY_EXCERPT_LIMIT
                ? oneLine
                : oneLine.substring(0, BODY_EXCERPT_LIMIT) + "...";
    }

    /** HTTP status code Jira answered with. */
    public int getStatus() {
        return status;
    }

    /** HTTP method of the request that failed. */
    public String getMethod() {
        return method;
    }

    /** Absolute URI of the request that failed, with its query string. */
    public String getUri() {
        return uri;
    }

    /** Parsed Jira error collection; never {@code null}, possibly empty. */
    public ErrorCollection getErrors() {
        return errors;
    }

    /** Raw response body, for the cases the error collection does not describe. */
    public String getBody() {
        return body;
    }

    /** Global error messages Jira reported, in the order it reported them. */
    public List<String> getErrorMessages() {
        return errors.errorMessages();
    }

    /** Per-field errors Jira reported, keyed by field id ({@code summary}, {@code customfield_10001}). */
    public Map<String, String> getFieldErrors() {
        return errors.errors();
    }
}
