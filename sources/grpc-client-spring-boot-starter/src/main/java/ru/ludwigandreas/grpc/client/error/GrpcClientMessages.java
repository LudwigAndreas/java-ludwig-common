package ru.ludwigandreas.grpc.client.error;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.ResourceBundle;

/**
 * The starter's startup and warning texts, read from its bundle. Root locale: an operator's log is
 * searchable in one language.
 */
public final class GrpcClientMessages {

    /**
     * The bundle's basename.
     */
    public static final String BUNDLE = "i18n/ludwig-grpc-client-messages";

    private static final String STARTUP_PREFIX = "ludwig.grpc.client.startup.";

    private GrpcClientMessages() {
    }

    /**
     * A startup message.
     *
     * @param name the key after {@code ludwig.grpc.client.startup.}
     * @param args the message arguments
     * @return the formatted text
     */
    public static String startup(String name, Object... args) {
        String pattern = ResourceBundle.getBundle(BUNDLE, Locale.ROOT).getString(STARTUP_PREFIX + name);
        return new MessageFormat(pattern, Locale.ROOT).format(args);
    }
}
