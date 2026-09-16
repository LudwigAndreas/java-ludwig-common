package ru.ludwigandreas.archrules.fixture.bad.spring.catalog.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Violation: the collaborator arrives through a field. */
@Service
public class FieldInjectedService {

    @Autowired
    private Collaborator collaborator;

    public Collaborator collaborator() {
        return collaborator;
    }
}
