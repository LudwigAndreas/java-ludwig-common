package ru.ludwigandreas.example.catalog.unit.fileaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.example.catalog.repository.CategoryRepository;
import ru.ludwigandreas.example.catalog.repository.ProductRepository;
import ru.ludwigandreas.example.catalog.repository.entity.CategoryEntity;
import ru.ludwigandreas.example.catalog.repository.entity.ProductEntity;
import ru.ludwigandreas.example.catalog.service.ProductService;
import ru.ludwigandreas.example.catalog.service.fileaction.ProductImportHandler;
import ru.ludwigandreas.example.catalog.service.fileaction.ProductImportMessages;
import ru.ludwigandreas.example.catalog.service.fileaction.ProductImportRow;
import ru.ludwigandreas.fileaction.api.FileActionContext;
import ru.ludwigandreas.fileaction.api.RowOutcome;
import ru.ludwigandreas.webcore.preference.UserPreferences;

/**
 * The reference import handler: what it applies, what it rejects, and what it skips.
 *
 * <p>The distinction between a reject and a skip is the interesting one and the easiest to get wrong. A SKU that
 * already exists is a <em>skip</em>, because that is what makes the handler safe to re-apply after a lease lapses
 * - and because a re-applied submission counting its own rows as rejects would trip its reject threshold. The same
 * SKU twice in one file is a <em>reject</em>, because the user has two rows claiming to be one product and only
 * they know which is right.
 */
class ProductImportActionTest {

    private static final UUID CATEGORY_ID = UUID.fromString("c0000000-0000-0000-0000-000000000001");

    private ProductService products;
    private ProductRepository productRepository;
    private CategoryRepository categories;
    private ProductImportHandler handler;

    @BeforeEach
    void setUp() {
        products = mock(ProductService.class);
        productRepository = mock(ProductRepository.class);
        categories = mock(CategoryRepository.class);
        handler = new ProductImportHandler(products, productRepository, categories);

        CategoryEntity category = new CategoryEntity();
        category.setId(CATEGORY_ID);
        category.setName("Tools");
        when(categories.findAll()).thenReturn(List.of(category));
        when(productRepository.lookupBySku(any())).thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("the binding declares the columns a product sheet has, with the aliases users actually type")
    void theBindingIsDeclared() {
        assertThat(handler.binding().columns())
                .extracting(column -> column.header())
                .containsExactly("SKU", "Name", "Description", "Price", "Stock", "Category");
        assertThat(handler.binding().sheet()).isEqualTo("Products");
        assertThat(handler.binding().columnForHeader("Article"))
                .as("an alias binds to the same column, so a sheet headed Article still imports")
                .isPresent();
        assertThat(handler.binding().columnForHeader("qty")).isPresent();
    }

    @Test
    @DisplayName("only Description is optional, so a sheet missing any other column is refused as a file")
    void onlyDescriptionIsOptional() {
        assertThat(handler.binding().requiredColumns())
                .extracting(column -> column.field())
                .containsExactly("sku", "name", "price", "stock", "category");
    }

    @Test
    @DisplayName("a good row creates a product through the domain service")
    void aGoodRowCreatesAProduct() {
        RowOutcome outcome = handler.apply(row("A-1", "Hammer", "Tools"), context());

        assertThat(outcome.isApplied()).isTrue();
        verify(products).create(any());
    }

    @Test
    @DisplayName("an unknown category is a reject naming the category, not an exception from the domain")
    void anUnknownCategoryIsAReject() {
        RowOutcome outcome = handler.apply(row("A-1", "Hammer", "Nonexistent"), context());

        assertThat(outcome.countsAsReject()).isTrue();
        assertThat(outcome.code()).isEqualTo(ProductImportMessages.UNKNOWN_CATEGORY);
        assertThat(outcome.args()).containsExactly("Nonexistent");
        verify(products, never()).create(any());
    }

    @Test
    @DisplayName("a category name matches case-insensitively, because a heading is matched rather than displayed")
    void theCategoryMatchesCaseInsensitively() {
        RowOutcome outcome = handler.apply(row("A-1", "Hammer", "  tools "), context());

        assertThat(outcome.isApplied()).isTrue();
    }

    @Test
    @DisplayName("an existing SKU is a skip, which is what makes the handler safe to re-apply")
    void anExistingSkuIsASkip() {
        when(productRepository.lookupBySku("A-1")).thenReturn(Optional.of(new ProductEntity()));

        RowOutcome outcome = handler.apply(row("A-1", "Hammer", "Tools"), context());

        assertThat(outcome.isApplied()).isFalse();
        assertThat(outcome.countsAsReject())
                .as("a re-applied submission counting its own rows as rejects would trip its reject threshold")
                .isFalse();
        assertThat(outcome.code()).isEqualTo(ProductImportMessages.SKU_EXISTS);
        verify(products, never()).create(any());
    }

    @Test
    @DisplayName("applying the same row twice applies it once, which is the rule nothing can check")
    void applyingTheSameRowTwiceAppliesItOnce() {
        FileActionContext context = context();
        ProductImportRow row = row("A-1", "Hammer", "Tools");

        handler.apply(row, context);
        // What a lapsed lease looks like: the row is handed over again, and by then the product exists.
        when(productRepository.lookupBySku("A-1")).thenReturn(Optional.of(new ProductEntity()));
        RowOutcome second = handler.apply(row, context);

        verify(products).create(any());
        assertThat(second.isApplied()).isFalse();
    }

    @Test
    @DisplayName("the same SKU twice in one file is a reject, because only the user knows which row is right")
    void aDuplicateInTheFileIsAReject() {
        FileActionContext context = context();
        handler.apply(row("A-1", "Hammer", "Tools"), context);

        RowOutcome second = handler.apply(row("A-1", "Mallet", "Tools"), context);

        assertThat(second.countsAsReject()).isTrue();
        assertThat(second.code()).isEqualTo(ProductImportMessages.DUPLICATE_IN_FILE);
    }

    @Test
    @DisplayName("two submissions running at once do not see each other's SKUs")
    void submissionsAreIsolated() {
        handler.apply(row("A-1", "Hammer", "Tools"), context());

        RowOutcome other = handler.apply(row("A-1", "Hammer", "Tools"), context());

        assertThat(other.isApplied())
                .as("a single shared set would make the second upload of the same sheet a file of duplicates")
                .isTrue();
    }

    @Test
    @DisplayName("every reject code this handler can emit is declared, so the bundle test can enumerate them")
    void everyCodeIsDeclared() {
        assertThat(ProductImportMessages.all())
                .containsExactlyInAnyOrder(ProductImportMessages.UNKNOWN_CATEGORY,
                        ProductImportMessages.SKU_EXISTS, ProductImportMessages.DUPLICATE_IN_FILE);
    }

    private static ProductImportRow row(String sku, String name, String category) {
        return new ProductImportRow(sku, name, "a description", new BigDecimal("19.99"), 5, category);
    }

    /** A fresh context, so each case is its own submission. */
    private static FileActionContext context() {
        return new FileActionContext(UUID.randomUUID(), "product-import", "products.xlsx", "corr-1",
                UserPreferences.FALLBACK, false);
    }
}
