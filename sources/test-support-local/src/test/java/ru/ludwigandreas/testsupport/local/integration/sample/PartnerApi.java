package ru.ludwigandreas.testsupport.local.integration.sample;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.service.annotation.GetExchange;
import ru.ludwigandreas.restclient.annotation.LudwigRestClient;

/** The sample's one partner, reached through the real rest-client pipeline. */
@LudwigRestClient("partner")
public interface PartnerApi {

    @GetExchange("/things/{id}")
    String thing(@PathVariable String id);

    @GetExchange("/slow-things/{id}")
    String slowThing(@PathVariable String id);
}
