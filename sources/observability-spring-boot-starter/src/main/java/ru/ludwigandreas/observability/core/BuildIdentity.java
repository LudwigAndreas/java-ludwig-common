package ru.ludwigandreas.observability.core;

/**
 * What this process was built from: the commit, the branch, when, and by which CI run.
 *
 * <h2>Why this is not part of {@link ServiceIdentity}</h2>
 *
 * <p>The two look like one thing and are not. {@link ServiceIdentity} is the set of dimensions a
 * telemetry backend <em>groups by</em>: its components become Micrometer common tags and
 * OpenTelemetry resource attributes, and every one of them multiplies the number of series in the
 * registry by the number of values it takes. This record is <em>provenance</em>: it is never
 * grouped by, only read off one line when someone needs to get from a running process back to the
 * source.
 *
 * <p><strong>None of these fields may become a metric tag.</strong> A branch name, a build
 * timestamp and a dirty flag all change independently of a release, so as tags they multiply every
 * series by values nobody will ever filter on - the standard way a metrics bill grows after a change
 * described as harmless. Keeping them in a separate record with no {@code toCommonMetricTags()} is
 * what stops that question from being answered wrongly by accident. The one field that travels on
 * every log event is {@link #abbreviatedCommitId()}, and it is safe there for a specific reason: a
 * commit and a version move together, so it adds no value that varies on its own.
 *
 * <p>Widening {@link ServiceIdentity} instead would also have changed the canonical constructor of
 * a published record, which is a breaking API difference for a purely additive feature.
 *
 * <h2>Absent means absent</h2>
 *
 * <p>Every component is optional and resolved independently, and an unresolved one is {@code null}
 * - never {@code "unknown"}. A process started from an IDE has no provenance at all, and a local
 * build has no CI build number; both are legitimate states. A placeholder would look like a real
 * value and silently become a group that unrelated builds share, which is the same reasoning
 * {@link ServiceIdentity#toResourceAttributes()} already follows.
 *
 * <p>There is deliberately no release identifier here. The version is computed from the git tag, so
 * the tag already is the release identifier, and a second field holding the same string is one more
 * value that can disagree with the first.
 *
 * @param commitId            the full commit hash the artifact was built from
 * @param abbreviatedCommitId the short form of the same commit; the one stamped on every log event
 * @param branch              the branch that was checked out at build time
 * @param buildTimestamp      when the artifact was built, as ISO-8601 instant text. The platform's
 *                            own build truncates it to the day so image digests stay reproducible,
 *                            so treat it as a date, not as a point in time
 * @param ciBuildNumber       the CI run that produced the artifact; absent on a local build
 * @param dirty               whether the working tree had uncommitted changes at build time;
 *                            {@code null} when that is not known, which is not the same as clean
 */
public record BuildIdentity(String commitId, String abbreviatedCommitId, String branch, String buildTimestamp,
        String ciBuildNumber, Boolean dirty) {

    private static final BuildIdentity ABSENT = new BuildIdentity(null, null, null, null, null, null);

    public BuildIdentity {
        commitId = blankToNull(commitId);
        abbreviatedCommitId = blankToNull(abbreviatedCommitId);
        branch = blankToNull(branch);
        buildTimestamp = blankToNull(buildTimestamp);
        ciBuildNumber = blankToNull(ciBuildNumber);
    }

    /** The identity of a process with no provenance at all - run from an IDE, or built without git. */
    public static BuildIdentity absent() {
        return ABSENT;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
