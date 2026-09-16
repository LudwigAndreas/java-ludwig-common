package ru.ludwigandreas.archrules.fixture.bad.boundary.catalog.web;

import org.springframework.web.bind.annotation.RestController;

@RestController
public class NeighbourController {

    public String ping() {
        return "pong";
    }
}
