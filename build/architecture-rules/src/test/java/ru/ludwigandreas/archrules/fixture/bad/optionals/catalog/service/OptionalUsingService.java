package ru.ludwigandreas.archrules.fixture.bad.optionals.catalog.service;

import java.util.Optional;

import org.springframework.stereotype.Service;

/** Violations: Optional as a field type and as a parameter type. */
@Service
public class OptionalUsingService {

    private final Optional<String> cachedName = Optional.empty();

    public String describe(Optional<String> name) {
        return name.orElse(cachedName.orElse("unknown"));
    }
}
