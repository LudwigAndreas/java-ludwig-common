package ru.ludwigandreas.archrules.fixture.bad.logging.catalog.logs;

import java.io.File;
import org.eclipse.jgit.api.Git;

/** Reads the repository in-process, with JGit, without launching anything. */
public class RepositoryReader {

    public String head() {
        return Git.open(new File(".")).head();
    }
}
