package ru.ludwigandreas.ingest.autoconfigure;

import org.springframework.boot.actuate.autoconfigure.endpoint.condition.ConditionalOnAvailableEndpoint;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.ludwigandreas.ingest.actuator.FileIngestEndpoint;
import ru.ludwigandreas.ingest.config.FileIngestProperties;
import ru.ludwigandreas.ingest.engine.IngestPass;
import ru.ludwigandreas.ingest.engine.IngestTaskRegistry;
import ru.ludwigandreas.ingest.repository.FileIngestQuarantineRepository;
import ru.ludwigandreas.ingest.repository.FileIngestRunRepository;

/**
 * Registers the {@code fileingest} endpoint, when Actuator is present and the endpoint is exposed.
 *
 * <p>Separate, and class-level conditional, for the same reason as the metrics and the events: the
 * Actuator is optional and a class naming {@code @Endpoint} types cannot be introspected without it.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(Endpoint.class)
@EnableConfigurationProperties(FileIngestProperties.class)
@ConditionalOnProperty(prefix = "ludwig.ingest.endpoint", name = "enabled", matchIfMissing = true)
public class FileIngestActuatorAutoConfiguration {

    /**
     * The endpoint.
     *
     * @param registry    the configured tasks
     * @param runs        the run table
     * @param quarantines the quarantine table
     * @param pass        what a manual trigger runs
     * @param properties  the bound configuration
     * @return the endpoint
     */
    @Bean
    @ConditionalOnMissingBean(FileIngestEndpoint.class)
    @ConditionalOnAvailableEndpoint(endpoint = FileIngestEndpoint.class)
    public FileIngestEndpoint fileIngestEndpoint(IngestTaskRegistry registry,
                                                 FileIngestRunRepository runs,
                                                 FileIngestQuarantineRepository quarantines,
                                                 IngestPass pass, FileIngestProperties properties) {
        return new FileIngestEndpoint(registry, runs, quarantines, pass, properties);
    }
}
