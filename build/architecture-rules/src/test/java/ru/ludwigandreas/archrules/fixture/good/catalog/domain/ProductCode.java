package ru.ludwigandreas.archrules.fixture.good.catalog.domain;

/** Framework-free domain type: no Spring, no JPA, no JSON. */
public record ProductCode(String value) {

    public ProductCode {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Product code must not be blank");
        }
    }
}
