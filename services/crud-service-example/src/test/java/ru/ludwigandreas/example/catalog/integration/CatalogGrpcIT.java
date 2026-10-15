package ru.ludwigandreas.example.catalog.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.stub.MetadataUtils;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import ru.ludwigandreas.catalog.grpc.v1.CatalogServiceGrpc;
import ru.ludwigandreas.catalog.grpc.v1.GetProductRequest;
import ru.ludwigandreas.catalog.grpc.v1.GetProductsRequest;
import ru.ludwigandreas.catalog.grpc.v1.Product;
import ru.ludwigandreas.example.catalog.repository.ProductRepository;
import ru.ludwigandreas.example.catalog.web.dto.CreateProductRequest;
import ru.ludwigandreas.example.catalog.web.dto.ProductResponse;
import ru.ludwigandreas.example.catalog.web.dto.ProductStatusDto;
import ru.ludwigandreas.grpc.core.problem.GrpcStatusCodec;
import ru.ludwigandreas.grpc.server.properties.GrpcServerProperties;
import ru.ludwigandreas.testsupport.container.PostgresContainerConfiguration;
import ru.ludwigandreas.testsupport.security.TestPrincipalBuilder;

/**
 * The catalog over gRPC, end to end: the same product the REST endpoint returns, the same 404 as a
 * {@code NOT_FOUND}, and the same role check - on the service both transports call.
 */
@AutoConfigureMockMvc
@SpringBootTest
@Import({TestSecurityConfiguration.class, PostgresContainerConfiguration.class, CatalogGrpcIT.Tokens.class})
@TestPropertySource(properties = {
        "ludwig.outbox.polling.enabled=false",
        "ludwig.identity.kafka.enabled=false"
})
class CatalogGrpcIT {

    private static final UUID TOOLS = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private GrpcServerProperties grpcServer;

    private ManagedChannel channel;

    @BeforeEach
    void open() {
        productRepository.deleteAll();
        channel = InProcessChannelBuilder.forName(grpcServer.getInProcessName()).build();
    }

    @AfterEach
    void close() {
        channel.shutdownNow();
    }

    private CatalogServiceGrpc.CatalogServiceBlockingStub as(String subject, String role) {
        Metadata headers = new Metadata();
        headers.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER),
                "Bearer valid-" + subject + "-" + role);
        return CatalogServiceGrpc.newBlockingStub(channel)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
    }

    private ProductResponse create(String sku) throws Exception {
        CreateProductRequest request = new CreateProductRequest(sku, "Hammer", null, new BigDecimal("19.99"),
                new BigDecimal("7.50"), ProductStatusDto.ACTIVE, 10, TOOLS);
        String body = mockMvc.perform(post("/api/v1/products").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readValue(body, ProductResponse.class);
    }

    @Test
    @DisplayName("an authenticated caller gets the values the REST endpoint returns for the same product")
    void sameProductAsRest() throws Exception {
        ProductResponse created = create("GRPC-1");
        ProductResponse rest = objectMapper.readValue(mockMvc.perform(get("/api/v1/products/{id}", created.id()))
                .andReturn().getResponse().getContentAsString(), ProductResponse.class);

        Product grpc = as("reader", "ROLE_CATALOG_READER")
                .getProduct(GetProductRequest.newBuilder().setId(created.id().toString()).build());

        assertThat(grpc.getId()).isEqualTo(rest.id().toString());
        assertThat(grpc.getSku()).isEqualTo(rest.sku());
        assertThat(grpc.getName()).isEqualTo(rest.name());
        assertThat(grpc.hasDescription()).isEqualTo(rest.description() != null);
        assertThat(new BigDecimal(grpc.getPrice())).isEqualByComparingTo(rest.price());
        assertThat(grpc.getState().name()).isEqualTo("PRODUCT_STATE_" + rest.status().name());
        assertThat(grpc.getStockQuantity()).isEqualTo(rest.stockQuantity());
        assertThat(grpc.getCategory().getCode()).isEqualTo(rest.category().code());
        assertThat(grpc.getVersion()).isEqualTo(rest.version());
        assertThat(grpc.getCreatedAt().getSeconds()).isEqualTo(rest.createdAt().getEpochSecond());
    }

    @Test
    @DisplayName("a batch lookup returns the products in the order asked for")
    void batch() throws Exception {
        String first = create("GRPC-A").id().toString();
        String second = create("GRPC-B").id().toString();

        assertThat(as("reader", "ROLE_CATALOG_READER")
                .getProducts(GetProductsRequest.newBuilder().addIds(second).addIds(first).build())
                .getProductsList()).extracting(Product::getId).containsExactly(second, first);
    }

    @Test
    @DisplayName("a product that does not exist is NOT_FOUND with the product-not-found code")
    void notFound() {
        StatusRuntimeException failure = catchThrowableOfType(() -> as("reader", "ROLE_CATALOG_READER")
                .getProduct(GetProductRequest.newBuilder().setId(UUID.randomUUID().toString()).build()),
                StatusRuntimeException.class);

        assertThat(failure.getStatus().getCode()).isEqualTo(Status.Code.NOT_FOUND);
        assertThat(new GrpcStatusCodec("test").decode(failure).orElseThrow().problemCode())
                .contains("error.product.not-found");
    }

    @Test
    @DisplayName("an id that is not a UUID is INVALID_ARGUMENT")
    void invalidId() {
        StatusRuntimeException failure = catchThrowableOfType(() -> as("reader", "ROLE_CATALOG_READER")
                .getProduct(GetProductRequest.newBuilder().setId("not-a-uuid").build()), StatusRuntimeException.class);

        assertThat(failure.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
    }

    @Test
    @DisplayName("a caller with no catalog role is PERMISSION_DENIED, as REST answers 403")
    void noCatalogRole() throws Exception {
        String id = create("GRPC-2").id().toString();

        StatusRuntimeException failure = catchThrowableOfType(() -> as("stranger", "ROLE_SOMETHING_ELSE")
                .getProduct(GetProductRequest.newBuilder().setId(id).build()), StatusRuntimeException.class);

        assertThat(failure.getStatus().getCode()).isEqualTo(Status.Code.PERMISSION_DENIED);
    }

    @Test
    @DisplayName("a call with no token is UNAUTHENTICATED")
    void anonymous() {
        StatusRuntimeException failure = catchThrowableOfType(() -> CatalogServiceGrpc.newBlockingStub(channel)
                .getProduct(GetProductRequest.newBuilder().setId(UUID.randomUUID().toString()).build()),
                StatusRuntimeException.class);

        assertThat(failure.getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
    }

    /**
     * Tokens for this test: {@code valid-<subject>-<role>} is that subject holding that role. Primary,
     * so the gRPC server uses them; MockMvc requests authenticate as before, without a token.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class Tokens {

        private static final String PREFIX = "valid-";

        @Bean
        @Primary
        JwtDecoder grpcTestJwtDecoder() {
            return token -> {
                if (!token.startsWith(PREFIX)) {
                    throw new BadJwtException("not a test token");
                }
                String[] parts = token.substring(PREFIX.length()).split("-", 2);
                // The audience this service requires (ludwig.security.jwt.audiences): the security
                // starter wraps every JwtDecoder with that check, this one included.
                return Jwt.withTokenValue(token).header("alg", "none").subject(parts[0])
                        .audience(java.util.List.of("product-catalog"))
                        .claim("test-role", parts[1]).build();
            };
        }

        @Bean
        @Primary
        Converter<Jwt, AbstractAuthenticationToken> grpcTestJwtConverter() {
            return new RoleClaimConverter();
        }
    }

    /**
     * A class, not a lambda: Spring MVC registers every {@code Converter} bean in its conversion
     * service and needs the generic types, which a lambda erases.
     */
    static final class RoleClaimConverter implements Converter<Jwt, AbstractAuthenticationToken> {

        @Override
        public AbstractAuthenticationToken convert(Jwt jwt) {
            return TestPrincipalBuilder.user(jwt.getSubject()).roles(jwt.getClaimAsString("test-role"))
                    .authentication();
        }
    }
}
