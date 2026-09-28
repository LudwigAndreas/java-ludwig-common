package ru.ludwigandreas.example.catalog.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.ludwigandreas.example.catalog.repository.CategoryRepository;
import ru.ludwigandreas.example.catalog.repository.ProductRepository;
import ru.ludwigandreas.example.catalog.repository.entity.CategoryEntity;
import ru.ludwigandreas.example.catalog.repository.entity.ProductEntity;
import ru.ludwigandreas.example.catalog.repository.entity.ProductStatus;
import ru.ludwigandreas.example.catalog.config.CatalogNotificationProperties;
import ru.ludwigandreas.example.catalog.service.ProductServiceImpl;
import ru.ludwigandreas.example.catalog.service.event.ProductEventPayload;
import ru.ludwigandreas.example.catalog.service.event.ProductEventType;
import ru.ludwigandreas.example.catalog.service.exception.CategoryNotFoundException;
import ru.ludwigandreas.example.catalog.service.exception.ProductNotFoundException;
import ru.ludwigandreas.example.catalog.service.exception.ProductSkuAlreadyExistsException;
import ru.ludwigandreas.example.catalog.service.exception.StaleProductVersionException;
import ru.ludwigandreas.example.catalog.service.mapper.ProductEntityMapper;
import ru.ludwigandreas.example.catalog.service.mapper.ProductEntityMapperImpl;
import ru.ludwigandreas.example.catalog.service.model.NewProduct;
import ru.ludwigandreas.example.catalog.service.model.Product;
import ru.ludwigandreas.example.catalog.service.model.ProductState;
import ru.ludwigandreas.example.catalog.service.model.ProductUpdate;
import ru.ludwigandreas.outbox.api.OutboxEvent;
import ru.ludwigandreas.outbox.api.OutboxEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import ru.ludwigandreas.security.data.DataAccessGuard;
import ru.ludwigandreas.security.data.DataAction;

/**
 * Business rules of the service layer, with no database and no Spring context. The MapStruct
 * mapper is the real generated one - mocking it would only assert that mocks return mocks.
 */
@ExtendWith(MockitoExtension.class)
class ProductServiceImplTest {

    private static final UUID CATEGORY_ID = UUID.randomUUID();
    private static final UUID PRODUCT_ID = UUID.randomUUID();

    @Mock
    private ProductRepository productRepository;

    @Mock
    private CategoryRepository categoryRepository;

    @Mock
    private OutboxEventPublisher outboxEventPublisher;

    /**
     * Data-level authorization is exercised in its own module's tests; here it is mocked so these
     * tests stay about business rules - except for the one case below, which asserts the service
     * actually consults it before handing a product back.
     */
    @Mock
    private DataAccessGuard dataAccessGuard;

    /** The MapStruct-generated implementation, exactly as Spring would wire it. */
    @Spy
    private ProductEntityMapper mapper = new ProductEntityMapperImpl();

    /**
     * A real settings object rather than a mock, because what the tests care about is the switch it
     * carries. Disabled here, which is also the shipped default, so the cases above see exactly one
     * outbox row.
     */
    @Spy
    private CatalogNotificationProperties notificationProperties = new CatalogNotificationProperties();

    @InjectMocks
    private ProductServiceImpl service;

    private CategoryEntity category;

    @BeforeEach
    void setUp() {
        category = CategoryEntity.builder().code("TOOLS").name("Tools").build();
        category.setId(CATEGORY_ID);
    }

    @Test
    void createPersistsProductAndRecordsOutboxEventInSameCall() {
        when(productRepository.skuTaken("HAMMER-1", null)).thenReturn(false);
        when(categoryRepository.findById(CATEGORY_ID)).thenReturn(Optional.of(category));
        when(productRepository.saveAndFlush(any(ProductEntity.class))).thenAnswer(invocation -> {
            ProductEntity entity = invocation.getArgument(0);
            entity.setId(PRODUCT_ID);
            return entity;
        });

        Product created = service.create(newProduct("HAMMER-1"));

        assertThat(created.id()).isEqualTo(PRODUCT_ID);
        assertThat(created.sku()).isEqualTo("HAMMER-1");
        assertThat(created.state()).isEqualTo(ProductState.ACTIVE);
        assertThat(created.category().code()).isEqualTo("TOOLS");

        ArgumentCaptor<OutboxEvent> event = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventPublisher).publish(event.capture());
        assertThat(event.getValue().getEventType()).isEqualTo(ProductEventType.CREATED);
        assertThat(event.getValue().getAggregateId()).isEqualTo(PRODUCT_ID.toString());
        // Per-aggregate ordering key: a product's own events stay in order.
        assertThat(event.getValue().getOrderingKey()).isEqualTo(PRODUCT_ID.toString());
        assertThat(event.getValue().getPayload())
                .isInstanceOf(ProductEventPayload.class)
                .extracting("sku", "status", "categoryCode")
                .containsExactly("HAMMER-1", "ACTIVE", "TOOLS");
    }

    /**
     * Two rows, not one, and they are different intentions: a fact published to whoever subscribes,
     * and an instruction addressed to one named peer. They are separate so they can succeed, retry
     * and dead-letter independently - and because a unique index on idempotency_key means they must
     * not share a key.
     */
    @Test
    void createAlsoRequestsAStewardNotificationWhenThatIsEnabled() {
        notificationProperties.setEnabled(true);
        notificationProperties.setStewardUserIds(java.util.List.of("user-7"));
        when(productRepository.skuTaken("HAMMER-1", null)).thenReturn(false);
        when(categoryRepository.findById(CATEGORY_ID)).thenReturn(Optional.of(category));
        when(productRepository.saveAndFlush(any(ProductEntity.class))).thenAnswer(invocation -> {
            ProductEntity entity = invocation.getArgument(0);
            entity.setId(PRODUCT_ID);
            return entity;
        });

        service.create(newProduct("HAMMER-1"));

        ArgumentCaptor<OutboxEvent> events = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventPublisher, times(2)).publish(events.capture());

        OutboxEvent domainEvent = events.getAllValues().get(0);
        OutboxEvent notification = events.getAllValues().get(1);
        assertThat(domainEvent.getEventType()).isEqualTo(ProductEventType.CREATED);
        assertThat(notification.getEventType()).isEqualTo(ProductEventType.NOTIFY_CREATED);
        // Named explicitly, so an unrelated entry added to ludwig.outbox.routes cannot capture it.
        assertThat(notification.getRoute()).isEqualTo("product-notifications");
        assertThat(notification.getIdempotencyKey())
                .isNotEqualTo(domainEvent.getIdempotencyKey());
    }

    @Test
    void createDoesNotRequestANotificationWhenThatIsSwitchedOff() {
        when(productRepository.skuTaken("HAMMER-1", null)).thenReturn(false);
        when(categoryRepository.findById(CATEGORY_ID)).thenReturn(Optional.of(category));
        when(productRepository.saveAndFlush(any(ProductEntity.class))).thenAnswer(invocation -> {
            ProductEntity entity = invocation.getArgument(0);
            entity.setId(PRODUCT_ID);
            return entity;
        });

        service.create(newProduct("HAMMER-1"));

        verify(outboxEventPublisher, times(1)).publish(any(OutboxEvent.class));
    }

    @Test
    void createRejectsDuplicateSkuBeforeTouchingTheDatabase() {
        when(productRepository.skuTaken("HAMMER-1", null)).thenReturn(true);

        assertThatThrownBy(() -> service.create(newProduct("HAMMER-1")))
                .isInstanceOf(ProductSkuAlreadyExistsException.class)
                .extracting("code")
                .isEqualTo("error.product.sku-exists");

        verify(productRepository, never()).saveAndFlush(any());
        verify(outboxEventPublisher, never()).publish(any());
    }

    @Test
    void createRejectsUnknownCategory() {
        when(productRepository.skuTaken("HAMMER-1", null)).thenReturn(false);
        when(categoryRepository.findById(CATEGORY_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(newProduct("HAMMER-1")))
                .isInstanceOf(CategoryNotFoundException.class);

        verify(outboxEventPublisher, never()).publish(any());
    }

    @Test
    void updateRejectsStaleVersionWithoutWriting() {
        ProductEntity stored = storedProduct();
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(stored));

        assertThatThrownBy(() -> service.update(PRODUCT_ID, update(2L)))
                .isInstanceOf(StaleProductVersionException.class);

        verify(productRepository, never()).saveAndFlush(any());
        verify(outboxEventPublisher, never()).publish(any());
        assertThat(stored.getName()).isEqualTo("Hammer");
    }

    /**
     * The row-level half of authorization. A direct load by id never goes through the scoped search
     * query, so without this check any caller who can guess an id would get any product - which is
     * exactly the hole the guard exists to close.
     */
    @Test
    void getRefusesAProductOutsideTheCallersDataScope() {
        ProductEntity stored = storedProduct();
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(stored));
        doThrow(new AccessDeniedException("out of scope"))
                .when(dataAccessGuard).check(eq("product"), eq(DataAction.READ), eq(stored), any());

        assertThatThrownBy(() -> service.get(PRODUCT_ID))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void updateAppliesChangesButNeverTheSku() {
        ProductEntity stored = storedProduct();
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(stored));
        when(categoryRepository.findById(CATEGORY_ID)).thenReturn(Optional.of(category));
        when(productRepository.saveAndFlush(stored)).thenReturn(stored);

        Product updated = service.update(PRODUCT_ID, update(5L));

        assertThat(updated.name()).isEqualTo("Hammer Pro");
        assertThat(updated.price()).isEqualByComparingTo("42.50");
        assertThat(updated.state()).isEqualTo(ProductState.DISCONTINUED);
        // The SKU is the natural key: the update model has no field for it and the mapper ignores it.
        assertThat(updated.sku()).isEqualTo("HAMMER-1");
        verify(outboxEventPublisher).publish(any(OutboxEvent.class));
    }

    @Test
    void getReportsMissingProductAsALocalizedNotFound() {
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(PRODUCT_ID))
                .isInstanceOf(ProductNotFoundException.class)
                .extracting("args")
                .isEqualTo(new Object[] {PRODUCT_ID});
    }

    private NewProduct newProduct(String sku) {
        return new NewProduct(sku, "Hammer", "A hammer", new BigDecimal("19.99"),
                new BigDecimal("9.00"), ProductState.ACTIVE, 10, CATEGORY_ID);
    }

    private ProductUpdate update(long expectedVersion) {
        return new ProductUpdate("Hammer Pro", "A better hammer", new BigDecimal("42.50"),
                new BigDecimal("21.00"), ProductState.DISCONTINUED, 3, CATEGORY_ID, expectedVersion);
    }

    private ProductEntity storedProduct() {
        ProductEntity entity = ProductEntity.builder()
                .sku("HAMMER-1")
                .name("Hammer")
                .description("A hammer")
                .price(new BigDecimal("19.99"))
                .supplierCost(new BigDecimal("9.00"))
                .status(ProductStatus.ACTIVE)
                .stockQuantity(10)
                .category(category)
                .build();
        entity.setId(PRODUCT_ID);
        entity.setVersion(5L);
        return entity;
    }
}
