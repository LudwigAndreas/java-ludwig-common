package ru.ludwigandreas.example.catalog.service;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.ludwigandreas.example.catalog.repository.CategoryRepository;
import ru.ludwigandreas.example.catalog.repository.ProductRepository;
import ru.ludwigandreas.example.catalog.repository.entity.CategoryEntity;
import ru.ludwigandreas.example.catalog.repository.entity.ProductEntity;
import ru.ludwigandreas.example.catalog.repository.query.ProductSearchCriteria;
import ru.ludwigandreas.example.catalog.service.event.ProductEventType;
import ru.ludwigandreas.example.catalog.service.exception.CategoryNotFoundException;
import ru.ludwigandreas.example.catalog.service.exception.ProductNotFoundException;
import ru.ludwigandreas.example.catalog.service.exception.ProductSkuAlreadyExistsException;
import ru.ludwigandreas.example.catalog.service.exception.StaleProductVersionException;
import ru.ludwigandreas.example.catalog.service.mapper.ProductEntityMapper;
import ru.ludwigandreas.example.catalog.service.model.NewProduct;
import ru.ludwigandreas.example.catalog.service.model.Product;
import ru.ludwigandreas.example.catalog.service.model.ProductQuery;
import ru.ludwigandreas.example.catalog.service.model.ProductUpdate;
import ru.ludwigandreas.example.catalog.service.notification.StewardNotificationSettings;
import ru.ludwigandreas.outbox.api.OutboxEvent;
import ru.ludwigandreas.outbox.api.OutboxEventPublisher;
import ru.ludwigandreas.security.data.DataAccessGuard;
import ru.ludwigandreas.security.data.DataAction;
import ru.ludwigandreas.security.principal.LudwigPrincipal;
import ru.ludwigandreas.security.principal.PrincipalType;
import ru.ludwigandreas.security.principal.SecurityPrincipals;

/**
 * Transaction boundary of the service. Every write persists the change and records its event in the
 * outbox inside one transaction, so a committed product change always has an event to publish, and
 * an event never escapes for a change that rolled back.
 */
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class ProductServiceImpl implements ProductService {

    private static final String AGGREGATE_TYPE = "Product";

    /** The name this resource's scope mapping and policies are registered under. */
    private static final String RESOURCE_TYPE = "product";

    /** Entry under {@code ludwig.outbox.routes} that carries steward notifications. */
    private static final String NOTIFICATION_ROUTE = "product-notifications";

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final ProductEntityMapper mapper;
    private final OutboxEventPublisher outboxEventPublisher;
    private final DataAccessGuard dataAccessGuard;
    private final StewardNotificationSettings notificationSettings;

    @Override
    public Product create(NewProduct command) {
        if (productRepository.skuTaken(command.sku(), null)) {
            throw new ProductSkuAlreadyExistsException(command.sku());
        }
        ProductEntity entity = mapper.toEntity(command, requireCategory(command.categoryId()));
        stampOwningPartner(entity);
        // Flushed here rather than at commit so the generated id, audit columns and version are
        // populated before the event payload and the response are built from them.
        Product created = mapper.toDomain(productRepository.saveAndFlush(entity));

        publish(created, ProductEventType.CREATED);
        requestStewardNotification(created);
        log.info("Created product {} (sku={})", created.id(), created.sku());
        return created;
    }

    @Override
    @Transactional(readOnly = true)
    public Product get(UUID id) {
        return mapper.toDomain(require(id, DataAction.READ));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<Product> search(ProductQuery query) {
        return productRepository
                .search(new ProductSearchCriteria(query.filter(), query.orderBy(), query.top(), query.skip()))
                .map(mapper::toDomain);
    }

    @Override
    public Product update(UUID id, ProductUpdate command) {
        ProductEntity entity = require(id, DataAction.WRITE);
        if (entity.getVersion() != command.expectedVersion()) {
            // Fail before touching the entity: a stale write is a client-visible conflict, not an
            // infrastructure error surfacing from the flush at commit time.
            throw new StaleProductVersionException(id, command.expectedVersion(), entity.getVersion());
        }

        mapper.apply(command, requireCategory(command.categoryId()), entity);
        Product updated = mapper.toDomain(productRepository.saveAndFlush(entity));

        publish(updated, ProductEventType.UPDATED);
        log.info("Updated product {} to version {}", updated.id(), updated.version());
        return updated;
    }

    @Override
    public void delete(UUID id) {
        ProductEntity entity = require(id, DataAction.DELETE);
        Product deleted = mapper.toDomain(entity);

        productRepository.delete(entity);
        publish(deleted, ProductEventType.DELETED);
        log.info("Deleted product {} (sku={})", deleted.id(), deleted.sku());
    }

    /**
     * {@code findById} is typed by the repository's own generics - unlike a derived query method,
     * nothing about it can fail to resolve at runtime - and the not-found case becomes this
     * service's own localized exception rather than a persistence-layer one.
     *
     * <p>This is the single-object half of data-level authorization, and it is needed precisely because
     * this path does not go through the scoped search query: a direct load by id would otherwise return
     * any product to any caller who can guess an id. The guard is asked <em>after</em> the row is loaded
     * because a scope is a statement about a row's contents, and it audits the denial itself.
     *
     * <p>The read/write/delete distinction is passed in rather than collapsed into one "access" check:
     * a partner reads the whole catalog but may only change its own rows, and a single check could only
     * ever enforce the wider of the two.
     */
    private ProductEntity require(UUID id, String action) {
        ProductEntity entity = productRepository.findById(id)
                .orElseThrow(() -> new ProductNotFoundException(id));
        dataAccessGuard.check(RESOURCE_TYPE, action, entity, ProductEntity::getId);
        return entity;
    }

    /**
     * A product created by a partner belongs to that partner.
     *
     * <p>Taken from the authenticated principal, never from the request body: a partner-supplied
     * {@code supplierPartnerId} would let one partner file products under another's id - and then read
     * and edit them, since that column is exactly what their data scope matches on.
     */
    private void stampOwningPartner(ProductEntity entity) {
        SecurityPrincipals.current()
                .filter(principal -> principal.isType(PrincipalType.PARTNER))
                .map(LudwigPrincipal::subject)
                .ifPresent(entity::setSupplierPartnerId);
    }

    private CategoryEntity requireCategory(UUID id) {
        return categoryRepository.findById(id).orElseThrow(() -> new CategoryNotFoundException(id));
    }

    /**
     * Asks the notification service to tell the catalogue stewards about a new product.
     *
     * <p>A second outbox row rather than a second consumer of the first one, because the two are
     * different intentions with different destinations and different consequences. {@code
     * ProductCreated} is a fact this service publishes to whoever is listening, and losing one
     * subscriber's copy is that subscriber's problem; this is an instruction to one named peer, and
     * it succeeds or dead-letters on its own. One row that meant both would have to share a retry
     * budget and a dead-letter fate between a broker and an HTTP endpoint.
     *
     * <p>The route is named explicitly rather than matched by event type: an explicit route is the
     * first thing the resolver honours, so this row's destination cannot be changed by somebody
     * adding an unrelated entry to {@code ludwig.outbox.routes}.
     */
    private void requestStewardNotification(Product product) {
        if (!notificationSettings.isEnabled()) {
            return;
        }
        outboxEventPublisher.publish(OutboxEvent.builder()
                .aggregateType(AGGREGATE_TYPE)
                .aggregateId(product.id().toString())
                .eventType(ProductEventType.NOTIFY_CREATED)
                .payload(mapper.toPayload(product))
                .route(NOTIFICATION_ROUTE)
                // A distinct suffix from the domain event's key: idempotency_key is unique across the
                // whole table, and the two rows describe the same change for different destinations.
                .idempotencyKey(product.id() + ":" + ProductEventType.NOTIFY_CREATED + ":"
                        + product.version())
                .build());
    }

    private void publish(Product product, String eventType) {
        outboxEventPublisher.publish(OutboxEvent.builder()
                .aggregateType(AGGREGATE_TYPE)
                .aggregateId(product.id().toString())
                .eventType(eventType)
                .payload(mapper.toPayload(product))
                // Keeps a product's own events in order relative to each other without serializing
                // the whole outbox.
                .orderingKey(product.id().toString())
                // A retried request that re-runs this transaction re-uses the row instead of
                // publishing the same state change twice.
                .idempotencyKey(product.id() + ":" + eventType + ":" + product.version())
                .build());
    }
}
