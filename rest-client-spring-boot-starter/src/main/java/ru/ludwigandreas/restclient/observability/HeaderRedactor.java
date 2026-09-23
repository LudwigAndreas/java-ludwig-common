package ru.ludwigandreas.restclient.observability;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.http.HttpHeaders;

/**
 * Replaces credentials with {@code ****} before anything is written down.
 *
 * <p>Applied to every header this module logs, records in an audit entry, or attaches to an
 * exception - one place rather than three, because a redaction rule that exists in two of three
 * places is a rule that leaks from the third.
 *
 * <p>The header list is matched case-insensitively, which is not pedantry: HTTP header names are
 * case-insensitive by specification, and a partner that sends {@code authorization} in lower case
 * would otherwise sail straight past a list containing {@code Authorization}.
 *
 * <p>Body redaction is structural, not textual. A regular expression over the raw JSON would also
 * match the string {@code "password"} appearing as a <em>value</em>, and would miss a field nested
 * three objects deep. Parsing and walking the tree is slower and correct, and it only ever runs when
 * a client has explicitly opted into body logging - which is the one place where being slow and
 * correct is obviously the right trade.
 */
public class HeaderRedactor {

    private static final String MASK = "****";

    private final Set<String> redactedHeaders;
    private final Set<String> redactedFields;
    private final ObjectMapper objectMapper;
    /** Creates a redactor over one client's header and field lists. */
    public HeaderRedactor(List<String> redactedHeaders, List<String> redactedFields,
                          ObjectMapper objectMapper) {
        this.redactedHeaders = lowerCased(redactedHeaders);
        this.redactedFields = lowerCased(redactedFields);
        this.objectMapper = objectMapper;
    }

    /** A copy of {@code headers} with the configured names masked. */
    public Map<String, List<String>> redact(HttpHeaders headers) {
        if (headers == null || headers.isEmpty()) {
            return Map.of();
        }
        Map<String, List<String>> out = new LinkedHashMap<>();
        headers.forEach((name, values) -> out.put(name, redactValues(name, values)));
        return out;
    }

    private List<String> redactValues(String name, List<String> values) {
        if (!redactedHeaders.contains(name.toLowerCase(Locale.ROOT))) {
            return List.copyOf(values);
        }
        List<String> masked = new ArrayList<>(values.size());
        for (int i = 0; i < values.size(); i++) {
            masked.add(MASK);
        }
        return masked;
    }

    /**
     * {@code body} with the configured field names masked, truncated to {@code maxBytes}.
     *
     * <p>A body that is not JSON is truncated and otherwise left alone rather than dropped: a
     * partner's plain-text error message is often the only explanation of a failure, and refusing to
     * log it because it could not be parsed would throw away the useful case to protect against a
     * hypothetical one. Form-encoded bodies are the exception - they routinely carry credentials in
     * exactly the shape this class cannot see into - so an unparseable body with a
     * {@code application/x-www-form-urlencoded} content type is replaced wholesale.
     */
    public String redactBody(String body, String contentType, int maxBytes) {
        if (body == null || body.isEmpty()) {
            return body;
        }
        if (contentType != null && contentType.contains("x-www-form-urlencoded")) {
            return MASK;
        }
        String redacted = isJson(contentType) ? redactJson(body) : body;
        return truncate(redacted, maxBytes);
    }

    private boolean isJson(String contentType) {
        return contentType != null && contentType.contains("json");
    }

    private String redactJson(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            walk(root);
            return objectMapper.writeValueAsString(root);
        } catch (JsonProcessingException ex) {
            // Claimed to be JSON and is not. Truncation still applies; the raw text is kept because a
            // malformed body is usually the reason the call is being investigated at all.
            return body;
        }
    }

    private void walk(JsonNode node) {
        if (node instanceof ObjectNode object) {
            List<String> names = new ArrayList<>();
            object.fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                if (redactedFields.contains(name.toLowerCase(Locale.ROOT))) {
                    object.put(name, MASK);
                } else {
                    walk(object.get(name));
                }
            }
        } else if (node instanceof ArrayNode array) {
            array.forEach(this::walk);
        }
    }

    private String truncate(String value, int maxBytes) {
        if (value.length() <= maxBytes) {
            return value;
        }
        // The marker matters: a truncated body that looks complete is a body somebody will read as
        // evidence that a field was absent.
        return value.substring(0, maxBytes) + "...[truncated " + (value.length() - maxBytes) + " chars]";
    }

    private static Set<String> lowerCased(List<String> values) {
        Set<String> out = new TreeSet<>();
        if (values != null) {
            values.forEach(value -> out.add(value.toLowerCase(Locale.ROOT)));
        }
        return out;
    }
}
