package ru.ludwigandreas.archrules.fixture.bad.admin.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.ludwigandreas.security.admin.AdminAccess;
import ru.ludwigandreas.security.admin.AdminAuthenticated;
import ru.ludwigandreas.archrules.fixture.bad.admin.service.ThingAdminService;

/** The correct shape: every handler declares its operation, one gate, under /admin/v1/, through a service. */
@RestController
@RequestMapping("/admin/v1/things")
public class GoodAdminController {

    private final ThingAdminService service;

    public GoodAdminController(ThingAdminService service) {
        this.service = service;
    }

    @GetMapping
    @AdminAccess("things.read")
    public String list() {
        return "things";
    }

    @PostMapping("/{id}/discard")
    @AdminAccess("things.privileged")
    public String discard(String id) {
        return service.discard(id);
    }

    @GetMapping("/mine")
    @AdminAuthenticated
    public String mine() {
        return "mine";
    }
}
