package ru.ludwigandreas.identity.feed;

import java.util.Map;

/**
 * A feed serving one declared source, registered as a bean by the module that provides it. The projection validates
 * at startup that every feed names a declared source, and lists each feed's status under the source in
 * {@code GET /admin/v1/identity/sources}; it never depends on a feed module.
 */
public interface IdentityFeed {

    /**
     * The source this feed serves.
     *
     * @return a name declared under {@code ludwig.identity.sources}
     */
    String source();

    /**
     * What kind of feed this is, for the sources listing.
     *
     * @return for example {@code kafka} or {@code directory}
     */
    String kind();

    /**
     * The feed's current state for the sources listing: its topic, its watermark, its last run. Never user data.
     *
     * @return JSON-serialisable values by name
     */
    Map<String, Object> status();
}
