package ru.ludwigandreas.archrules.fixture.bad.admin.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.ludwigandreas.archrules.fixture.bad.admin.repository.ThingRepository;
import ru.ludwigandreas.security.operation.RequiresOperation;

/** Violates controller-is-a-thin-adapter. */
@RestController
@RequestMapping("/admin/v1/raw")
public class RepositoryBackedAdminController {

    private final ThingRepository repository;

    public RepositoryBackedAdminController(ThingRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    @RequiresOperation("raw.raw.read")
    public String read() {
        return repository.nameOf("x");
    }
}
