package ru.ludwigandreas.archrules.fixture.good.catalog.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Typed configuration, validated so a bad ConfigMap value stops the pod instead of travelling. */
@ConfigurationProperties("catalog")
@Validated
public class CatalogProperties {

    private final String bucket = null;

    public String bucket() {
        return bucket;
    }
}
