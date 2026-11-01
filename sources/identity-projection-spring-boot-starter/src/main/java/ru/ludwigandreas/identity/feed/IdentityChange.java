package ru.ludwigandreas.identity.feed;

import java.time.Instant;
import java.util.Objects;

/**
 * One thing a source says happened to one user - the only input the identity projection accepts.
 *
 * <p>A change states the user's state, never a delta: an upsert carries the complete role set when it carries one,
 * so a lost or duplicated change cannot leave the projection permanently out of step with the source, and replaying
 * a source converges. {@link #occurredAt()} is when the change happened at the source, and the ordering key the
 * projection compares on.
 *
 * <p>A blank subject is accepted here and discarded (and counted) by the projection, so that a feed reading untrusted
 * input does not have to decide what a usable subject is. A missing source or time is a programming error.
 */
public sealed interface IdentityChange permits IdentityChange.Upsert, IdentityChange.Disable, IdentityChange.Remove {

    /** The declared source this change comes from ({@code ludwig.identity.sources.<name>}). */
    String source();

    /** The user's subject at the source - the projection's key. */
    String subject();

    /** When the change happened at the source. */
    Instant occurredAt();

    /** The source's own version of the record, when it has one. */
    String sourceVersion();

    /** The change's type as metrics and logs name it: {@code upsert}, {@code disable} or {@code remove}. */
    String type();

    /**
     * The user exists, with this profile.
     *
     * @param source        the declared source
     * @param subject       the user's subject
     * @param occurredAt    when the change happened at the source
     * @param sourceVersion the source's version of the record, or null
     * @param profile       the user's profile; a field left null keeps its stored value
     */
    record Upsert(String source, String subject, Instant occurredAt, String sourceVersion, UserProfile profile)
            implements IdentityChange {

        public Upsert {
            requireProvenance(source, occurredAt);
            Objects.requireNonNull(profile, "profile");
        }

        @Override
        public String type() {
            return "upsert";
        }
    }

    /**
     * The user was deactivated at the source. The row stays; the user resolves to no authorities.
     *
     * @param source        the declared source
     * @param subject       the user's subject
     * @param occurredAt    when the change happened at the source
     * @param sourceVersion the source's version of the record, or null
     */
    record Disable(String source, String subject, Instant occurredAt, String sourceVersion) implements IdentityChange {

        public Disable {
            requireProvenance(source, occurredAt);
        }

        @Override
        public String type() {
            return "disable";
        }
    }

    /**
     * The user was deleted at the source. Projected as a status, never a row deletion, so audit records and
     * references to the subject keep resolving and a re-created account does not inherit the old one's history.
     *
     * @param source        the declared source
     * @param subject       the user's subject
     * @param occurredAt    when the change happened at the source
     * @param sourceVersion the source's version of the record, or null
     */
    record Remove(String source, String subject, Instant occurredAt, String sourceVersion) implements IdentityChange {

        public Remove {
            requireProvenance(source, occurredAt);
        }

        @Override
        public String type() {
            return "remove";
        }
    }

    private static void requireProvenance(String source, Instant occurredAt) {
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("An identity change must name its source");
        }
        Objects.requireNonNull(occurredAt, "occurredAt");
    }
}
