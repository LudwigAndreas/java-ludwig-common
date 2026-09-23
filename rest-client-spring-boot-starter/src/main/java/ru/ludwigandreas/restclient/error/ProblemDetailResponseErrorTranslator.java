package ru.ludwigandreas.restclient.error;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.Iterator;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import ru.ludwigandreas.restclient.spi.ErrorContext;
import ru.ludwigandreas.restclient.spi.ResponseErrorTranslator;

/**
 * The translator of last resort: every failed response becomes a
 * {@link RestClientResponseException}, with an RFC 9457 {@code ProblemDetail} attached when the peer
 * sent one.
 *
 * <p>Registered at {@link Ordered#LOWEST_PRECEDENCE} so that a service's own translator always gets
 * first refusal. It never returns {@code null}, which is what lets the pipeline promise that a
 * failed response always produces a typed exception rather than a raw one.
 *
 * <p>Parsing is defensive to the point of paranoia: the document comes from another organisation's
 * service, it arrives while something is already going wrong, and a parser that throws here would
 * replace a useful "billing said 503" with a {@code JsonParseException} pointing at this starter.
 * Anything unparseable falls back to the plain exception with the body snippet attached.
 */
public class ProblemDetailResponseErrorTranslator implements ResponseErrorTranslator, Ordered {

    private static final Logger log = LoggerFactory.getLogger(ProblemDetailResponseErrorTranslator.class);

    private static final String PROBLEM_JSON = "application/problem+json";

    private final ObjectMapper objectMapper;

    public ProblemDetailResponseErrorTranslator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public RuntimeException translate(ErrorContext context) {
        ProblemDetail problem = parseProblem(context);
        String detail = problem != null && problem.getDetail() != null
                ? problem.getDetail()
                : summarize(context);
        return new RestClientResponseException(
                context.clientName(),
                context.correlationId(),
                "%s %s returned %d: %s".formatted(
                        context.method(), context.uriTemplate(), context.statusCode(), detail),
                context.statusCode(),
                context.reasonPhrase(),
                context.headers(),
                context.body(),
                problem);
    }

    /** Runs last, so any translator a service publishes is consulted first. */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    private ProblemDetail parseProblem(ErrorContext context) {
        String contentType = context.contentType();
        if (contentType == null || !contentType.startsWith(PROBLEM_JSON)
                || context.body() == null || context.body().isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(context.body());
            if (!root.isObject()) {
                return null;
            }
            return toProblemDetail(root, context.statusCode());
        } catch (RuntimeException | java.io.IOException ex) {
            // Deliberately swallowed and downgraded to DEBUG. The response was already a failure;
            // the fact that its problem document was also malformed is a detail about the peer, not
            // a second incident, and logging it at WARN would double the noise of every outage.
            log.debug("Could not parse problem+json from client {}: {}", context.clientName(), ex.toString());
            return null;
        }
    }

    /**
     * Builds the {@code ProblemDetail} by hand rather than through
     * {@code objectMapper.readValue(body, ProblemDetail.class)}.
     *
     * <p>Two reasons. The peer's {@code status} member can disagree with the HTTP status, or be
     * absent, or be a string - databind would throw, and the HTTP status is the one that is actually
     * true. And extension members have to survive: they are where a partner puts the field that
     * tells you what to do, and Jackson would drop every member the record does not declare.
     */
    private ProblemDetail toProblemDetail(JsonNode root, int httpStatus) {
        ProblemDetail detail = ProblemDetail.forStatus(resolveStatus(root, httpStatus));
        if (root.hasNonNull("type")) {
            try {
                detail.setType(URI.create(root.get("type").asText()));
            } catch (IllegalArgumentException ex) {
                // A `type` that is not a URI is the partner's bug; the rest of the document is still
                // worth having.
                log.debug("Ignoring non-URI problem type: {}", root.get("type").asText());
            }
        }
        if (root.hasNonNull("title")) {
            detail.setTitle(root.get("title").asText());
        }
        if (root.hasNonNull("detail")) {
            detail.setDetail(root.get("detail").asText());
        }
        if (root.hasNonNull("instance")) {
            try {
                detail.setInstance(URI.create(root.get("instance").asText()));
            } catch (IllegalArgumentException ex) {
                log.debug("Ignoring non-URI problem instance");
            }
        }
        copyExtensions(root, detail);
        return detail;
    }

    private HttpStatusCode resolveStatus(JsonNode root, int httpStatus) {
        // The HTTP status wins over the document's own `status` member whenever the two disagree:
        // one of them is what the network actually carried and the other is what a template said.
        try {
            return HttpStatusCode.valueOf(httpStatus);
        } catch (IllegalArgumentException ex) {
            return HttpStatus.INTERNAL_SERVER_ERROR;
        }
    }

    private void copyExtensions(JsonNode root, ProblemDetail detail) {
        Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String name = field.getKey();
            if (isStandardMember(name)) {
                continue;
            }
            // Converted to plain Java values rather than kept as JsonNode: the extension members end
            // up in an exception that may be serialized, logged or re-rendered by web-core, and a
            // JsonNode in any of those places ties the consumer to Jackson's tree model.
            detail.setProperty(name, objectMapper.convertValue(field.getValue(), Object.class));
        }
    }

    private boolean isStandardMember(String name) {
        return "type".equals(name) || "title".equals(name) || "detail".equals(name)
                || "instance".equals(name) || "status".equals(name);
    }

    private String summarize(ErrorContext context) {
        if (context.body() == null || context.body().isBlank()) {
            return context.reasonPhrase() == null ? "no body" : context.reasonPhrase();
        }
        return context.body();
    }
}
