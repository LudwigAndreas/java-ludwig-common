package ru.ludwigandreas.notification.architecture.violations;

import org.springframework.web.client.RestClient;

/**
 * What the outbound-HTTP rule exists to refuse: a class that quietly builds a client of its own.
 *
 * <p>A test class, never instantiated. {@code OutboundHttpRulesTest} evaluates the rule against it,
 * because a rule nobody has seen fail is not known to work.
 */
public class RogueHttpClient {

    private final RestClient client;

    public RogueHttpClient(RestClient.Builder builder) {
        this.client = builder.baseUrl("https://a-new-partner.example").build();
    }

    public RestClient client() {
        return client;
    }
}
