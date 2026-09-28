package ru.ludwigandreas.idempotency.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * A hash of the request a key was used for.
 *
 * <h2>Why this is a correctness requirement and not a nicety</h2>
 *
 * <p>Without it, a client that recycles keys silently receives the wrong resource's response. That is
 * a data-integrity failure, and it presents to whoever has to diagnose it as "the API returned
 * somebody else's data" - with a clean access log, a 200, and no error anywhere. Storing the
 * fingerprint turns it into a 422 naming the key that was reused, which is a bug report the client's
 * author can act on.
 *
 * <h2>Why a hash rather than the body</h2>
 *
 * <p>It is cheaper, and it avoids a table that accumulates request payloads. The second reason is the
 * stronger one: a table of bodies is a table of personal data with a retention window measured in
 * days, which the redaction rules in {@code audit-core} would then have to cover, and which every
 * future reader of the claim table would have to be entitled to see. A hash is not reversible and
 * carries no data-protection obligation.
 *
 * <h2>Why the body is canonicalised first</h2>
 *
 * <p>Two byte-identical requests are obviously the same request, but the interesting case is a retry
 * that re-serialised its body: a different JSON library, a different key order, different whitespace,
 * the same ask. Hashing raw bytes would call that a fingerprint mismatch and refuse a legitimate
 * retry with a 422 - turning this feature from a safety net into an outage. So a JSON body is parsed
 * and re-emitted with object keys sorted before hashing, and a body that is not JSON is hashed as it
 * arrived.
 *
 * <p>Array order is deliberately <em>preserved</em>. A JSON array is ordered by specification, and two
 * requests whose arrays differ only in order are two different asks - a batch of recipients in a
 * different order may still be the same intent, but this module is in no position to decide that for
 * somebody else's payload, and guessing wrong here means treating a genuinely different request as a
 * duplicate. That is the one failure mode worse than refusing a retry.
 */
public final class RequestFingerprint {

    /**
     * The digest. SHA-256 because the fingerprint is a 64-character column and a collision here would
     * answer one request with another's response.
     */
    private static final String ALGORITHM = "SHA-256";

    private final ObjectMapper objectMapper;

    /**
     * Creates the fingerprinter.
     *
     * @param objectMapper the mapper used to canonicalise a JSON body. The application's own, so that
     *                     a body its controllers can parse is a body this can canonicalise
     */
    public RequestFingerprint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * The fingerprint of one request.
     *
     * @param method      the HTTP method, or any other stable verb
     * @param path        the request path
     * @param contentType the body's media type, or {@code null}
     * @param body        the raw body bytes, possibly empty
     * @return a lower-case hex SHA-256 digest
     */
    public String of(String method, String path, String contentType, byte[] body) {
        StringBuilder material = new StringBuilder();
        material.append(method == null ? "" : method.toUpperCase(Locale.ROOT)).append('\n');
        material.append(path == null ? "" : path).append('\n');
        material.append(baseMediaType(contentType)).append('\n');
        material.append(canonicalBody(contentType, body));
        return digest(material.toString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The fingerprint of an arbitrary byte payload - a Kafka record's value, say.
     *
     * @param scope   the scope the key is claimed in, so the same payload under two scopes is two
     *                fingerprints and a scope rename cannot make an old claim look like it matches
     * @param payload the payload
     * @return a lower-case hex SHA-256 digest
     */
    public String ofPayload(String scope, byte[] payload) {
        MessageDigest digest = newDigest();
        digest.update(scope.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) '\n');
        if (payload != null) {
            digest.update(payload);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * The media type without its parameters.
     *
     * <p>{@code charset} and {@code boundary} are dropped: a retry that spelled
     * {@code application/json;charset=UTF-8} where the original said {@code application/json} is the
     * same request, and a multipart boundary is random per request by design - including it would
     * make every multipart retry a fingerprint mismatch.
     */
    private static String baseMediaType(String contentType) {
        if (contentType == null) {
            return "";
        }
        int parameters = contentType.indexOf(';');
        String base = parameters < 0 ? contentType : contentType.substring(0, parameters);
        return base.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * The body, canonicalised when it is JSON and verbatim otherwise.
     *
     * <p>A body that claims to be JSON and is not parses as a failure here, and is then hashed raw
     * rather than rejected. Rejecting would mean this module answering a malformed-body error before
     * the handler that owns that error has seen the request, in a different shape from the one the
     * service's own pipeline produces.
     */
    private String canonicalBody(String contentType, byte[] body) {
        if (body == null || body.length == 0) {
            return "";
        }
        if (!baseMediaType(contentType).endsWith("json")) {
            return new String(body, StandardCharsets.UTF_8);
        }
        try {
            return canonicalise(objectMapper.readTree(body)).toString();
        } catch (java.io.IOException e) {
            // Not rejected: see the method comment. The raw bytes still fingerprint the request, and
            // a body that does not parse will be refused by the handler's own pipeline in a moment.
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    /**
     * Rewrites a parsed body with every object's keys in sorted order.
     *
     * <p>Recursive over objects and arrays. Arrays keep their order - see the class comment.
     */
    private JsonNode canonicalise(JsonNode node) {
        if (node.isObject()) {
            Map<String, JsonNode> sorted = new TreeMap<>();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                sorted.put(field.getKey(), canonicalise(field.getValue()));
            }
            return objectMapper.createObjectNode().setAll(sorted);
        }
        if (node.isArray()) {
            List<JsonNode> elements = new ArrayList<>(node.size());
            node.forEach(element -> elements.add(canonicalise(element)));
            return objectMapper.createArrayNode().addAll(elements);
        }
        return node;
    }

    private String digest(byte[] material) {
        return HexFormat.of().formatHex(newDigest().digest(material));
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance(ALGORITHM);
        } catch (NoSuchAlgorithmException e) {
            // Every JVM this platform runs on ships SHA-256; a JVM that does not cannot run the
            // security starter either. Wrapped rather than declared, because making every call site
            // handle an impossible checked exception is how a fingerprint ends up optional.
            throw new IllegalStateException(ALGORITHM + " is not available in this JVM", e);
        }
    }
}
