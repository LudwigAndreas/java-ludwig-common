package ru.ludwigandreas.notification.local;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.core.env.Environment;
import ru.ludwigandreas.testsupport.local.stub.StubServers;

/**
 * Calls the running service over HTTP as one of the seeded callers.
 *
 * <p>The seed fixtures use this to submit notifications and publish an announcement through the
 * service's own REST API, with a real token. That is deliberate: seeding through the front door
 * exercises validation, authorization, idempotency and fan-out exactly as a client does, so seed
 * data cannot exist in a state the API would never have produced - and a fixture needs no knowledge
 * of the service's internals to stay correct.
 */
public class LocalApi {

    private static final Duration TOKEN_LIFETIME = Duration.ofMinutes(5);
    private static final int SUCCESS_FAMILY = 2;
    private static final int STATUS_FAMILY_DIVISOR = 100;

    private final HttpClient http = HttpClient.newHttpClient();
    private final Environment environment;
    private final StubServers stubs;
    private final ObjectMapper objectMapper;

    public LocalApi(Environment environment, StubServers stubs, ObjectMapper objectMapper) {
        this.environment = environment;
        this.stubs = stubs;
        this.objectMapper = objectMapper;
    }

    /**
     * Posts a JSON body as a caller and requires a 2xx answer.
     *
     * @param caller who to call as
     * @param path   the path under the service's root
     * @param body   the request object, serialized with the service's own mapper
     * @return the response body
     * @throws IllegalStateException when the service answers anything but 2xx, with its answer
     */
    public String post(LocalCaller caller, String path, Object body) {
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                    .header("Authorization", "Bearer " + token(caller))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Could not serialize the request for " + path, ex);
        }
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException ex) {
            throw new IllegalStateException("The service did not answer POST " + path, ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while calling POST " + path, ex);
        }
        if (response.statusCode() / STATUS_FAMILY_DIVISOR != SUCCESS_FAMILY) {
            throw new IllegalStateException("POST " + path + " as " + caller.subject() + " answered "
                    + response.statusCode() + ": " + response.body());
        }
        return response.body();
    }

    /**
     * The service's own address.
     *
     * @return {@code http://localhost:<port>}
     */
    public String baseUrl() {
        return "http://localhost:" + environment.getProperty("local.server.port", "8080");
    }

    private String token(LocalCaller caller) {
        IdentityProviderStub issuer =
                (IdentityProviderStub) stubs.stub(IdentityProviderStub.NAME).definition();
        return issuer.tokenFor(caller, TOKEN_LIFETIME);
    }
}
