package ru.ludwigandreas.restclient.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.AbstractClientHttpRequest;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;

/**
 * Puts {@link SyncExchangePipeline} underneath a {@code RestClient} by being its request factory.
 *
 * <p>The body is buffered, because a retry has to be able to send it again. That is a real
 * constraint and it is stated here rather than discovered: a {@code sync} client cannot stream a
 * request body of unbounded size. In practice the bodies this platform sends are JSON documents of a
 * few kilobytes; a service that has to upload a large file does it with a client configured for no
 * retries, or reaches for the transport directly.
 */
public class PipelineClientHttpRequestFactory implements ClientHttpRequestFactory {

    private final ClientRuntime runtime;
    private final SyncExchangePipeline pipeline;

    public PipelineClientHttpRequestFactory(ClientRuntime runtime, ClientHttpRequestFactory transport,
                                            boolean decodeGzip) {
        this.runtime = runtime;
        this.pipeline = new SyncExchangePipeline(runtime, transport, decodeGzip);
    }

    @Override
    public ClientHttpRequest createRequest(URI uri, HttpMethod httpMethod) {
        return new PipelineRequest(uri, httpMethod);
    }

    /** The name of the client this factory belongs to, for error messages. */
    public String clientName() {
        return runtime.getName();
    }

    private final class PipelineRequest extends AbstractClientHttpRequest {

        private final URI uri;
        private final HttpMethod method;

        /**
         * 1 KiB rather than the default 32: the overwhelming majority of request bodies here are a
         * small JSON document or nothing at all, and a 32 KiB array per request is allocation this
         * path does not need.
         */
        private final ByteArrayOutputStream body = new ByteArrayOutputStream(1024);

        private PipelineRequest(URI uri, HttpMethod method) {
            this.uri = uri;
            this.method = method;
        }

        @Override
        public HttpMethod getMethod() {
            return method;
        }

        @Override
        public URI getURI() {
            return uri;
        }

        @Override
        protected OutputStream getBodyInternal(HttpHeaders headers) {
            return body;
        }

        @Override
        protected ClientHttpResponse executeInternal(HttpHeaders headers) throws IOException {
            return pipeline.execute(method, uri, headers, body.toByteArray());
        }
    }
}
