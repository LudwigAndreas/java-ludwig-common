package ru.ludwigandreas.restclient.config;

/**
 * The HTTP transport a named client runs on.
 *
 * <p>Each constant names the artifact it needs and one class from it, so a client configured for an
 * engine that is not on the classpath fails at startup with a message naming the missing dependency
 * instead of a {@code ClassNotFoundException} on the first call.
 */
public enum TransportEngine {

    /**
     * The JDK's own {@code java.net.http.HttpClient}. Default: it is in the platform, it speaks
     * HTTP/2, and it has no dependency to keep in step with anything.
     *
     * <p>Its connection pool is not configurable - the JDK exposes no knobs for size or eviction -
     * so the {@code pool} block is ignored for this engine and the startup validator says so rather
     * than letting an operator believe a pool size took effect.
     */
    HTTP_CLIENT("java.net.http.HttpClient", "the JDK HTTP client (always available on Java 17)"),

    /**
     * Apache HttpClient 5. The engine to choose when the pool matters: max total, max per route,
     * time-to-live, idle eviction and connection validation are all real settings here.
     */
    APACHE("org.apache.hc.client5.http.impl.classic.HttpClients",
            "org.apache.httpcomponents.client5:httpclient5"),

    /**
     * Reactor Netty. Implied by {@code mode: async} and not selectable for a {@code sync} client -
     * a blocking call on a Netty event loop is the one arrangement that turns a slow dependency
     * into a dead service.
     */
    REACTOR_NETTY("reactor.netty.http.client.HttpClient", "io.projectreactor.netty:reactor-netty-http");

    private final String probeClass;
    private final String requiredArtifact;

    TransportEngine(String probeClass, String requiredArtifact) {
        this.probeClass = probeClass;
        this.requiredArtifact = requiredArtifact;
    }

    /** A class from this engine, used only to test whether the engine is on the classpath. */
    public String probeClass() {
        return probeClass;
    }

    /** What an operator has to add to the build to get this engine. */
    public String requiredArtifact() {
        return requiredArtifact;
    }

    /** Whether this engine's classes can be loaded by {@code classLoader}. */
    public boolean isAvailable(ClassLoader classLoader) {
        try {
            Class.forName(probeClass, false, classLoader);
            return true;
        } catch (ClassNotFoundException | LinkageError ex) {
            // LinkageError as well as ClassNotFoundException: a half-present engine - the API jar
            // without its implementation, a Netty version whose native transport is missing - fails
            // here rather than at class-initialization time inside the first request.
            return false;
        }
    }
}
