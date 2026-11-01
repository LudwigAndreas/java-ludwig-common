package ru.ludwigandreas.restclient.admin.web;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.ludwigandreas.restclient.admin.RestClientAdminOperations;
import ru.ludwigandreas.restclient.admin.service.RestClientAdminService;
import ru.ludwigandreas.restclient.admin.service.RestClientAdminService.ClientView;
import ru.ludwigandreas.security.operation.RequiresOperation;

/**
 * The {@code restclient} area's listing. Tokens and breakers are replica controls - {@code restclienttokenreset},
 * {@code restclientbreakers} and {@code restclientbreaker} under {@code POST /admin/v1/replicas/controls/} - because
 * each replica holds its own.
 */
@RestController
@RequestMapping("/admin/v1/restclient")
public class RestClientAdminController {

    private final RestClientAdminService service;

    /**
     * Creates the controller.
     *
     * @param service the listing
     */
    public RestClientAdminController(RestClientAdminService service) {
        this.service = service;
    }

    /**
     * The named clients with their host, auth kind and resilience settings, never a credential.
     *
     * @return them
     */
    @GetMapping("/clients")
    @RequiresOperation(RestClientAdminOperations.CLIENT_READ)
    public List<ClientView> clients() {
        return service.clients();
    }
}
