package ru.ludwigandreas.testsupport.local.architecture.violations.orphan;

import org.springframework.web.service.annotation.GetExchange;
import ru.ludwigandreas.restclient.annotation.LudwigRestClient;

/** A rest client nobody wrote a stub for. Outside the sample's package, so the sample never scans it. */
@LudwigRestClient(name = "orphan")
public interface OrphanApi {

    @GetExchange("/orphans")
    String orphans();
}
