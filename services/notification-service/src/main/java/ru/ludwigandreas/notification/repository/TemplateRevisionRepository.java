package ru.ludwigandreas.notification.repository;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.ludwigandreas.db.core.repository.BaseRepository;
import ru.ludwigandreas.notification.repository.entity.TemplateRevisionEntity;

/** Template version history; its reads live in {@link TemplateRevisionQueryRepository}. */
public interface TemplateRevisionRepository
        extends BaseRepository<TemplateRevisionEntity, UUID>, TemplateRevisionQueryRepository {

    /**
     * Records that this exact template text was used, or notes that it was used again.
     *
     * <h2>Why native</h2>
     *
     * <p>Two replicas rendering the same freshly-edited template both see an unrecorded hash and both
     * insert. With an ordinary {@code save} the loser takes a constraint violation, and that
     * violation would abort a transaction whose real work - recording a revision for observability -
     * is the least important thing happening at that moment. {@code ON CONFLICT DO UPDATE} makes the
     * loser's insert a touch of {@code last_seen_at} instead, which is exactly what it meant.
     *
     * <p>The {@code revision} number is computed as {@code max + 1} by the caller and is therefore
     * best-effort: two <em>different</em> new hashes appearing on two replicas in the same instant
     * can both claim the same number. That is cosmetic and it is not worth a lock - the content hash
     * is what a delivery records and what identifies the text, and it is unique by construction.
     */
    @Modifying
    @Query(value = """
            INSERT INTO notification_template_revision
                (id, template_name, content_hash, revision, source, first_seen_at, last_seen_at)
            VALUES (:id, :templateName, :contentHash, :revision, :source, :now, :now)
            ON CONFLICT (template_name, content_hash)
            DO UPDATE SET last_seen_at = EXCLUDED.last_seen_at
            """, nativeQuery = true)
    int recordUsage(@Param("id") UUID id,
                    @Param("templateName") String templateName,
                    @Param("contentHash") String contentHash,
                    @Param("revision") int revision,
                    @Param("source") String source,
                    @Param("now") Instant now);
}
