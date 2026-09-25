package ru.ludwigandreas.export.unit;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.export.api.ReportDefinition;
import ru.ludwigandreas.export.config.ExportConfigurationValidator;
import ru.ludwigandreas.export.config.ExportProperties;
import ru.ludwigandreas.export.config.RestClientPoolSizes;
import ru.ludwigandreas.export.exception.ExportConfigurationException;

/**
 * The configurations that are valid field by field and wrong as a whole.
 *
 * <p>Each case here is one Bean Validation cannot see, and each of them fails in production rather
 * than in a test: a window that cannot hold a batch, a lease shorter than two heartbeats, a
 * download link that outlives the file behind it.
 */
class ExportConfigurationValidatorTest {

    /**
     * A deployment with no REST-client starter, where every client name is unknown.
     *
     * <p>{@code RestClientPoolSizes.UNKNOWN} rather than a stub, because that constant is what the
     * autoconfiguration actually falls back to: a test stub would let this suite pass while the shipped
     * fallback answered something else.
     */
    private static final RestClientPoolSizes NO_REST_CLIENTS = RestClientPoolSizes.UNKNOWN;

    private static ExportConfigurationValidator validator(ExportProperties properties) {
        return validator(properties, List.of());
    }

    private static ExportConfigurationValidator validator(ExportProperties properties,
                                                          List<ReportDefinition<?, ?>> definitions) {
        return new ExportConfigurationValidator(properties, Set.of("xlsx", "csv"), Set.of("filesystem"),
                definitions, NO_REST_CLIENTS);
    }

    private static ExportProperties defaults() {
        return new ExportProperties();
    }

    @Test
    @DisplayName("the shipped defaults are a consistent set")
    void defaultsAreConsistent() {
        assertThatCode(() -> validator(defaults()).validate()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a source page larger than the window is refused, because the page becomes the bound")
    void rejectsPageLargerThanWindow() {
        ExportProperties properties = defaults();
        properties.setSourcePageSize(5_000);
        properties.setWindowSize(2_000);

        assertThatThrownBy(() -> validator(properties).validate())
                .isInstanceOf(ExportConfigurationException.class)
                .hasMessageContaining("source-page-size");
    }

    @Test
    @DisplayName("an enrichment batch larger than the window is refused, because it can never be filled")
    void rejectsBatchLargerThanWindow() {
        ExportProperties properties = defaults();
        properties.setWindowSize(100);

        assertThatThrownBy(() -> validator(properties).validate())
                .isInstanceOf(ExportConfigurationException.class)
                .hasMessageContaining("enrichment.batch-size");
    }

    @Test
    @DisplayName("a cache smaller than one batch is refused, because it would never return a hit")
    void rejectsCacheSmallerThanBatch() {
        ExportProperties properties = defaults();
        properties.getEnrichment().setCacheSize(10);

        assertThatThrownBy(() -> validator(properties).validate())
                .isInstanceOf(ExportConfigurationException.class)
                .hasMessageContaining("enrichment.cache-size");
    }

    @Test
    @DisplayName("a sync threshold above the row cap is refused, because nothing would ever defer")
    void rejectsSyncThresholdAboveRowCap() {
        ExportProperties properties = defaults();
        properties.setSyncThresholdRows(10_000_000);

        assertThatThrownBy(() -> validator(properties).validate())
                .isInstanceOf(ExportConfigurationException.class)
                .hasMessageContaining("sync-threshold-rows");
    }

    @Test
    @DisplayName("a lease that is not at least twice the heartbeat is refused")
    void rejectsLeaseWithoutHeartbeatMargin() {
        ExportProperties properties = defaults();
        properties.getPoller().setHeartbeatInterval(Duration.ofMinutes(4));

        assertThatThrownBy(() -> validator(properties).validate())
                .isInstanceOf(ExportConfigurationException.class)
                .hasMessageContaining("heartbeat-interval");
    }

    @Test
    @DisplayName("a download link outliving the retention is refused")
    void rejectsLinkOutlivingRetention() {
        ExportProperties properties = defaults();
        properties.getSink().setDownloadLinkTtl(Duration.ofDays(30));

        assertThatThrownBy(() -> validator(properties).validate())
                .isInstanceOf(ExportConfigurationException.class)
                .hasMessageContaining("download-link-ttl");
    }

    @Test
    @DisplayName("a sink type nothing provides is refused, naming what is registered")
    void rejectsUnknownSinkType() {
        ExportProperties properties = defaults();
        properties.getSink().setType("s3");

        assertThatThrownBy(() -> validator(properties).validate())
                .isInstanceOf(ExportConfigurationException.class)
                .hasMessageContaining("sink.type 's3'");
    }

    @Test
    @DisplayName("a CSV profile that is neither excel nor rfc4180 is refused")
    void rejectsUnknownCsvProfile() {
        ExportProperties properties = defaults();
        properties.getFormats().getCsv().setProfile("tsv");

        assertThatThrownBy(() -> validator(properties).validate())
                .isInstanceOf(ExportConfigurationException.class)
                .hasMessageContaining("formats.csv.profile");
    }

    @Test
    @DisplayName("formats.enabled naming a format nothing writes is refused")
    void rejectsEnabledFormatWithoutAWriter() {
        ExportProperties properties = defaults();
        properties.getFormats().setEnabled(new java.util.LinkedHashSet<>(List.of("pdf")));

        assertThatThrownBy(() -> validator(properties).validate())
                .isInstanceOf(ExportConfigurationException.class)
                .hasMessageContaining("formats.enabled names 'pdf'");
    }

    @Test
    @DisplayName("a base path with no leading slash is refused")
    void rejectsMalformedBasePath() {
        ExportProperties properties = defaults();
        properties.getWeb().setBasePath("api/v1/reports/");

        assertThatThrownBy(() -> validator(properties).validate())
                .isInstanceOf(ExportConfigurationException.class)
                .hasMessageContaining("web.base-path");
    }

    @Test
    @DisplayName("every problem is reported together")
    void reportsEveryProblemAtOnce() {
        ExportProperties properties = defaults();
        properties.setWindowSize(100);
        properties.getSink().setType("s3");
        properties.getWeb().setBasePath("reports");

        assertThatThrownBy(() -> validator(properties).validate())
                .isInstanceOf(ExportConfigurationException.class)
                .hasMessageContaining("enrichment.batch-size")
                .hasMessageContaining("sink.type")
                .hasMessageContaining("web.base-path");
    }
}
