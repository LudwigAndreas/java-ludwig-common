package ru.ludwigandreas.audit.redaction;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Replaces sensitive values with {@link Redaction#MASK} across every shape this platform writes down.
 *
 * <p>The masking half of the split described on {@link SensitivityClassifier}: a
 * {@code Redactor} does not decide what is sensitive, it decides what happens to a value once a
 * classifier has said so. Which is why there is one of these and four classifiers.
 *
 * <p>Applied at the point an audit entry is <em>built</em>, never at the sink. That rule comes from
 * {@code AuditingSourceChangeListener}, which said why: a custom logger persisting to a database or
 * shipping to a SIEM can't forget to do it if it never receives the unredacted value in the first
 * place.
 *
 * <h2>Five shapes, because the platform writes five</h2>
 *
 * <ul>
 *   <li>scalars - a changed configuration value, a setting's old and new value;</li>
 *   <li>attribute maps, walked recursively, because an audit event's {@code attributes} nest;</li>
 *   <li>header multimaps, {@code name -> values}, which is what an HTTP client has;</li>
 *   <li>JSON bodies, walked <em>structurally</em>;</li>
 *   <li>form-encoded bodies, replaced wholesale.</li>
 * </ul>
 *
 * <p>The last two are {@code HeaderRedactor}'s and are the reason a lowest-common-denominator merge of
 * the three old redactors would have been a regression rather than a consolidation. Its own javadoc
 * made the case and it is kept verbatim on {@link #redactBody}.
 */
public class Redactor {

    private final SensitivityClassifier classifier;
    private final ObjectMapper objectMapper;

    /**
     * A redactor over {@code classifier}, with its own {@link ObjectMapper}.
     *
     * <p>Its own rather than the application's, because the application's is configured for the
     * application's models - a {@code FAIL_ON_UNKNOWN_PROPERTIES} or a naming strategy set for domain
     * objects has no business deciding whether a partner's body can be parsed at all. Prefer
     * {@link #Redactor(SensitivityClassifier, ObjectMapper)} where a plain mapper is already to hand.
     *
     * @param classifier decides what is sensitive
     */
    public Redactor(SensitivityClassifier classifier) {
        this(classifier, new ObjectMapper());
    }

    /**
     * A redactor over {@code classifier}, using {@code objectMapper} for structural body redaction.
     *
     * @param classifier   decides what is sensitive; null classifies nothing, which is the correct
     *                     behaviour for a deployment that configured no rules and is loud about it
     *                     through the classifier it chose rather than through a crash here
     * @param objectMapper used to parse and re-serialise JSON bodies
     */
    public Redactor(SensitivityClassifier classifier, ObjectMapper objectMapper) {
        this.classifier = classifier == null ? SensitivityClassifier.none() : classifier;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
    }

    /** The classifier this redactor asks. */
    public SensitivityClassifier classifier() {
        return classifier;
    }

    /** Whether a value under {@code key} from {@code provenance} would be masked. */
    public boolean isSensitive(String provenance, String key) {
        return classifier.isSensitive(provenance, key);
    }

    /**
     * {@code value} itself, or the mask if it is sensitive.
     *
     * <p>A null value is returned unchanged rather than masked: "there was no value" is a fact the
     * trail should keep, and replacing it with the marker would make an unset key and a redacted one
     * the same row - the failure mode {@link Redaction} refuses to make configurable.
     *
     * @param provenance where the value came from, or {@code null}
     * @param key        the key it is under
     * @param value      the value
     * @return the value, or {@link Redaction#MASK}
     */
    public Object redactValue(String provenance, String key, Object value) {
        if (value == null || !classifier.isSensitive(provenance, key)) {
            return value;
        }
        return Redaction.MASK;
    }

    /**
     * The string form of {@link #redactValue}, for the callers whose values are already encoded.
     *
     * @param provenance where the value came from, or {@code null}
     * @param key        the key it is under
     * @param value      the encoded value
     * @return the value, or {@link Redaction#MASK}
     */
    public String redactString(String provenance, String key, String value) {
        if (value == null || !classifier.isSensitive(provenance, key)) {
            return value;
        }
        return Redaction.MASK;
    }

    /**
     * A copy of {@code attributes} with sensitive entries masked, recursing into nested maps.
     *
     * <p>Recursion matters for the same reason the JSON walk is a walk: an audit attribute map is
     * assembled from a module's own structures, and a credential two maps down is exactly the one
     * nobody notices. Lists are walked too, for a list of maps.
     *
     * @param provenance where the values came from, or {@code null}
     * @param attributes the map; null yields an empty map
     * @return a new map, iteration order preserved
     */
    public Map<String, Object> redactAttributes(String provenance, Map<String, ?> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        attributes.forEach((key, value) -> out.put(key, redactNested(provenance, key, value)));
        return out;
    }

    private Object redactNested(String provenance, String key, Object value) {
        if (classifier.isSensitive(provenance, key) && value != null) {
            return Redaction.MASK;
        }
        if (value instanceof Map<?, ?> nested) {
            Map<String, Object> out = new LinkedHashMap<>();
            nested.forEach((nestedKey, nestedValue) -> out.put(String.valueOf(nestedKey),
                    redactNested(provenance, String.valueOf(nestedKey), nestedValue)));
            return out;
        }
        if (value instanceof List<?> items) {
            // Reached only for a key that is not itself sensitive - a sensitive key's whole value was
            // replaced above, list or not, because the length of a list of credentials is itself
            // information. What this walk is for is a list of maps, whose entries have names of their
            // own: a list of enrichment stages, each with an api-key.
            List<Object> out = new ArrayList<>(items.size());
            items.forEach(item -> out.add(redactNested(provenance, key, item)));
            return out;
        }
        return value;
    }

    /**
     * A copy of {@code headers} with the sensitive names' values masked.
     *
     * <p>Every value of a masked name is replaced, not just the first: a repeated header is one header
     * with several values, and masking one of them would leave the credential in the log next to a
     * mask that says it was handled.
     *
     * @param provenance the client or source the headers belong to, or {@code null}
     * @param headers    the multimap; null yields an empty map
     * @return a new multimap, iteration order preserved
     */
    public Map<String, List<String>> redactHeaders(String provenance, Map<String, ? extends List<String>> headers) {
        if (headers == null || headers.isEmpty()) {
            return Map.of();
        }
        Map<String, List<String>> out = new LinkedHashMap<>();
        headers.forEach((name, values) -> out.put(name, redactHeaderValues(provenance, name, values)));
        return out;
    }

    private List<String> redactHeaderValues(String provenance, String name, List<String> values) {
        if (values == null) {
            return List.of();
        }
        if (!classifier.isSensitive(provenance, name)) {
            return List.copyOf(values);
        }
        List<String> masked = new ArrayList<>(values.size());
        for (int i = 0; i < values.size(); i++) {
            masked.add(Redaction.MASK);
        }
        return masked;
    }

    /**
     * {@code body} with the sensitive field names masked, truncated to {@code maxBytes}.
     *
     * <p><b>Body redaction is structural, not textual.</b> A regular expression over the raw JSON would
     * also match the string {@code "password"} appearing as a <em>value</em>, and would miss a field
     * nested three objects deep. Parsing and walking the tree is slower and correct, and it only ever
     * runs when a caller has explicitly opted into body logging - which is the one place where being
     * slow and correct is obviously the right trade.
     *
     * <p>A body that is not JSON is truncated and otherwise left alone rather than dropped: a partner's
     * plain-text error message is often the only explanation of a failure, and refusing to log it
     * because it could not be parsed would throw away the useful case to protect against a hypothetical
     * one. Form-encoded bodies are the exception - they routinely carry credentials in exactly the shape
     * a JSON walk cannot see into - so a body with an {@code application/x-www-form-urlencoded} content
     * type is replaced wholesale.
     *
     * @param provenance  the client or source the body belongs to, or {@code null}
     * @param body        the body; null or empty is returned unchanged
     * @param contentType the declared content type, or {@code null}
     * @param maxBytes    the length beyond which the body is truncated, with a marker saying so
     * @return the redacted body
     */
    public String redactBody(String provenance, String body, String contentType, int maxBytes) {
        if (body == null || body.isEmpty()) {
            return body;
        }
        if (contentType != null && contentType.contains("x-www-form-urlencoded")) {
            return Redaction.MASK;
        }
        String redacted = isJson(contentType) ? redactJson(provenance, body) : body;
        return truncate(redacted, maxBytes);
    }

    private boolean isJson(String contentType) {
        return contentType != null && contentType.contains("json");
    }

    private String redactJson(String provenance, String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            walk(provenance, root);
            return objectMapper.writeValueAsString(root);
        } catch (JsonProcessingException ex) {
            // Claimed to be JSON and is not. Truncation still applies; the raw text is kept because a
            // malformed body is usually the reason the call is being investigated at all.
            return body;
        }
    }

    private void walk(String provenance, JsonNode node) {
        if (node instanceof ObjectNode object) {
            List<String> names = new ArrayList<>();
            object.fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                if (classifier.isSensitive(provenance, name)) {
                    object.put(name, Redaction.MASK);
                } else {
                    walk(provenance, object.get(name));
                }
            }
        } else if (node instanceof ArrayNode array) {
            array.forEach(element -> walk(provenance, element));
        }
    }

    /**
     * {@code value} cut to {@code maxBytes}, with a marker saying how much was removed.
     *
     * <p>The marker matters: a truncated body that looks complete is a body somebody will read as
     * evidence that a field was absent.
     *
     * @param value    the text
     * @param maxBytes the limit; zero or negative leaves the text alone, so "no limit configured"
     *                 does not silently mean "keep nothing"
     * @return the text, possibly truncated
     */
    public static String truncate(String value, int maxBytes) {
        if (value == null || maxBytes <= 0 || value.length() <= maxBytes) {
            return value;
        }
        return value.substring(0, maxBytes) + "...[truncated " + (value.length() - maxBytes) + " chars]";
    }
}
