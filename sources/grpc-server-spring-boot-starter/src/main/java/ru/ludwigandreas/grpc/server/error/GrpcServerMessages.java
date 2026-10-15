package ru.ludwigandreas.grpc.server.error;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.ResourceBundle;

/**
 * The starter's startup and warning texts, read from its bundle rather than written in the code.
 *
 * <p>Always the root bundle: these lines go to an operator's log and to a startup failure, where
 * one language is what makes them searchable. The problems a <em>caller</em> sees are rendered
 * through web-core's {@code ProblemMessages} in the caller's locale, from the same file.
 */
public final class GrpcServerMessages {

    /**
     * The bundle's basename.
     */
    public static final String BUNDLE = "i18n/ludwig-grpc-server-messages";

    private static final String STARTUP_PREFIX = "ludwig.grpc.server.startup.";

    private GrpcServerMessages() {
    }

    /**
     * A startup message.
     *
     * @param name the key after {@code ludwig.grpc.server.startup.}
     * @param args the message arguments
     * @return the formatted text
     */
    public static String startup(String name, Object... args) {
        String pattern = ResourceBundle.getBundle(BUNDLE, Locale.ROOT).getString(STARTUP_PREFIX + name);
        return new MessageFormat(pattern, Locale.ROOT).format(args);
    }
}
