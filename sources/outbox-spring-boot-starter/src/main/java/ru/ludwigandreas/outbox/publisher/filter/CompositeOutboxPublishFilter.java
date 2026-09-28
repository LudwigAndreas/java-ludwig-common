package ru.ludwigandreas.outbox.publisher.filter;

import ru.ludwigandreas.outbox.api.OutboxEvent;

import java.util.List;

/** ANDs together all injected filter beans; an empty list always allows publishing. */
public class CompositeOutboxPublishFilter implements OutboxPublishFilter {

    private final List<OutboxPublishFilter> filters;

    public CompositeOutboxPublishFilter(List<OutboxPublishFilter> filters) {
        this.filters = List.copyOf(filters);
    }

    @Override
    public boolean shouldPublish(OutboxEvent event) {
        return filters.stream().allMatch(filter -> filter.shouldPublish(event));
    }
}
