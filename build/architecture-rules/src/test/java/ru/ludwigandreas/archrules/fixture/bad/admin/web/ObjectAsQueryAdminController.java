package ru.ludwigandreas.archrules.fixture.bad.admin.web;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.ludwigandreas.security.admin.AdminAccess;
import ru.ludwigandreas.security.admin.AdminObject;

/** Violates admin-object-is-a-path-variable: the object is a request parameter, which the guard never reads. */
@RestController
@RequestMapping("/admin/v1/things")
public class ObjectAsQueryAdminController {

    @PostMapping("/poke")
    @AdminAccess("things.operate")
    public String poke(@RequestParam @AdminObject String thing) {
        return thing;
    }
}
