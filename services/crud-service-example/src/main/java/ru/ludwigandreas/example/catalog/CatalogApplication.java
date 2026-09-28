package ru.ludwigandreas.example.catalog;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Product catalog: a CRUD microservice assembled almost entirely from this repository's own
 * modules - db-core (base entities, auditing, repository base class), odata-filter (safe, policy-
 * bounded query options) and outbox (transactional event publishing).
 */
@SpringBootApplication
public class CatalogApplication {

    public static void main(String[] args) {
        SpringApplication.run(CatalogApplication.class, args);
    }
}
