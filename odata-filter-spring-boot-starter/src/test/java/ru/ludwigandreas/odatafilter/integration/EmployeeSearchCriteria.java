package ru.ludwigandreas.odatafilter.integration;

/** Raw OData query options as they reach the layer that owns the entity. */
record EmployeeSearchCriteria(String filter, String orderBy, Integer top, Integer skip) {
}
