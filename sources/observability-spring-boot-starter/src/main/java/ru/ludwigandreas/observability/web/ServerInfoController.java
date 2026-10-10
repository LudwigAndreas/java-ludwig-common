package ru.ludwigandreas.observability.web;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.ludwigandreas.observability.core.BuildIdentity;
import ru.ludwigandreas.observability.core.ServiceIdentity;

/**
 * Serves {@link ServerInfoResponse} at a path that is the same in every service.
 *
 * <p>The path is a constant and deliberately not a property. A user interface that calls several
 * services needs one path it can append to each of them, and a configurable well-known path stops
 * being well-known; a prefix in front of a service belongs to whatever routes to it. A service that
 * already maps this path switches the endpoint off with
 * {@code ludwig.observability.server.info.enabled=false}.
 *
 * <p>This class decides nothing about access. Whether the path is reachable without credentials is
 * the service's own {@code ludwig.security.public-paths} entry; what makes that a safe entry to add
 * is the response's allow-list, not anything here.
 *
 * <p>Both identities are resolved once at startup and do not change while the process lives, so the
 * response is built once.
 */
@RestController
public class ServerInfoController {

    /** The one path, in every service. */
    public static final String PATH = "/server/info";

    private final ServerInfoResponse response;

    public ServerInfoController(ServiceIdentity serviceIdentity, BuildIdentity buildIdentity) {
        this.response = ServerInfoResponse.of(serviceIdentity, buildIdentity);
    }

    @GetMapping(path = PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    public ServerInfoResponse serverInfo() {
        return response;
    }
}
