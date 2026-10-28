package ru.ludwigandreas.archrules.fixture.bad.admin.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Not administrative: no admin annotation and not under /admin/, so none of the rules concern it. */
@RestController
@RequestMapping("/api/v1/things")
public class OrdinaryApiController {

    @GetMapping
    public String list() {
        return "things";
    }
}
