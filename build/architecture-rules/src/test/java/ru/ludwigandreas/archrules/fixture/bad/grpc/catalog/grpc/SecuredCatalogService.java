package ru.ludwigandreas.archrules.fixture.bad.grpc.catalog.grpc;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import ru.ludwigandreas.grpc.server.LudwigGrpcService;

/**
 * Looks correct and is not: gRPC calls the target object, not the Spring proxy, so neither
 * annotation below is ever applied. The authorization rule is written down and unenforced.
 */
@LudwigGrpcService
@Transactional
public class SecuredCatalogService extends CatalogImplBase {

    @Override
    @PreAuthorize("hasAuthority('catalog:read')")
    public String getProduct(String id) {
        return id;
    }
}
