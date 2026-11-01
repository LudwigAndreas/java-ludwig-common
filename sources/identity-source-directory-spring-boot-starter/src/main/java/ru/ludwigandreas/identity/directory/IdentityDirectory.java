package ru.ludwigandreas.identity.directory;

/**
 * A provider's user directory, read one page at a time - the one thing a service writes to feed the identity
 * projection from an API. Implement it against the provider with the service's own {@code @LudwigRestClient} and
 * register it as a bean; this starter syncs it incrementally, sweeps it completely, persists its progress and runs
 * both on job-core.
 *
 * <p>Contract, when {@link DirectoryQuery#changedSince()} is given: return only users changed at or after it, in
 * ascending change order, and set the page's {@link DirectoryPage#watermark()} to the last change time it contains.
 * When it is null, list every user of the source. A page may hold upserts, disables and removes; a user the provider
 * deleted without a change record is caught by the next sweep.
 */
public interface IdentityDirectory {

    /**
     * The source this directory lists.
     *
     * @return a name declared under {@code ludwig.identity.sources}
     */
    String source();

    /**
     * One page.
     *
     * @param query the cursor, the change filter and the page size
     * @return the page; a null {@link DirectoryPage#nextCursor()} ends the listing
     */
    DirectoryPage fetch(DirectoryQuery query);

    /**
     * Whether the provider can filter by change time. When it cannot, every sync is a sweep.
     *
     * @return true by default
     */
    default boolean filtersByChange() {
        return true;
    }
}
