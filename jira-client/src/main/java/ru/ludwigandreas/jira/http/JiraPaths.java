package ru.ludwigandreas.jira.http;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Assembles REST paths from fixed text and caller-supplied segments, encoding the segments.
 *
 * <p>Not a nicety. An issue key is normally {@code ABC-123}, but a Structure item id can contain a slash,
 * a group name can contain a space or a {@code #}, and a filter name can contain anything at all.
 * Concatenating those into a path unencoded produces a request to a different resource than the one
 * intended - or, with a segment of {@code ../..}, to a different endpoint entirely.
 *
 * <p>Encoding is per segment, so {@code /} inside a value becomes {@code %2F} and does not split the path.
 * {@link QueryParams} does the same job for the query string.
 */
public final class JiraPaths {

    private JiraPaths() {
    }

    /**
     * Joins a prefix and path segments, encoding each segment.
     *
     * @param prefix fixed path prefix, for example {@code /rest/api/2/issue}
     * @param segments values to append, each encoded
     * @return the assembled path
     */
    public static String of(String prefix, Object... segments) {
        StringBuilder out = new StringBuilder(prefix);
        for (Object segment : segments) {
            out.append('/').append(encode(String.valueOf(segment)));
        }
        return out.toString();
    }

    /**
     * Percent-encodes one path segment.
     *
     * <p>{@code URLEncoder} targets form bodies, so its output is adjusted twice: {@code +} back to
     * {@code %20}, because a plus in a path is a literal plus and not a space, and {@code %2B} left alone
     * so a genuine plus survives. The other two rewrites restore characters that are legal, unambiguous and
     * far more readable unencoded in a path.
     *
     * @param segment the raw segment
     * @return the segment, safe to embed between slashes
     */
    public static String encode(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("%7E", "~")
                .replace("%27", "'");
    }
}
