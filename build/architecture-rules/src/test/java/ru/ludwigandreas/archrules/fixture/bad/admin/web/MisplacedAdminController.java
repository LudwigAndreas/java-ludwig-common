package ru.ludwigandreas.archrules.fixture.bad.admin.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.ludwigandreas.security.operation.RequiresOperation;

/** Violates mapped-under-the-versioned-prefix: under /admin/, but outside the versioned /admin/v1/. */
@RestController
@RequestMapping("/admin/locks")
public class MisplacedAdminController {

    @GetMapping
    @RequiresOperation("locks.lock.read")
    public String list() {
        return "locks";
    }
}
