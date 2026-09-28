package ru.ludwigandreas.ingest.engine;

import java.nio.file.FileSystems;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import ru.ludwigandreas.ingest.config.FileIngestProperties;
import ru.ludwigandreas.storage.api.ObjectStore;
import ru.ludwigandreas.storage.api.ObjectSummary;
import ru.ludwigandreas.storage.api.ObjectUri;

/**
 * Finds the objects a task should consider, cheaply enough to run every morning against a prefix
 * holding a year of them.
 *
 * <h2>The listing is not materialised, and the limit is applied while streaming</h2>
 *
 * <p>{@code ObjectStore#list} pages lazily, and this class keeps it that way: it filters and takes
 * inside the stream, so a prefix with three hundred and sixty-five objects fetches one page and
 * stops. Collecting the listing and filtering afterwards would work identically in every test and
 * would fetch every page in production, which is the failure the lazy listing exists to prevent.
 *
 * <p>The stream is closed in a try-with-resources, because it holds a connection between pages - see
 * {@code ObjectStore#list}. A discovery pass that leaked one per morning would exhaust the pool in a
 * fortnight, and the symptom would be a hang in connection-acquire rather than anything naming this
 * class.
 *
 * <h2>Sentinels are excluded from the candidates</h2>
 *
 * <p>A sentinel lives next to its data object and will match a loose pattern. Ingesting
 * {@code data.csv.done} as though it were data produces a run that reads a zero-byte object, applies
 * nothing, balances perfectly and completes - and then the identity constraint remembers it, so the
 * mistake is permanent. The exclusion is therefore structural rather than left to the pattern.
 */
@Slf4j
public class ObjectDiscovery {

    private final ObjectStore store;

    /**
     * Creates the discovery.
     *
     * @param store where the objects are
     */
    public ObjectDiscovery(ObjectStore store) {
        this.store = store;
    }

    /**
     * Lists the objects under a task's prefix whose names match its pattern.
     *
     * @param source the task's source configuration
     * @param arrival the task's arrival configuration, so sentinels can be excluded by name
     * @param limit  how many to return
     * @return the matching objects, oldest first, at most {@code limit} of them
     */
    public List<ObjectSummary> discover(FileIngestProperties.Source source,
                                        FileIngestProperties.Arrival arrival, int limit) {
        ObjectUri prefix = ObjectUri.parse(source.getUri());
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + source.getPattern());
        String sentinelSuffix = IngestNaming.sentinelSuffix(arrival.getSentinel());
        List<ObjectSummary> found = new ArrayList<>();
        try (Stream<ObjectSummary> listing = store.list(prefix.value())) {
            listing.filter(summary -> !isSentinel(summary, sentinelSuffix))
                    .filter(summary -> matcher.matches(Paths.get(summary.name())))
                    // Oldest first, because a backlog should be worked through in the order it
                    // arrived: a partner who sent Monday's file late and Tuesday's on time should
                    // have Monday's applied first, or Tuesday's corrections are overwritten by
                    // Monday's staler values.
                    .sorted((a, b) -> a.lastModified().compareTo(b.lastModified()))
                    .limit(limit)
                    .forEach(found::add);
        }
        log.debug("Discovery under {} matching '{}' found {} candidate(s)", prefix.value(),
                source.getPattern(), found.size());
        return found;
    }


    private boolean isSentinel(ObjectSummary summary, String sentinelSuffix) {
        return sentinelSuffix != null && summary.name().endsWith(sentinelSuffix);
    }
}
