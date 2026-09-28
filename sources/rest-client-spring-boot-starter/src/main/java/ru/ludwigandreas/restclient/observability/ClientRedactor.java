package ru.ludwigandreas.restclient.observability;

import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import ru.ludwigandreas.audit.redaction.ConfiguredNamesSensitivityClassifier;
import ru.ludwigandreas.audit.redaction.Redactor;
import ru.ludwigandreas.audit.redaction.SensitivityClassifier;

/**
 * Binds one named client's redaction lists to the platform's shared {@link Redactor}.
 *
 * <p>What is left of this module's {@code ClientRedactor} after the audit consolidation. Everything that
 * class actually did - the case-insensitive header match, the structural JSON walk, the wholesale
 * replacement of a form-encoded body, the truncation marker - moved into {@link Redactor} unchanged and is
 * covered there by this module's own ported tests. What stays here is the part that is genuinely
 * client-scoped: <em>which</em> names this client redacts, which is per-partner configuration and belongs
 * next to the client, and the client name as the provenance a classifier sees.
 *
 * <p>The per-client list is <em>composed with</em> the deployment-wide classifier rather than replacing it,
 * so {@code ludwig.audit.redaction} rules still apply to partner traffic - a body field called
 * {@code client_secret} is masked whether or not this client's list happens to name it. A per-client list
 * can only widen what is masked, never narrow it: narrowing is the operation that leaks, and a module must
 * not be able to undo a platform rule.
 *
 * <p>Applied to every header this module logs, records in an audit entry, or attaches to an exception - one
 * place rather than three, because a redaction rule that exists in two of three places is a rule that leaks
 * from the third.
 */
public class ClientRedactor {

    private final String clientName;
    private final Redactor redactor;
    private final int maxBodySize;

    /**
     * Creates a redactor over one client's header and field lists.
     *
     * @param clientName      the named client, passed as the provenance so a deployment can make a whole
     *                        partner's traffic sensitive by prefix
     * @param shared          the platform's classifier, from {@code ludwig.audit.redaction}
     * @param redactedHeaders header names this client masks
     * @param redactedFields  body field names this client masks
     * @param maxBodySize     the length beyond which a logged body is truncated
     */
    public ClientRedactor(String clientName, SensitivityClassifier shared, List<String> redactedHeaders,
                          List<String> redactedFields, int maxBodySize) {
        this.clientName = clientName;
        this.maxBodySize = maxBodySize;
        this.redactor = new Redactor(SensitivityClassifier.anyOf(
                shared,
                new ConfiguredNamesSensitivityClassifier(redactedHeaders),
                new ConfiguredNamesSensitivityClassifier(redactedFields)));
    }

    /** A copy of {@code headers} with the sensitive names masked. */
    public Map<String, List<String>> redact(HttpHeaders headers) {
        return redactor.redactHeaders(clientName, headers);
    }

    /**
     * {@code body} with the sensitive field names masked, truncated to {@code maxBytes}.
     *
     * @param body        the body
     * @param contentType the declared content type, or {@code null}
     * @param maxBytes    the truncation limit
     * @return the redacted body
     */
    public String redactBody(String body, String contentType, int maxBytes) {
        return redactor.redactBody(clientName, body, contentType, maxBytes);
    }

    /** {@code body} redacted and truncated to this client's configured limit. */
    public String redactBody(String body, String contentType) {
        return redactBody(body, contentType, maxBodySize);
    }
}
