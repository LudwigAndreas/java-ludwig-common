package ru.ludwigandreas.testsupport.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.testsupport.docs.PublishedDocument;
import ru.ludwigandreas.testsupport.docs.PublishedDocument.Kind;

class PublishedDocumentTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void membersAreSortedAndArraysKeepTheirOrder() throws Exception {
        String one = PublishedDocument.canonical(
                json.readTree("{\"b\":1,\"a\":{\"z\":[3,1,2],\"y\":true}}"), Kind.ASYNCAPI);
        String two = PublishedDocument.canonical(
                json.readTree("{\"a\":{\"y\":true,\"z\":[3,1,2]},\"b\":1}"), Kind.ASYNCAPI);

        assertThat(one).isEqualTo(two);
        assertThat(one.indexOf("a:")).isLessThan(one.indexOf("b:"));
        assertThat(one).contains("z:\n  - 3\n  - 1\n  - 2");
    }

    @Test
    void openApiLosesItsServersBlock() throws Exception {
        String canonical = PublishedDocument.canonical(json.readTree(
                "{\"openapi\":\"3.0.1\",\"servers\":[{\"url\":\"http://localhost\"}]}"), Kind.OPENAPI);

        assertThat(canonical).doesNotContain("servers", "localhost");
    }

    @Test
    void asyncApiReplacesEachHostWithItsServerName() throws Exception {
        String canonical = PublishedDocument.canonical(json.readTree(
                "{\"servers\":{\"partner-a\":{\"host\":\"localhost:55001\",\"protocol\":\"kafka\"}}}"),
                Kind.ASYNCAPI);

        assertThat(canonical).contains("partner-a:", "host: partner-a", "protocol: kafka").doesNotContain("55001");
    }
}
