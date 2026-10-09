package ru.ludwigandreas.notification.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import ru.ludwigandreas.notification.repository.entity.AnnouncementEmailRunEntity;

/** Fan-out run reads, as QueryDSL against the generated Q-type rather than derived method names. */
public interface AnnouncementEmailRunQueryRepository {

    /** The one run for an announcement, if it has one. */
    Optional<AnnouncementEmailRunEntity> findForAnnouncement(UUID announcementId);

    /**
     * Runs the scheduler should work on: not finished, oldest first.
     *
     * <p>Oldest first so a long run cannot be starved by newer ones, and bounded so one cycle cannot
     * take the lock for an unbounded time.
     */
    List<AnnouncementEmailRunEntity> findClaimable(int limit);

    /**
     * The subjects of an {@code EVERYONE} audience, after a cursor, in id order.
     *
     * <p>A keyset page rather than an offset: it stays the same speed at batch one and batch two
     * hundred, and it cannot skip or repeat a row when the underlying set shifts mid-run - which over
     * minutes it will, as people are created and deactivated.
     */
    List<String> activeSubjectsAfter(String cursorSubject, int limit);

    /** The same, restricted to holders of one role code as the directory names it. */
    List<String> activeSubjectsWithRoleAfter(String roleCode, String cursorSubject, int limit);
}
