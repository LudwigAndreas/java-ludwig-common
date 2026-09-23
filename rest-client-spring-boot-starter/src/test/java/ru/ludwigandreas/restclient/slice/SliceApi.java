package ru.ludwigandreas.restclient.slice;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.service.annotation.GetExchange;
import ru.ludwigandreas.restclient.annotation.LudwigRestClient;

/** A declarative interface the slice is expected to discover without being told where to look. */
@LudwigRestClient("slice")
public interface SliceApi {

    /** Reads one thing. */
    @GetExchange("/things/{id}")
    String thing(@PathVariable String id);
}
