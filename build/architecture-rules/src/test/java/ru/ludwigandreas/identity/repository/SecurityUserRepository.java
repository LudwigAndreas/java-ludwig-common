package ru.ludwigandreas.identity.repository;

import java.util.Optional;

/** A stand-in for the projection's repository: only its package and method names matter to the rule. */
public interface SecurityUserRepository {

    Optional<Object> findById(String id);

    Object save(Object user);

    void deleteAll();
}
