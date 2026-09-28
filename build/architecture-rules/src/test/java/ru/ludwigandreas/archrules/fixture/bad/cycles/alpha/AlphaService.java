package ru.ludwigandreas.archrules.fixture.bad.cycles.alpha;

import ru.ludwigandreas.archrules.fixture.bad.cycles.beta.BetaService;

public class AlphaService {

    public String call(BetaService beta) {
        return beta.name();
    }
}
