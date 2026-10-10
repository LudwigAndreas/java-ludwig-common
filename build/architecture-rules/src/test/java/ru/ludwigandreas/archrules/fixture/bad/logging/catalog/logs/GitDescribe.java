package ru.ludwigandreas.archrules.fixture.bad.logging.catalog.logs;

import java.io.IOException;

/** Asks git at runtime, through a launched process. */
public class GitDescribe {

    public Process head() throws IOException {
        return new ProcessBuilder("git", "rev-parse", "HEAD").start();
    }
}
