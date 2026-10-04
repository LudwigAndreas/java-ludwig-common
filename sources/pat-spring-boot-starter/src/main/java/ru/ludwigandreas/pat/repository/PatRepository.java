package ru.ludwigandreas.pat.repository;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.ludwigandreas.pat.entity.PatEntity;

/**
 * Persistence for personal access tokens.
 *
 * <p>Deliberately declares <b>no derived query methods</b> - no {@code findByKeyId}, no
 * {@code findByOwnerSubject}. Every read in this module is a QueryDSL predicate against the generated
 * Q-type, in {@link PatQueryRepository}, which is the platform's data-access rule and is not relaxed here.
 *
 * <p>So this interface exists only for what Spring Data gives for free and QueryDSL does not: {@code save},
 * {@code findById}, {@code delete} and the transaction/flush plumbing around them. A derived method added
 * here would compile, work, and be the first of a dozen - which is how a module ends up with two query
 * mechanisms and no way to tell which one a given read used.
 */
public interface PatRepository extends JpaRepository<PatEntity, UUID> {
}
