package ru.ludwigandreas.restclient.core;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.PropertyNamingStrategy;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.TimeZone;
import org.springframework.beans.BeanUtils;
import ru.ludwigandreas.restclient.config.SerializationProperties;

/**
 * Produces a client's {@code ObjectMapper}: the application's own, or a fork of it.
 *
 * <p>Forking only when something is configured is the important part. The application's mapper
 * carries its modules, its {@code JavaTimeModule} configuration and its mixins, and a client that
 * silently used a fresh mapper would serialize dates differently from the rest of the service for
 * reasons nobody could find. A fork is a {@code copy()} of the application's mapper, so everything
 * that was configured globally still applies and only the named differences change.
 */
public final class ClientObjectMappers {

    private ClientObjectMappers() {
    }

    /**
     * The mapper for one client.
     *
     * @return {@code base} itself when the client configures nothing, otherwise a copy with the
     *         client's settings applied
     */
    public static ObjectMapper forClient(String clientName, ObjectMapper base,
                                         SerializationProperties props) {
        if (!customized(props)) {
            return base;
        }
        ObjectMapper mapper = base.copy();
        if (props.getPropertyNamingStrategy() != null) {
            mapper.setPropertyNamingStrategy(namingStrategy(clientName, props.getPropertyNamingStrategy()));
        }
        if (props.getDateFormat() != null) {
            SimpleDateFormat format = new SimpleDateFormat(props.getDateFormat(), Locale.ROOT);
            if (props.getTimeZone() != null) {
                format.setTimeZone(TimeZone.getTimeZone(props.getTimeZone()));
            }
            mapper.setDateFormat(format);
        }
        if (props.getTimeZone() != null) {
            mapper.setTimeZone(TimeZone.getTimeZone(props.getTimeZone()));
        }
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                Boolean.TRUE.equals(props.getFailOnUnknownProperties()));
        if (Boolean.TRUE.equals(props.getExcludeNulls())) {
            mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        }
        for (String moduleClass : props.getModules()) {
            mapper.registerModule(instantiate(clientName, moduleClass));
        }
        return mapper;
    }

    private static boolean customized(SerializationProperties props) {
        return props.getPropertyNamingStrategy() != null
                || props.getDateFormat() != null
                || props.getTimeZone() != null
                || Boolean.TRUE.equals(props.getFailOnUnknownProperties())
                || !props.getModules().isEmpty();
    }

    private static PropertyNamingStrategy namingStrategy(String clientName, String name) {
        return switch (name.toUpperCase(Locale.ROOT).replace('-', '_')) {
            case "SNAKE_CASE" -> PropertyNamingStrategies.SNAKE_CASE;
            case "UPPER_CAMEL_CASE" -> PropertyNamingStrategies.UPPER_CAMEL_CASE;
            case "LOWER_CAMEL_CASE" -> PropertyNamingStrategies.LOWER_CAMEL_CASE;
            case "LOWER_CASE" -> PropertyNamingStrategies.LOWER_CASE;
            case "KEBAB_CASE" -> PropertyNamingStrategies.KEBAB_CASE;
            case "LOWER_DOT_CASE" -> PropertyNamingStrategies.LOWER_DOT_CASE;
            default -> throw new IllegalStateException("Client '" + clientName
                    + "': unknown serialization.property-naming-strategy '" + name + "'. One of "
                    + "SNAKE_CASE, UPPER_CAMEL_CASE, LOWER_CAMEL_CASE, LOWER_CASE, KEBAB_CASE, "
                    + "LOWER_DOT_CASE.");
        };
    }

    private static Module instantiate(String clientName, String className) {
        try {
            Class<?> type = Class.forName(className);
            if (!Module.class.isAssignableFrom(type)) {
                throw new IllegalStateException("Client '" + clientName + "': serialization.modules "
                        + "lists '" + className + "', which is not a Jackson Module.");
            }
            return (Module) BeanUtils.instantiateClass(type);
        } catch (ClassNotFoundException ex) {
            throw new IllegalStateException("Client '" + clientName + "': serialization.modules lists "
                    + "'" + className + "', which is not on the classpath.", ex);
        }
    }
}
