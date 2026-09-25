package ru.ludwigandreas.export.integration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import ru.ludwigandreas.export.api.ReportSink;
import ru.ludwigandreas.export.sink.FilesystemReportSink;

/**
 * The application the persistence integration tests start.
 *
 * <p>Deliberately declares no report definitions. These tests are about the run lifecycle - claiming,
 * leasing, reclaiming, retrying, purging - and every one of those is a property of the row rather
 * than of the report it names. A definition here would make each test carry a whole reporting
 * fixture to assert something about a timestamp.
 *
 * <h2>Why so much is excluded</h2>
 *
 * <p>{@code security}, {@code rest-client} and {@code identity-projection} are optional dependencies
 * of this module, so their autoconfigurations are on the test classpath while the things they
 * condition on - an OAuth2 issuer, a client-registration repository, a Kafka broker - are not. That
 * is exactly what {@code optional} means and is correct in production: a service that uses
 * enrichment brings the client library and its configuration together. It is only a problem here,
 * where a test wants the run lifecycle and nothing else, so the exclusions are listed rather than
 * the dependencies being made compulsory.
 */
@SpringBootApplication(excludeName = {
        "ru.ludwigandreas.security.config.LudwigSecurityAutoConfiguration",
        "ru.ludwigandreas.security.config.SecurityMetricsAutoConfiguration",
        "ru.ludwigandreas.security.config.DataAuthorizationAutoConfiguration",
        "ru.ludwigandreas.security.config.MutualTlsAutoConfiguration",
        "ru.ludwigandreas.security.config.ResourceServerAutoConfiguration",
        "ru.ludwigandreas.restclient.config.RestClientAutoConfiguration",
        "ru.ludwigandreas.restclient.config.RestClientAuthAutoConfiguration",
        "ru.ludwigandreas.restclient.config.RestClientObservabilityAutoConfiguration",
        "ru.ludwigandreas.restclient.config.RestClientInterfaceAutoConfiguration",
        "ru.ludwigandreas.restclient.config.RestClientWebCoreAutoConfiguration",
        "ru.ludwigandreas.identity.config.IdentityProjectionAutoConfiguration",
        "ru.ludwigandreas.identity.config.IdentityKafkaAutoConfiguration",
        "ru.ludwigandreas.identity.config.IdentityLiquibaseAutoConfiguration"
})
public class ExportTestApplication {

    public static void main(String[] args) {
        SpringApplication.run(ExportTestApplication.class, args);
    }

    /**
     * A sink over a directory of its own, named {@code testReportSink} in {@code sink.type}.
     *
     * <p>Contributed rather than left to the autoconfiguration so that the tests exercise the path a
     * service with its own sink takes - including the startup check that {@code sink.type} names a
     * bean that exists, which is the one thing about a custom sink that can be wrong in a way
     * nothing notices until the first report finishes.
     */
    @Bean
    public ReportSink testReportSink() throws IOException {
        Path root = Path.of(System.getProperty("java.io.tmpdir"), "ludwig-export-it");
        Files.createDirectories(root);
        return new FilesystemReportSink(root);
    }
}
