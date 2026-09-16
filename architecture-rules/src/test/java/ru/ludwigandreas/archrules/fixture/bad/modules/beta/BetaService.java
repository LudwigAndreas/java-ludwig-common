package ru.ludwigandreas.archrules.fixture.bad.modules.beta;

import ru.ludwigandreas.archrules.fixture.bad.modules.alpha.internal.AlphaInternals;

/** Violation: reaching into another module's internals. */
public class BetaService {

    private final AlphaInternals internals = new AlphaInternals();

    public String call() {
        return internals.secret();
    }
}
