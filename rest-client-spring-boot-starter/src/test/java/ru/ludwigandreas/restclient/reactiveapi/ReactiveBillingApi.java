package ru.ludwigandreas.restclient.reactiveapi;

import org.springframework.web.service.annotation.GetExchange;
import reactor.core.publisher.Mono;
import ru.ludwigandreas.restclient.annotation.LudwigRestClient;

/** The same contract on an {@code async} client, returning a {@code Mono}. */
@LudwigRestClient("pricing")
public interface ReactiveBillingApi {

    /** Reads one price. */
    @GetExchange("/prices/{id}")
    Mono<String> price(@org.springframework.web.bind.annotation.PathVariable String id);
}
