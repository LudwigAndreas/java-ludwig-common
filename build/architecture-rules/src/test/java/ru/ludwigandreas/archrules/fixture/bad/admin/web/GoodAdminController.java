package ru.ludwigandreas.archrules.fixture.bad.admin.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.ludwigandreas.archrules.fixture.bad.admin.service.ThingAdminService;
import ru.ludwigandreas.security.admin.AdminObject;
import ru.ludwigandreas.security.operation.RequiresAuthentication;
import ru.ludwigandreas.security.operation.RequiresOperation;

/** The correct shape: every handler declares its operation, one gate, under /admin/v1/, through a service. */
@RestController
@RequestMapping("/admin/v1/things")
public class GoodAdminController {

    private final ThingAdminService service;

    public GoodAdminController(ThingAdminService service) {
        this.service = service;
    }

    @GetMapping
    @RequiresOperation("things.thing.read")
    public String list() {
        return "things";
    }

    @PostMapping("/{id}/discard")
    @RequiresOperation("things.thing.discard")
    public String discard(@PathVariable @AdminObject String id) {
        return service.discard(id);
    }

    @GetMapping("/mine")
    @RequiresAuthentication
    public String mine() {
        return "mine";
    }
}
