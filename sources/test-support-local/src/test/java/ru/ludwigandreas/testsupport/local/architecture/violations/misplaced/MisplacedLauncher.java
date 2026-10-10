package ru.ludwigandreas.testsupport.local.architecture.violations.misplaced;

import ru.ludwigandreas.testsupport.local.LocalRun;

/** Uses the launcher from a package that is not named {@code local}. Never run. */
public final class MisplacedLauncher {

    private MisplacedLauncher() {
    }

    public static void main(String[] args) {
        LocalRun.from(ignored -> { }).run(args);
    }
}
