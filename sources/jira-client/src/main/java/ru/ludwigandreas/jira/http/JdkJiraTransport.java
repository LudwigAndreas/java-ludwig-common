package ru.ludwigandreas.jira.http;

import java.io.IOException;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import javax.net.ssl.SSLContext;
import ru.ludwigandreas.jira.error.JiraTransportException;

/**
 * The default {@link JiraTransport}, on {@code java.net.http.HttpClient} from the JDK.
 *
 * <p>Chosen over Apache HttpClient or OkHttp so that this module has no HTTP dependency at all: a library
 * that drags a second HTTP stack into a Spring Boot service is a version-conflict waiting to happen, and
 * the JDK client is more than adequate for a REST API with no streaming and no HTTP/2 push.
 *
 * <p>Two configuration choices are worth knowing about.
 *
 * <p><b>HTTP/1.1, not HTTP/2.</b> Jira Server behind the Tomcat it ships with does not negotiate HTTP/2,
 * and the JDK client's default of {@code HTTP_2} makes every connection start with an ALPN negotiation that
 * always falls back. Pinning the version removes a round trip per connection and removes a class of
 * failures seen with older load balancers that mishandle the upgrade.
 *
 * <p><b>Redirects are not followed.</b> Jira answers a request made over plain HTTP to an HTTPS-only
 * instance with a 302, and the JDK client drops the {@code Authorization} header when it follows a redirect
 * that changes scheme or host - so following it silently turns an authenticated call into an anonymous one
 * that comes back 401, or worse, comes back 200 with the anonymous view of the data. Failing on the
 * redirect surfaces the misconfigured base URL instead.
 */
public final class JdkJiraTransport implements JiraTransport {

    private final HttpClient httpClient;
    private final Duration responseTimeout;

    private JdkJiraTransport(HttpClient httpClient, Duration responseTimeout) {
        this.httpClient = httpClient;
        this.responseTimeout = responseTimeout;
    }

    /** A builder for a transport with the defaults described on this class. */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public JiraResponse execute(JiraRequest request) {
        HttpRequest httpRequest = toHttpRequest(request);
        try {
            HttpResponse<byte[]> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofByteArray());
            return new JiraResponse(response.statusCode(), response.headers().map(), response.body());
        } catch (IOException e) {
            throw new JiraTransportException("Call to " + request.method() + " " + request.uri() + " failed", e);
        } catch (InterruptedException e) {
            // Restoring the flag is not optional: swallowing it strands a thread pool that is shutting down.
            Thread.currentThread().interrupt();
            throw new JiraTransportException(
                    "Interrupted while calling " + request.method() + " " + request.uri(), e);
        }
    }

    private HttpRequest toHttpRequest(JiraRequest request) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(request.uri())
                .timeout(request.timeout().orElse(responseTimeout));
        for (Map.Entry<String, String> header : request.headers().entrySet()) {
            builder.header(header.getKey(), header.getValue());
        }
        Optional<RequestBody> body = request.body();
        HttpRequest.BodyPublisher publisher = body
                .map(payload -> HttpRequest.BodyPublishers.ofByteArray(payload.content()))
                .orElseGet(HttpRequest.BodyPublishers::noBody);
        // setHeader, not header: header() appends, so a caller-supplied default Content-Type would
        // produce two of them and Jira would reject the request.
        body.ifPresent(payload -> builder.setHeader("Content-Type", payload.contentType()));
        builder.method(request.method().name(), publisher);
        return builder.build();
    }

    /** Fluent builder for {@link JdkJiraTransport}. */
    public static final class Builder {

        private Duration connectTimeout = Duration.ofSeconds(10);
        private Duration responseTimeout = Duration.ofSeconds(60);
        private SSLContext sslContext;
        private ProxySelector proxySelector;
        private java.util.concurrent.Executor executor;

        private Builder() {
        }

        /** How long to wait for a TCP connection and TLS handshake. */
        public Builder connectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
            return this;
        }

        /**
         * How long to wait for the complete response once the request is sent. Generous by default: a JQL
         * search over a large instance, or a {@code createmeta} expansion, routinely takes tens of seconds.
         */
        public Builder responseTimeout(Duration responseTimeout) {
            this.responseTimeout = responseTimeout;
            return this;
        }

        /**
         * A custom {@code SSLContext}, which is how an instance behind a corporate CA is trusted: build a
         * context over a truststore holding that CA. There is deliberately no "trust everything" switch -
         * a client that can be told to skip verification eventually is, in production.
         */
        public Builder sslContext(SSLContext sslContext) {
            this.sslContext = sslContext;
            return this;
        }

        /** An explicit proxy. Pass {@code ProxySelector.getDefault()} to honour the JVM's proxy properties. */
        public Builder proxySelector(ProxySelector proxySelector) {
            this.proxySelector = proxySelector;
            return this;
        }

        /** Executor for the client's internal tasks; defaults to the JDK client's own pool. */
        public Builder executor(java.util.concurrent.Executor executor) {
            this.executor = executor;
            return this;
        }

        /** Builds the transport. */
        public JdkJiraTransport build() {
            HttpClient.Builder clientBuilder = HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .connectTimeout(connectTimeout);
            if (sslContext != null) {
                clientBuilder.sslContext(sslContext);
            }
            if (proxySelector != null) {
                clientBuilder.proxy(proxySelector);
            }
            if (executor != null) {
                clientBuilder.executor(executor);
            }
            return new JdkJiraTransport(clientBuilder.build(), responseTimeout);
        }
    }
}
