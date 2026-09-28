package ru.ludwigandreas.jira.unit;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import ru.ludwigandreas.jira.http.JiraRequest;
import ru.ludwigandreas.jira.http.JiraResponse;
import ru.ludwigandreas.jira.http.JiraTransport;

/**
 * A transport that answers from a queue of canned responses and records what it was asked.
 *
 * <p>Lets the client's own behaviour - authentication, retries, error mapping, URI assembly - be tested
 * without a Jira, which is the only way those paths get covered at all: the interesting cases are a 429
 * with a Retry-After and a connection reset mid-write, and neither is reproducible against a real server.
 */
final class RecordingTransport implements JiraTransport {

    private final Deque<Function<JiraRequest, JiraResponse>> answers = new ArrayDeque<>();
    private final List<JiraRequest> requests = new ArrayList<>();
    private boolean closed;

    RecordingTransport respondWith(int status, String body) {
        return respondWith(status, body, Map.of());
    }

    RecordingTransport respondWith(int status, String body, Map<String, List<String>> headers) {
        answers.add(request -> new JiraResponse(status, headers, body.getBytes(StandardCharsets.UTF_8)));
        return this;
    }

    RecordingTransport failWith(RuntimeException failure) {
        answers.add(request -> {
            throw failure;
        });
        return this;
    }

    List<JiraRequest> requests() {
        return List.copyOf(requests);
    }

    JiraRequest lastRequest() {
        return requests.get(requests.size() - 1);
    }

    boolean isClosed() {
        return closed;
    }

    @Override
    public JiraResponse execute(JiraRequest request) {
        requests.add(request);
        if (answers.isEmpty()) {
            throw new IllegalStateException("No canned response left for " + request);
        }
        return answers.removeFirst().apply(request);
    }

    @Override
    public void close() {
        closed = true;
    }
}
