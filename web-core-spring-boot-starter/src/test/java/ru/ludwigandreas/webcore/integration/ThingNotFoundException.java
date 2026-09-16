package ru.ludwigandreas.webcore.integration;

import java.util.UUID;
import ru.ludwigandreas.webcore.problem.LocalizedException;
import ru.ludwigandreas.webcore.problem.ProblemStatus;

/** A service's own business failure, declared the way a real one is. */
public class ThingNotFoundException extends LocalizedException {

    public ThingNotFoundException(UUID id) {
        super(ProblemStatus.NOT_FOUND, "error.thing.not-found", id);
    }
}
