package ru.ludwigandreas.restclient.spi;

import java.lang.reflect.Method;

/**
 * What a {@link FallbackHandler} is told about the call it is standing in for.
 *
 * @param clientName  the named client
 * @param method      the HTTP method
 * @param uriTemplate the URI template when known, else the path
 * @param interfaceMethod the declarative-interface method being called. Never {@code null}:
 *                        fallbacks apply to declarative interfaces, because a call made through an
 *                        injected {@code RestClient} has no return type this starter knows in
 *                        advance and therefore nothing it could safely substitute
 * @param returnType  what the caller expects back; a handler must return an instance of it, or
 *                    rethrow
 * @param failure     why the call could not be completed
 */
public record FallbackContext(
        String clientName,
        String method,
        String uriTemplate,
        Method interfaceMethod,
        Class<?> returnType,
        Throwable failure) {
}
