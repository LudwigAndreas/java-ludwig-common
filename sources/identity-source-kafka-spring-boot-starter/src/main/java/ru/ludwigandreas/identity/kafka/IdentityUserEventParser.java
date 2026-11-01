package ru.ludwigandreas.identity.kafka;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.converter.MessageConversionException;

/**
 * Reads an {@link IdentityUserEvent} from a record's text, turning anything that is not an event of the contract into
 * a {@link MessageConversionException}: the platform's consumer treats that as non-retryable and dead-letters the
 * record on its first delivery, because it will fail identically forever.
 */
public final class IdentityUserEventParser {

    private static final Logger log = LoggerFactory.getLogger(IdentityUserEventParser.class);

    private final ObjectMapper objectMapper;

    /**
     * Creates the parser.
     *
     * @param objectMapper parses the events; needs {@code java.time} support
     */
    public IdentityUserEventParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Parses one event.
     *
     * @param topic   the record's topic, for the log line
     * @param payload the record's value
     * @return the event
     * @throws MessageConversionException when the payload is not an event of the contract
     */
    public IdentityUserEvent parse(String topic, String payload) {
        try {
            IdentityUserEvent event = payload == null ? null : objectMapper.readValue(payload, IdentityUserEvent.class);
            if (event == null) {
                throw new MessageConversionException("Empty identity event on " + topic);
            }
            return event;
        } catch (JacksonException e) {
            // The payload itself is deliberately not logged - it is directory data about a person.
            log.error("Dead-lettering an unparseable identity event on {}: {}", topic, e.getOriginalMessage());
            throw new MessageConversionException("Unparseable identity event on " + topic, e);
        }
    }
}
