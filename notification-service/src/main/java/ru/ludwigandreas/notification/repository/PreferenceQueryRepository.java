package ru.ludwigandreas.notification.repository;

import java.util.List;
import java.util.Optional;
import ru.ludwigandreas.notification.repository.entity.ChannelKind;
import ru.ludwigandreas.notification.repository.entity.RecipientPreferenceEntity;

/** Preference reads, all of them indexed lookups by subject. */
public interface PreferenceQueryRepository {

    /**
     * Every preference row that could bear on one category/channel pair: the exact match, the
     * per-category any-channel row, the per-channel wildcard-category row and the total wildcard.
     *
     * <p>Fetching all four and resolving precedence in the service rather than asking the database
     * for "the winning row" keeps the precedence rule in one readable place, and it is four rows -
     * the database cannot express the rule more cheaply than the service can apply it.
     */
    List<RecipientPreferenceEntity> findApplicable(String userId, String category, ChannelKind channel);

    /** Everything this recipient has ever set, for the self-service and support screens. */
    List<RecipientPreferenceEntity> findAllForUser(String userId);

    /** The exact row a write would replace, so setting a preference twice updates rather than duplicates. */
    Optional<RecipientPreferenceEntity> findExact(String userId, String category, ChannelKind channel);
}
