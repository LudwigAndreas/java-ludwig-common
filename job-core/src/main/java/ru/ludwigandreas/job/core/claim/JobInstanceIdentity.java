package ru.ludwigandreas.job.core.claim;

/**
 * This process's identity, as written into every {@code locked_by} / {@code owner} / {@code
 * owner_instance} column in the platform.
 *
 * <p>A one-field record rather than a bare {@code String} bean, because a {@code String} bean is
 * injected by type: the moment any other library in the application context publishes one, both
 * become ambiguous and the failure is a startup error several modules away from either of them. A
 * named type is unambiguous and says what the value is at every use site.
 *
 * @param owner the identity, from {@link ClaimOwner#resolve(String)}
 */
public record JobInstanceIdentity(String owner) {

    /** Rejects an empty identity, which would make every owner column meaningless rather than wrong. */
    public JobInstanceIdentity {
        if (owner == null || owner.isBlank()) {
            throw new IllegalArgumentException("owner must not be blank");
        }
    }

    @Override
    public String toString() {
        return owner;
    }
}
