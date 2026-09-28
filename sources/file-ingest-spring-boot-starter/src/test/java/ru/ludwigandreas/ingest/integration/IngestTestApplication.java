package ru.ludwigandreas.ingest.integration;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/** The Spring Boot application the integration suite runs the module inside. */
@SpringBootApplication
public class IngestTestApplication {

    /** The test service's one ingest. */
    @Bean
    public CatalogueIngest catalogueIngest() {
        return new CatalogueIngest();
    }
}
