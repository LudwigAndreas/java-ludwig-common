package ru.ludwigandreas.testsupport.local.integration.sample;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.util.Map;
import ru.ludwigandreas.testsupport.local.stub.PartnerStub;

/** The partner's healthy answers. */
public class PartnerApiStub implements PartnerStub {

    /** What every healthy lookup returns. */
    public static final String HEALTHY_BODY = "healthy";

    /** The extra property this stub contributes, standing in for a token endpoint address. */
    public static final String TOKEN_URL_PROPERTY = "sample.partner.token-url";

    @Override
    public String name() {
        return "partner";
    }

    @Override
    public void register(WireMockServer server) {
        server.stubFor(get(urlPathMatching("/(slow-)?things/.*"))
                .willReturn(aResponse().withStatus(200).withBody(HEALTHY_BODY)));
    }

    @Override
    public Map<String, String> properties(String baseUrl) {
        return Map.of(TOKEN_URL_PROPERTY, baseUrl + "/token");
    }
}
