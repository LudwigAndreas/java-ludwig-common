package ru.ludwigandreas.archrules.fixture.bad.logging.catalog.logs;

import java.io.IOException;

/** The same act through the older API. */
public class LegacyGitDescribe {

    public Process branch() throws IOException {
        return Runtime.getRuntime().exec(new String[] {"git", "branch", "--show-current"});
    }
}
