package ru.ludwigandreas.example.catalog.client;

import java.util.Collection;
import java.util.List;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;
import ru.ludwigandreas.example.catalog.client.dto.SupplierSummary;
import ru.ludwigandreas.restclient.annotation.LudwigRestClient;

/**
 * The supplier directory, as this service reads it.
 *
 * <h2>Why a batch endpoint, and why the report cares</h2>
 *
 * <p>This interface exists to be the partner half of a report's enrichment stage, and the shape of
 * the method is the whole reason the stage is affordable. The catalogue export declares its stage as
 * an {@code Enricher.Batched}, which means the engine collects the distinct supplier ids in a window
 * of rows and asks for them in one call. Reading a million products with eight hundred suppliers
 * between them costs a few hundred calls this way, and a million calls if this method took one id.
 *
 * <p>Like every other client here, the interface carries the HTTP shape and nothing else: the base
 * URL, the pool, the timeouts, the retry and the circuit breaker are properties of the
 * {@code suppliers} client under {@code ludwig.rest-client.clients}. The export module refuses at
 * startup to run a stage whose declared concurrency exceeds that client's per-route pool, so the two
 * numbers cannot drift apart without the application failing to start.
 *
 * <p>A {@code GET} with repeated query parameters rather than a {@code POST} with a body, because it
 * is a read: it is cacheable by anything on the path, it is safe to retry without an idempotency key,
 * and it shows up in an access log as what it is. The cost is a ceiling on the batch size, which is
 * why the stage declares one rather than inheriting the module default.
 */
@LudwigRestClient("suppliers")
public interface SupplierDirectoryApi {

    /**
     * Looks up several suppliers at once.
     *
     * <p>An id the directory does not know is simply absent from the answer. That is not an error and
     * the caller must not treat it as one - the export module has a first-class policy for a key a
     * partner does not resolve, and a client that threw here would deny the module the chance to
     * apply it.
     *
     * @param ids the supplier ids, at most as many as the calling stage's declared batch size
     * @return the suppliers that exist, in no guaranteed order and possibly fewer than were asked for
     */
    @GetExchange("/api/v1/suppliers")
    List<SupplierSummary> byIds(@RequestParam("id") Collection<String> ids);
}
