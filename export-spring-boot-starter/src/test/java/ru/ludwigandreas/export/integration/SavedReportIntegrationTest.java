package ru.ludwigandreas.export.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import ru.ludwigandreas.export.entity.ExportReportSubscription;
import ru.ludwigandreas.export.entity.ExportSavedReport;
import ru.ludwigandreas.export.exception.SavedReportStaleException;
import ru.ludwigandreas.export.lifecycle.SavedReportService;
import ru.ludwigandreas.export.repository.ExportReportSubscriptionRepository;
import ru.ludwigandreas.export.repository.ExportSavedReportRepository;

/**
 * Saved configurations, and the drift between them and the definitions they name.
 *
 * <p>The interesting case is a configuration written against a definition that has since changed,
 * and there is no way to construct it except against a real schema: the configuration has to survive
 * a round trip through the database for the test to be about anything other than an object it just
 * built.
 *
 * <p>The test application registers no report definitions at all, which makes every configuration
 * here stale by construction. That is exactly the situation a deployment produces when a report is
 * removed, and it is the one an implementation is most likely to get wrong by quietly returning
 * nothing.
 */
@SpringBootTest(classes = ExportTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.jpa.hibernate.ddl-auto=validate",
                "spring.liquibase.enabled=false",
                "ludwig.export.poller.enabled=false",
                "ludwig.export.sink.type=testReportSink"
        })
@Testcontainers
class SavedReportIntegrationTest {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName
            .parse("postgres:16-alpine@sha256:cf78e76683b9ca8c5733cbbdce6c9262b45b6767934dd0a95e671f9a0fc20685")
            .asCompatibleSubstituteFor("postgres");

    @Container
    @ServiceConnection("postgres")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(POSTGRES_IMAGE);

    @Autowired
    private ExportSavedReportRepository savedReports;

    @Autowired
    private ExportReportSubscriptionRepository subscriptions;

    @Autowired
    private SavedReportService service;

    @BeforeEach
    void reset() {
        subscriptions.deleteAll();
        savedReports.deleteAll();
    }

    private ExportSavedReport newSavedReport(String name) {
        ExportSavedReport saved = new ExportSavedReport();
        saved.setDefinitionKey("catalog.orders");
        saved.setName(name);
        saved.setParameters(Map.of("from", "2026-01-01"));
        saved.setColumnIds(List.of("number", "total"));
        saved.setFormatId("csv");
        return saved;
    }

    private ExportReportSubscription newSubscription(UUID savedReportId) {
        ExportReportSubscription subscription = new ExportReportSubscription();
        subscription.setSavedReportId(savedReportId);
        subscription.setCron("0 0 6 * * *");
        subscription.setFormatId("csv");
        subscription.setRunAs("alice");
        subscription.setRecipients(List.of("alice@example.test"));
        return subscription;
    }

    @Test
    @DisplayName("a configuration naming a definition that no longer exists fails loudly, naming it")
    void refusesAConfigurationWhoseDefinitionIsGone() {
        assertThatThrownBy(() -> service.save(newSavedReport("Monthly orders")))
                .isInstanceOf(SavedReportStaleException.class)
                .hasMessageContaining("catalog.orders");
    }

    @Test
    @DisplayName("the listing hides a stale configuration rather than failing the whole screen")
    void listingSkipsStaleConfigurations() {
        // Written straight to the repository, bypassing the service's own validation, because that
        // is exactly how a row that was valid last month exists today.
        savedReports.save(newSavedReport("Monthly orders"));

        assertThat(service.usable()).isEmpty();
        assertThat(savedReports.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("loading a stale configuration by id fails, because somebody chose it deliberately")
    void loadingAStaleConfigurationFails() {
        ExportSavedReport saved = savedReports.save(newSavedReport("Monthly orders"));

        assertThatThrownBy(() -> service.require(saved.getId()))
                .isInstanceOf(SavedReportStaleException.class);
    }

    @Test
    @DisplayName("a configuration something is subscribed to cannot be deleted, and says what depends on it")
    void refusesToDeleteASubscribedConfiguration() {
        ExportSavedReport saved = savedReports.save(newSavedReport("Monthly orders"));
        ExportReportSubscription subscription = subscriptions.save(newSubscription(saved.getId()));

        assertThatThrownBy(() -> service.delete(saved.getId()))
                .isInstanceOf(SavedReportStaleException.class)
                .hasMessageContaining(subscription.getId().toString());
        assertThat(savedReports.findById(saved.getId())).isPresent();
    }

    @Test
    @DisplayName("a configuration nothing depends on deletes cleanly")
    void deletesAnUnsubscribedConfiguration() {
        ExportSavedReport saved = savedReports.save(newSavedReport("Monthly orders"));

        assertThatCode(() -> service.delete(saved.getId())).doesNotThrowAnyException();
        assertThat(savedReports.findById(saved.getId())).isEmpty();
    }

    @Test
    @DisplayName("the schema round-trips a configuration and its subscription")
    void persistsBothTables() {
        ExportSavedReport saved = savedReports.save(newSavedReport("Monthly orders"));
        subscriptions.save(newSubscription(saved.getId()));

        ExportSavedReport reloaded = savedReports.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getParameters()).containsEntry("from", "2026-01-01");
        assertThat(reloaded.getColumnIds()).containsExactly("number", "total");
        assertThat(reloaded.getRevision()).isEqualTo(1);
        assertThat(subscriptions.findBySavedReportId(saved.getId())).hasSize(1);
        assertThat(subscriptions.findByEnabledTrue()).hasSize(1);
    }
}
