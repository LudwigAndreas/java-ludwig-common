package ru.ludwigandreas.identity.projection;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import ru.ludwigandreas.identity.feed.IdentityFeed;
import ru.ludwigandreas.security.exception.SecurityConfigurationException;

/**
 * Checks, once every bean exists, that every feed names a declared source, and logs the declared sources no feed
 * serves. Such a source is legitimate - an administrative service reading the tables, a source only seeded locally -
 * so it is a log line, not a failure.
 */
@Slf4j
public class IdentitySourceValidator implements SmartInitializingSingleton {

    private final IdentitySources sources;
    private final ObjectProvider<IdentityFeed> feeds;

    /**
     * Creates the validator.
     *
     * @param sources the declared sources
     * @param feeds   every feed bean
     */
    public IdentitySourceValidator(IdentitySources sources, ObjectProvider<IdentityFeed> feeds) {
        this.sources = sources;
        this.feeds = feeds;
    }

    @Override
    public void afterSingletonsInstantiated() {
        List<IdentityFeed> all = feeds.orderedStream().toList();
        List<String> undeclared = all.stream()
                .filter(feed -> !sources.isDeclared(feed.source()))
                .map(feed -> feed.kind() + " feed for '" + feed.source() + "'")
                .toList();
        if (!undeclared.isEmpty()) {
            throw new SecurityConfigurationException("Identity feeds name sources not declared under"
                    + " ludwig.identity.sources (declared: " + sources.names() + "): " + undeclared);
        }
        Set<String> unserved = new TreeSet<>(sources.names());
        all.forEach(feed -> unserved.remove(feed.source()));
        if (!unserved.isEmpty()) {
            log.info("Identity sources no feed serves: {}. Their users change only through code calling"
                    + " IdentityProjection.", unserved);
        }
    }
}
