package ru.ludwigandreas.outbox.repository;

import ru.ludwigandreas.db.core.repository.BaseRepository;
import ru.ludwigandreas.outbox.entity.OutboxStatusHistory;

import java.util.List;
import java.util.UUID;

public interface OutboxStatusHistoryRepository extends BaseRepository<OutboxStatusHistory, UUID> {

    List<OutboxStatusHistory> findByOutboxMessageIdOrderByOccurredAtAsc(UUID outboxMessageId);
}
