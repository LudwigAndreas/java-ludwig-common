package ru.ludwigandreas.identity.directory;

import java.time.Instant;
import java.util.List;
import ru.ludwigandreas.identity.feed.IdentityChange;

/**
 * One page of a directory listing.
 *
 * @param changes    the users on this page, as changes of the directory's source
 * @param nextCursor the cursor of the next page, or null when this is the last
 * @param watermark  the last change time this page contains, or null when the provider does not say; an incremental
 *                   sync resumes from the highest watermark it committed
 */
public record DirectoryPage(List<IdentityChange> changes, String nextCursor, Instant watermark) {

    public DirectoryPage {
        changes = List.copyOf(changes);
    }
}
