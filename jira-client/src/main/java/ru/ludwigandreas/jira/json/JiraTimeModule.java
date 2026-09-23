package ru.ludwigandreas.jira.json;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import java.io.IOException;
import java.time.OffsetDateTime;

/**
 * Registers Jira's timestamp dialect on top of {@code jackson-datatype-jsr310}.
 *
 * <p>Scoped to {@link OffsetDateTime} only. Jira's date-only fields ({@code duedate},
 * {@code releaseDate}, {@code startDate}) are plain {@code yyyy-MM-dd} and are modelled as
 * {@link java.time.LocalDate}, which the stock JSR-310 module already handles correctly - overriding it
 * would only create a second place for the format to be wrong.
 */
public final class JiraTimeModule extends SimpleModule {

    private static final long serialVersionUID = 1L;

    /** Registers the Jira-dialect {@link OffsetDateTime} serializer and deserializer. */
    public JiraTimeModule() {
        super("jira-time");
        addSerializer(OffsetDateTime.class, new JiraOffsetDateTimeSerializer());
        addDeserializer(OffsetDateTime.class, new JiraOffsetDateTimeDeserializer());
    }

    /** Writes {@code 2024-01-15T10:30:00.000+0300}, the only form Jira's input parser accepts. */
    private static final class JiraOffsetDateTimeSerializer extends JsonSerializer<OffsetDateTime> {

        @Override
        public void serialize(OffsetDateTime value, JsonGenerator generator, SerializerProvider providers)
                throws IOException {
            generator.writeString(JiraDateTimeFormats.writer().format(value));
        }
    }

    /**
     * Reads every offset spelling Jira emits, and treats an empty string as a null - which is what Jira
     * sends for a cleared date custom field.
     */
    private static final class JiraOffsetDateTimeDeserializer extends JsonDeserializer<OffsetDateTime> {

        @Override
        public OffsetDateTime deserialize(JsonParser parser, DeserializationContext context)
                throws IOException {
            String text = parser.getText();
            if (text == null || text.isBlank()) {
                return null;
            }
            return OffsetDateTime.parse(text.trim(), JiraDateTimeFormats.parser());
        }
    }
}
