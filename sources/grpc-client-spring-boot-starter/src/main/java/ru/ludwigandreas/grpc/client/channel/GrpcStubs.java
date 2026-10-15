package ru.ludwigandreas.grpc.client.channel;

import io.grpc.Channel;
import io.grpc.stub.AbstractStub;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Creates a generated stub on a channel, through the generated factory method -
 * {@code newBlockingStub(Channel)} and its siblings on the enclosing {@code <Service>Grpc} class.
 */
public final class GrpcStubs {

    private GrpcStubs() {
    }

    /**
     * Whether a type is a generated stub.
     *
     * @param type a type
     * @return {@code true} for a stub class with a generated factory
     */
    public static boolean isStub(Class<?> type) {
        return AbstractStub.class.isAssignableFrom(type) && factory(type) != null;
    }

    /**
     * A stub bound to a channel.
     *
     * @param type    the stub class
     * @param channel the channel
     * @param <S>     the stub type
     * @return the stub
     */
    public static <S> S create(Class<S> type, Channel channel) {
        Method factory = factory(type);
        if (factory == null) {
            throw new IllegalArgumentException(type.getName() + " is not a generated gRPC stub");
        }
        try {
            return type.cast(factory.invoke(null, channel));
        } catch (ReflectiveOperationException unusable) {
            throw new IllegalStateException("Could not create " + type.getName(), unusable);
        }
    }

    private static Method factory(Class<?> type) {
        Class<?> service = type.getEnclosingClass();
        if (service == null) {
            return null;
        }
        for (Method method : service.getMethods()) {
            if (Modifier.isStatic(method.getModifiers()) && method.getReturnType() == type
                    && method.getParameterCount() == 1 && method.getParameterTypes()[0] == Channel.class) {
                return method;
            }
        }
        return null;
    }
}
