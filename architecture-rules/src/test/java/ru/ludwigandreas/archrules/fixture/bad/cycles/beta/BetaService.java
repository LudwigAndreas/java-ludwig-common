package ru.ludwigandreas.archrules.fixture.bad.cycles.beta;

import ru.ludwigandreas.archrules.fixture.bad.cycles.alpha.AlphaService;

public class BetaService {

    public String name() {
        return AlphaService.class.getSimpleName();
    }
}
