package ru.ludwigandreas.restclient.core;

/**
 * What one request asked to do differently.
 *
 * @param retry       {@code TRUE}/{@code FALSE} when {@code X-Ludwig-Retry} was set, else
 *                    {@code null} meaning "use the configured policy"
 * @param maxAttempts a lower attempt limit for this request, or {@code null}
 */
public record RequestOverrides(Boolean retry, Integer maxAttempts) {

    /** No overrides - the overwhelmingly common case, allocated once. */
    public static final RequestOverrides NONE = new RequestOverrides(null, null);

    /** The effective attempt limit, which a request may lower and never raise. */
    public int effectiveMaxAttempts(int configured) {
        return maxAttempts == null ? configured : Math.min(maxAttempts, configured);
    }
}
