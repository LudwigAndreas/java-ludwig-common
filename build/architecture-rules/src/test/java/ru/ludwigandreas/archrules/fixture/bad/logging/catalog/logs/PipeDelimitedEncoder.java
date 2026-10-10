package ru.ludwigandreas.archrules.fixture.bad.logging.catalog.logs;

import ch.qos.logback.core.encoder.Encoder;
import java.nio.charset.StandardCharsets;

/** A second encoder: its own field order and its own delimiter, for other modules to write through. */
public class PipeDelimitedEncoder implements Encoder<Object> {

    @Override
    public byte[] encode(Object event) {
        return ("catalog|" + event).getBytes(StandardCharsets.UTF_8);
    }
}
