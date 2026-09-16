package ru.ludwigandreas.archrules.fixture.good.catalog.config;

import org.springframework.context.annotation.Configuration;

/** The one place allowed to read the environment directly. */
@Configuration
public class CatalogConfiguration {

    private final String bucket = System.getenv("CATALOG_BUCKET");

    public String bucket() {
        return bucket;
    }
}
