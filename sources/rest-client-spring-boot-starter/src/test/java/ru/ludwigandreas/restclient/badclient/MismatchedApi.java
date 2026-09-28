package ru.ludwigandreas.restclient.badclient;

import org.springframework.web.service.annotation.GetExchange;
import reactor.core.publisher.Mono;
import ru.ludwigandreas.restclient.annotation.LudwigRestClient;

/**
 * Deliberately wrong: a reactive return type on a {@code sync} client.
 *
 * <p>It exists so a test can prove the mismatch is a startup failure naming the interface, the
 * method and the client, rather than a {@code ClassCastException} on the first call.
 */
@LudwigRestClient("billing")
public interface MismatchedApi {

    /** Returns a {@code Mono} from a blocking client, which cannot work. */
    @GetExchange("/invoices/{id}")
    Mono<String> invoice(@org.springframework.web.bind.annotation.PathVariable String id);
}
