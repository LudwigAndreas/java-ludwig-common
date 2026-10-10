package ru.ludwigandreas.testsupport.local.integration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import ru.ludwigandreas.restclient.error.RestClientException;
import ru.ludwigandreas.testsupport.local.integration.sample.PartnerApi;
import ru.ludwigandreas.testsupport.local.integration.sample.PartnerApiStub;
import ru.ludwigandreas.testsupport.local.integration.sample.SampleStubs;
import ru.ludwigandreas.testsupport.local.stub.RunningStub;
import ru.ludwigandreas.testsupport.local.stub.StubFault;
import ru.ludwigandreas.testsupport.local.stub.StubServers;
import ru.ludwigandreas.testsupport.local.stub.WithPartnerStubs;

/**
 * Proves that what one test method does to a stub is gone by the next.
 *
 * <p>The methods are ordered on purpose: the first leaves a fault, a path-scoped fault, a one-off
 * answer and a recorded request behind, and the second asserts none of them survived - and that the
 * fault switch still works, which is the part WireMock's own {@code resetAll()} would break.
 */
@SampleContext
@WithPartnerStubs(SampleStubs.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class StubResetIT {

    @Autowired
    private PartnerApi partner;

    @Autowired
    private StubServers stubs;

    @Test
    @Order(1)
    @DisplayName("a test dirties the stub in every way it can")
    void dirtiesTheStub() {
        RunningStub stub = stubs.stub("partner");
        assertThat(partner.thing("recorded")).isEqualTo(PartnerApiStub.HEALTHY_BODY);
        stub.server().stubFor(get(urlPathEqualTo("/things/one-off"))
                .atPriority(2)
                .willReturn(aResponse().withBody("one-off")));
        stub.fail(StubFault.RESET, "/slow-things/.*");
        stub.fail(StubFault.REFUSED);

        assertThatThrownBy(() -> partner.thing("1")).isInstanceOf(RestClientException.class);
    }

    @Test
    @Order(2)
    @DisplayName("the next test finds a healthy stub with an empty journal and a working fault switch")
    void findsTheStubAsItStarted() {
        RunningStub stub = stubs.stub("partner");

        assertThat(stub.activeFault()).isEmpty();
        assertThat(stub.server().getAllServeEvents()).isEmpty();
        assertThat(partner.thing("one-off")).isEqualTo(PartnerApiStub.HEALTHY_BODY);
        assertThat(partner.slowThing("1")).isEqualTo(PartnerApiStub.HEALTHY_BODY);

        stub.fail(StubFault.UNAVAILABLE);
        assertThatThrownBy(() -> partner.thing("1")).isInstanceOf(RestClientException.class);
    }
}
