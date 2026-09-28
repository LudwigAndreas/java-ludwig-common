package ru.ludwigandreas.example.catalog.repository;

import java.util.UUID;
import ru.ludwigandreas.db.core.repository.BaseRepository;
import ru.ludwigandreas.example.catalog.repository.entity.CategoryEntity;

/** Categories are only ever looked up by id, which {@code JpaRepository} already types safely. */
public interface CategoryRepository extends BaseRepository<CategoryEntity, UUID> {
}
