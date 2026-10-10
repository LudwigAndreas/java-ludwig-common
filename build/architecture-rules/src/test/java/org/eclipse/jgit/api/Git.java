package org.eclipse.jgit.api;

/** Stand-in for JGit's entry point, so the fixtures need no JGit dependency. */
public class Git {

    public static Git open(java.io.File directory) {
        return new Git();
    }

    public String head() {
        return "";
    }
}
