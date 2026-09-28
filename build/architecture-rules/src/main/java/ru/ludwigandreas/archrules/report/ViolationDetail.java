package ru.ludwigandreas.archrules.report;

import java.util.Objects;
import java.util.Optional;

/**
 * One violation, resolved to a place in the source tree.
 *
 * <p>ArchUnit reports a violation as a sentence. That is enough for a human reading a build log and
 * not enough for anything else, so each one is also split into the fields a tool needs: which class,
 * which member, which file and line. An agent asked to fix the build can go straight to the location;
 * a dashboard can group violations by class across services.
 *
 * @param message    ArchUnit's own description of the violation, kept verbatim
 * @param className  fully qualified class the violation was reported on, if it could be resolved
 * @param memberName member (method, constructor or field) the violation was reported on, if any
 * @param sourceFile source file name, if known
 * @param lineNumber line number, or 0 when the violation is not tied to one
 */
public record ViolationDetail(String message,
                              String className,
                              String memberName,
                              String sourceFile,
                              int lineNumber) {

    public ViolationDetail {
        Objects.requireNonNull(message, "message");
    }

    public static ViolationDetail ofMessage(String message) {
        return new ViolationDetail(message, null, null, null, 0);
    }

    public Optional<String> optionalClassName() {
        return Optional.ofNullable(className);
    }

    public Optional<String> optionalMemberName() {
        return Optional.ofNullable(memberName);
    }

    public Optional<String> optionalSourceFile() {
        return Optional.ofNullable(sourceFile);
    }

    /** {@code Foo.java:42}, or an empty string when nothing is known about the location. */
    public String location() {
        if (sourceFile == null) {
            return className == null ? "" : className;
        }
        return lineNumber > 0 ? sourceFile + ":" + lineNumber : sourceFile;
    }
}
