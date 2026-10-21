package ru.ludwigandreas.outbox.dispatch;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class OutboxDispatcherRegistry {

    private final Map<String, OutboxDispatcher> dispatchersByTransport;

    public OutboxDispatcherRegistry(List<OutboxDispatcher> dispatchers, List<OutboxDispatcherGroup> groups) {
        this.dispatchersByTransport = Stream.concat(dispatchers.stream(),
                        groups.stream().flatMap(group -> group.dispatchers().stream()))
                .collect(Collectors.toUnmodifiableMap(
                        d -> d.transport().toUpperCase(Locale.ROOT), Function.identity()));
    }

    public Optional<OutboxDispatcher> find(String transport) {
        return Optional.ofNullable(dispatchersByTransport.get(transport.toUpperCase(Locale.ROOT)));
    }
}
