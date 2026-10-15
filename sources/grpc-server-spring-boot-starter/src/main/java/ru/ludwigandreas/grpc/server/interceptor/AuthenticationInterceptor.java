package ru.ludwigandreas.grpc.server.interceptor;

import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import ru.ludwigandreas.grpc.core.context.GrpcCallContext;
import ru.ludwigandreas.grpc.core.context.ThreadBinding;
import ru.ludwigandreas.grpc.core.metadata.GrpcMetadataKeys;
import ru.ludwigandreas.grpc.server.spi.GrpcCallContextContributor;

/**
 * Authenticates a call from its bearer token, with the application's own {@link JwtDecoder} and
 * JWT converter - so a gRPC caller and an HTTP caller holding the same token are the same
 * principal with the same authorities.
 *
 * <p>Deny by default: a method is served without a credential only if it is on the permit list
 * handed in, which holds the health service, an enabled reflection service and whatever
 * {@code ludwig.grpc.server.security.permit-unauthenticated} names.
 *
 * <p>The second half of this class, the {@link GrpcCallContextContributor}, binds the principal to
 * {@link SecurityContextHolder} around every callback. It always replaces what the thread held, a
 * permitted call included: an executor thread that kept the previous caller's context would hand
 * an anonymous call someone else's identity.
 */
public final class AuthenticationInterceptor implements BuiltInServerInterceptor, GrpcCallContextContributor {

    /**
     * The contributor order: the principal is bound first, because resolving stored preferences
     * needs it.
     */
    public static final int ORDER = -300;

    private static final String BEARER = "bearer ";

    private final JwtDecoder decoder;
    private final Converter<Jwt, ? extends AbstractAuthenticationToken> converter;
    private final Set<String> permitted;
    private final List<Metadata.Key<String>> forbidden;

    /**
     * Creates the interceptor.
     *
     * @param decoder          the application's JWT decoder
     * @param converter        the application's JWT-to-authentication converter
     * @param permitted        methods served without a credential, as {@code Service/Method} or
     *                         {@code Service/*}
     * @param forbiddenHeaders identity-bearing metadata a caller could forge, removed before the handler
     */
    public AuthenticationInterceptor(JwtDecoder decoder,
                                     Converter<Jwt, ? extends AbstractAuthenticationToken> converter,
                                     List<String> permitted, List<String> forbiddenHeaders) {
        this.decoder = decoder;
        this.converter = converter;
        this.permitted = Set.copyOf(permitted);
        this.forbidden = forbiddenHeaders.stream()
                .map(header -> Metadata.Key.of(header.toLowerCase(Locale.ROOT), Metadata.ASCII_STRING_MARSHALLER))
                .collect(Collectors.toUnmodifiableList());
    }

    @Override
    public Stage stage() {
        return Stage.AUTHENTICATION;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public <Q, R> ServerCall.Listener<Q> interceptCall(ServerCall<Q, R> call, Metadata headers,
                                                       ServerCallHandler<Q, R> next) {
        forbidden.forEach(headers::removeAll);
        if (permitted(call.getMethodDescriptor().getFullMethodName(), call.getMethodDescriptor().getServiceName())) {
            return next.startCall(call, headers);
        }
        String token = bearerToken(headers.get(GrpcMetadataKeys.AUTHORIZATION));
        Context authenticated = Context.current()
                .withValue(GrpcCallContext.AUTHENTICATION, authenticate(token))
                .withValue(GrpcCallContext.INBOUND_BEARER_TOKEN, token);
        return Contexts.interceptCall(authenticated, call, headers, next);
    }

    @Override
    public Context contribute(Context context, Metadata headers, String fullMethodName) {
        return context;
    }

    @Override
    public ThreadBinding binding() {
        return context -> {
            SecurityContext previous = SecurityContextHolder.getContext();
            SecurityContext current = SecurityContextHolder.createEmptyContext();
            if (GrpcCallContext.AUTHENTICATION.get(context) instanceof Authentication authentication) {
                current.setAuthentication(authentication);
            }
            SecurityContextHolder.setContext(current);
            return () -> {
                if (previous.getAuthentication() == null) {
                    SecurityContextHolder.clearContext();
                } else {
                    SecurityContextHolder.setContext(previous);
                }
            };
        };
    }

    private boolean permitted(String fullMethodName, String serviceName) {
        return permitted.contains(fullMethodName) || permitted.contains(serviceName + "/*");
    }

    private static String bearerToken(String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            throw new AuthenticationCredentialsNotFoundException("No bearer token in the authorization metadata");
        }
        return authorization.substring(BEARER.length()).trim();
    }

    private Authentication authenticate(String token) {
        try {
            Jwt jwt = decoder.decode(token);
            Authentication authentication = converter.convert(jwt);
            if (authentication == null) {
                throw new BadCredentialsException("The token was accepted but yields no principal");
            }
            return authentication;
        } catch (JwtException rejected) {
            throw new BadCredentialsException("The bearer token was rejected", rejected);
        }
    }
}
