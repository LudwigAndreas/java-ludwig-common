package ru.ludwigandreas.example.catalog.service.model;

import java.util.UUID;

/** Category as seen by the business layer. */
public record Category(UUID id, String code, String name) {
}
