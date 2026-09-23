package ru.ludwigandreas.restclient.integration;

import org.springframework.web.service.annotation.GetExchange;
import ru.ludwigandreas.restclient.annotation.LudwigRestClient;

/** A declarative interface on a {@code sync} client, as a consuming service would write one. */
@LudwigRestClient("billing")
interface BillingApi {

    /** Reads one invoice, with a URI template the metrics and the audit record can use. */
    @GetExchange("/invoices/{id}")
    String invoice(@org.springframework.web.bind.annotation.PathVariable String id);
}
