package ru.ludwigandreas.security.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.security.authn.mtls.ClientCertificateDetails;
import ru.ludwigandreas.security.authn.mtls.XfccParser;

class XfccParserTest {

    @Test
    @DisplayName("keeps a quoted subject DN intact even though it contains the pair separator")
    void parsesQuotedSubjectContainingCommas() {
        String header = "By=spiffe://mesh/ns/edge/sa/gateway;Hash=abc123;"
                + "Subject=\"CN=acme-partner,O=ACME GmbH,L=Berlin,C=DE\";URI=spiffe://partners/acme";

        List<ClientCertificateDetails> parsed = XfccParser.parse(header);

        assertThat(parsed).hasSize(1);
        assertThat(parsed.get(0).subjectDn()).isEqualTo("CN=acme-partner,O=ACME GmbH,L=Berlin,C=DE");
        assertThat(parsed.get(0).spiffeId()).contains("spiffe://partners/acme");
        assertThat(parsed.get(0).hash()).isEqualTo("abc123");
    }

    @Test
    @DisplayName("collects repeated URI and DNS entries instead of overwriting them")
    void collectsRepeatedSans() {
        String header = "Hash=abc;URI=spiffe://partners/acme;URI=https://acme.example/id;"
                + "DNS=partner.acme.example;DNS=alt.acme.example";

        ClientCertificateDetails details = XfccParser.parse(header).get(0);

        assertThat(details.uriSans())
                .containsExactly("spiffe://partners/acme", "https://acme.example/id");
        assertThat(details.dnsSans()).containsExactly("partner.acme.example", "alt.acme.example");
    }

    @Test
    @DisplayName("splits a forwarded chain into one entry per certificate")
    void parsesChain() {
        String header = "Hash=client;Subject=\"CN=client\",Hash=intermediate;Subject=\"CN=intermediate\"";

        List<ClientCertificateDetails> parsed = XfccParser.parse(header);

        assertThat(parsed).hasSize(2);
        assertThat(parsed.get(0).hash()).isEqualTo("client");
        assertThat(parsed.get(1).hash()).isEqualTo("intermediate");
    }

    @Test
    void returnsNothingForAbsentOrBlankHeader() {
        assertThat(XfccParser.parse(null)).isEmpty();
        assertThat(XfccParser.parse("   ")).isEmpty();
    }
}
