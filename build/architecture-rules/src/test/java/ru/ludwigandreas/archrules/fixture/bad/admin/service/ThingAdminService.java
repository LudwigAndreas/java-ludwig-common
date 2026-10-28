package ru.ludwigandreas.archrules.fixture.bad.admin.service;

import org.springframework.stereotype.Service;
import ru.ludwigandreas.archrules.fixture.bad.admin.repository.ThingRepository;

/** The module service an administrative controller should delegate to. */
@Service
public class ThingAdminService {

    private final ThingRepository repository;

    public ThingAdminService(ThingRepository repository) {
        this.repository = repository;
    }

    public String discard(String id) {
        return repository.nameOf(id);
    }
}
