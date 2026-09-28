package ru.ludwigandreas.notification.service.template;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.ludwigandreas.notification.repository.TemplateRevisionRepository;
import ru.ludwigandreas.notification.repository.entity.TemplateRevisionEntity;

/**
 * Keeps the record of which template text produced which message.
 *
 * <p>Hot reload is what makes this necessary. A wording change is a file edit and a config rollout,
 * which is the point - but it means the directory is not a history, and "what exactly did we send
 * that customer in March?" cannot be answered by looking at the file. Every render records the hash
 * of the source it used on the delivery; this service is what maps that hash back to readable text
 * and a revision number.
 *
 * <h2>Cost</h2>
 *
 * <p>One statement the first time a given text is seen by this process, and nothing at all
 * afterwards - {@link #seen} is the guard, and it is an in-memory set because the question it
 * answers ("have I already written this row?") does not need to be right across replicas, only
 * cheap. A second replica writing the same row again is an upsert that touches
 * {@code last_seen_at}, which is what it means anyway.
 *
 * <p>{@code REQUIRES_NEW} because this runs from the dispatch path, which is deliberately
 * transaction-free while a provider call is in flight, and from the preview endpoint, which is not
 * transactional at all. A suspended-and-resumed caller transaction is not a concern here because
 * there is none; what the propagation buys is that recording a revision can never widen or outlive
 * somebody else's transaction.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TemplateRevisionService {

    private final TemplateRevisionRepository repository;

    /**
     * Hashes this process has already written a row for.
     *
     * <p>Final, so the field satisfies the "no mutable state on a singleton bean" rule while the set
     * it points at is concurrent and mutable - which is the shape that rule actually wants, as
     * opposed to a reassignable field two threads can observe differently.
     */
    private final Set<String> seen = ConcurrentHashMap.newKeySet();

    /**
     * Records that {@code source} was used to render {@code templateName}, if this is the first time
     * this process has seen that exact text.
     *
     * <p>Never throws. A failure to record a revision must not stop a notification going out: the
     * revision trail is diagnostics, and trading a delivery for a diagnostic is the wrong way round.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordUsage(String templateName, String contentHash, String source) {
        String key = templateName + "@" + contentHash;
        if (!seen.add(key)) {
            return;
        }
        try {
            int revision = repository.highestRevision(templateName) + 1;
            repository.recordUsage(UUID.randomUUID(), templateName, contentHash, revision, source,
                    Instant.now());
        } catch (RuntimeException e) {
            // Re-armed so a transient failure does not permanently stop this process recording the
            // revision - the next render of the same template will try again.
            seen.remove(key);
            log.warn("Could not record template revision for {} (hash {}); delivery is unaffected",
                    templateName, contentHash, e);
        }
    }

    @Transactional(readOnly = true)
    public List<TemplateRevisionEntity> revisionsOf(String templateName) {
        return repository.listRevisions(templateName);
    }
}
