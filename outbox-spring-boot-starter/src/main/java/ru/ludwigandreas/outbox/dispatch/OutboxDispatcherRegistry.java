package ru.ludwigandreas.outbox.dispatch;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

public class OutboxDispatcherRegistry {

    private final Map<String, OutboxDispatcher> dispatchersByTransport;

    public OutboxDispatcherRegistry(List<OutboxDispatcher> dispatchers) {
        this.dispatchersByTransport = dispatchers.stream()
                .collect(Collectors.toUnmodifiableMap(d -> d.transport().toUpperCase(Locale.ROOT), Function.identity()));
    }

    public Optional<OutboxDispatcher> find(String transport) {
        return Optional.ofNullable(dispatchersByTransport.get(transport.toUpperCase(Locale.ROOT)));
    }
}
