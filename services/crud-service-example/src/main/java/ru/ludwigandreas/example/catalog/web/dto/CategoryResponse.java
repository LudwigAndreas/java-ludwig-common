package ru.ludwigandreas.example.catalog.web.dto;

import java.util.UUID;

/** Category as returned inside a product. */
public record CategoryResponse(UUID id, String code, String name) {
}
