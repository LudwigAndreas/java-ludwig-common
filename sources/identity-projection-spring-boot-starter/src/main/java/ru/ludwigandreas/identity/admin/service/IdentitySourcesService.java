package ru.ludwigandreas.identity.admin.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.annotation.Transactional;
import ru.ludwigandreas.identity.feed.IdentityFeed;
import ru.ludwigandreas.identity.projection.IdentitySources;
import ru.ludwigandreas.identity.repository.SecurityUserRepository;
import ru.ludwigandreas.identity.repository.SecurityUserRepository.SourceCount;

/**
 * The identity sources as an administrator sees them: what feeds each, how many of its users are in each status, when
 * its newest applied change happened, and whatever each feed reports about itself. Counts only - never a user.
 */
public class IdentitySourcesService {

    private final IdentitySources sources;
    private final ObjectProvider<IdentityFeed> feeds;
    private final SecurityUserRepository users;

    /**
     * Creates the service.
     *
     * @param sources the declared sources
     * @param feeds   every feed bean
     * @param users   the user table
     */
    public IdentitySourcesService(IdentitySources sources, ObjectProvider<IdentityFeed> feeds,
                                  SecurityUserRepository users) {
        this.sources = sources;
        this.feeds = feeds;
        this.users = users;
    }

    /**
     * Every declared source.
     *
     * @return the sources, ordered by name
     */
    @Transactional(readOnly = true)
    public List<IdentitySourceView> sources() {
        Map<String, Map<String, Long>> counts = new TreeMap<>();
        Map<String, Instant> lastChange = new LinkedHashMap<>();
        for (SourceCount row : users.countBySource()) {
            counts.computeIfAbsent(row.source(), source -> new TreeMap<>())
                    .put(row.status().name(), row.users());
            if (row.lastChangeAt() != null) {
                lastChange.merge(row.source(), row.lastChangeAt(), (left, right) -> left.isAfter(right) ? left : right);
            }
        }
        List<IdentityFeed> all = feeds.orderedStream().toList();
        List<IdentitySourceView> views = new ArrayList<>();
        for (String name : new TreeSet<>(sources.names())) {
            List<IdentityFeedView> served = all.stream().filter(feed -> feed.source().equals(name))
                    .map(feed -> new IdentityFeedView(feed.kind(), feed.status())).toList();
            views.add(new IdentitySourceView(name, served, counts.getOrDefault(name, Map.of()), lastChange.get(name)));
        }
        return views;
    }

    /**
     * One source.
     *
     * @param name         the source
     * @param feeds        the feeds serving it; empty when its users change only through code
     * @param users        users by status
     * @param lastChangeAt when its newest applied change happened at the source, or null when it has no user
     */
    public record IdentitySourceView(String name, List<IdentityFeedView> feeds, Map<String, Long> users,
                                     Instant lastChangeAt) {
    }

    /**
     * One feed of a source.
     *
     * @param kind   {@code kafka}, {@code directory}, or a service's own
     * @param status what the feed reports about itself
     */
    public record IdentityFeedView(String kind, Map<String, Object> status) {
    }
}
