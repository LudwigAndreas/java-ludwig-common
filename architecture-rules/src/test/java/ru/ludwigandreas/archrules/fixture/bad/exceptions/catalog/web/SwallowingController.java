package ru.ludwigandreas.archrules.fixture.bad.exceptions.catalog.web;

import java.io.IOException;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Violation: the controller decides how to translate a failure, and turns it into a 200. */
@RestController
@RequestMapping("/api/v1/reports")
public class SwallowingController {

    public String read(String key) {
        try {
            return load(key);
        } catch (IOException failure) {
            return "";
        }
    }

    private String load(String key) throws IOException {
        throw new IOException(key);
    }
}
