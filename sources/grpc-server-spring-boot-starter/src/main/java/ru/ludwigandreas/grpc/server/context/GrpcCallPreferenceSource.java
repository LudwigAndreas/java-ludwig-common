package ru.ludwigandreas.grpc.server.context;

import java.time.ZoneId;
import java.util.Locale;
import java.util.Optional;
import ru.ludwigandreas.grpc.core.context.GrpcCallContext;
import ru.ludwigandreas.webcore.preference.UserPreferenceSource;

/**
 * What a gRPC caller asked for, as one more source for web-core's preference resolver.
 *
 * <p>The gRPC counterpart of {@code RequestHeaderPreferenceSource}, at the same order: it answers
 * from the current gRPC context where that one answers from the current servlet request, and each
 * is silent outside its own transport. Nothing here holds a locale and a zone together - the pair
 * is still {@code UserPreferences} and is still resolved in one place.
 */
public final class GrpcCallPreferenceSource implements UserPreferenceSource {

    @Override
    public Optional<Locale> locale() {
        return Optional.ofNullable(GrpcCallContext.REQUESTED_LOCALE.get());
    }

    @Override
    public Optional<ZoneId> zone() {
        return Optional.ofNullable(GrpcCallContext.REQUESTED_ZONE.get());
    }

    @Override
    public int getOrder() {
        return REQUEST_ORDER;
    }
}
