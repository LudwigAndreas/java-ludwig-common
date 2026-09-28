package ru.ludwigandreas.jira.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import ru.ludwigandreas.jira.JiraClient;

class JiraClientBuilderTest {

    @Test
    void appendsTheSlashThatKeepsAContextPathFromBeingDiscarded() {
        // Without it, URI.resolve drops the last segment and every request goes to the wrong host path.
        try (JiraClient client = JiraClient.builder()
                .baseUrl("https://example.com/jira")
                .anonymous()
                .transport(new RecordingTransport())
                .build()) {
            assertThat(client.rest().baseUri()).hasToString("https://example.com/jira/");
        }
    }

    @Test
    void rejectsABaseUrlWithNoScheme() {
        assertThatThrownBy(() -> JiraClient.builder().baseUrl("jira.example.com"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("absolute");
    }

    @Test
    void rejectsANonHttpScheme() {
        assertThatThrownBy(() -> JiraClient.builder().baseUrl("ftp://jira.example.com"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("http or https");
    }

    @Test
    void requiresCredentialsToBeChosenRatherThanDefaultingToAnonymous() {
        assertThatThrownBy(() -> JiraClient.builder().baseUrl("https://jira.example.com").build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Credentials are required");
    }

    @Test
    void requiresABaseUrl() {
        assertThatThrownBy(() -> JiraClient.builder().anonymous().build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("base URL is required");
    }

    @Test
    void rejectsABlankPersonalAccessToken() {
        assertThatThrownBy(() -> JiraClient.builder().personalAccessToken("   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be blank");
    }

    @Test
    void sendsTheConfiguredUserAgentAndDefaultHeaders() {
        RecordingTransport transport = new RecordingTransport().respondWith(200, "{\"name\":\"a\"}");

        try (JiraClient client = JiraClient.builder()
                .baseUrl("https://jira.example.com")
                .anonymous()
                .userAgent("ops-sync/2.1")
                .defaultHeader("X-Change-Ticket", "CHG-42")
                .transport(transport)
                .build()) {
            client.users().myself();
        }

        assertThat(transport.lastRequest().headers())
                .containsEntry("User-Agent", "ops-sync/2.1")
                .containsEntry("X-Change-Ticket", "CHG-42")
                .doesNotContainKey("Authorization");
    }
}
