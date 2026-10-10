package ru.ludwigandreas.notification.local;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.util.List;
import ru.ludwigandreas.testsupport.local.stub.PartnerStub;
import ru.ludwigandreas.testsupport.local.stub.RunningStub;
import ru.ludwigandreas.testsupport.local.stub.StubAddress;

/**
 * Somebody's webhook endpoint, for the webhook channel to post to.
 *
 * <p>Nothing in the service's configuration names a webhook destination - a request that wants one
 * names the URL literally, as an address recipient - so this stub publishes no property at all. Its
 * address is handed out instead: the start-up summary prints it, and a test reads
 * {@link #endpoint(RunningStub)}.
 */
public class WebhookReceiverStub implements PartnerStub {

    /** This stub's name. */
    public static final String NAME = "webhooks";

    /**
     * A template that has a webhook variant, which none of the templates the service ships has.
     *
     * <p>It lives in {@code src/test/resources/templates}, beside the classpath templates the service
     * loads by default, and takes one variable: {@code orderId}.
     */
    public static final String TEMPLATE_KEY = "local-order-shipped";

    private static final String HOOK_PATH = "/hooks/orders";

    /**
     * The URL to give a notification request as its webhook address.
     *
     * @param running this stub, started
     * @return an endpoint the stub accepts posts on
     */
    public static String endpoint(RunningStub running) {
        return running.baseUrl() + HOOK_PATH;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public StubAddress address() {
        return StubAddress.none();
    }

    @Override
    public void register(WireMockServer server) {
        server.stubFor(post(urlPathMatching("/hooks/.*")).willReturn(aResponse().withStatus(204)));
    }

    @Override
    public List<String> summaryLines(RunningStub running) {
        return List.of("use as a WEBHOOK recipient address:  " + endpoint(running)
                + "   (template " + TEMPLATE_KEY + ", variable orderId)");
    }
}
