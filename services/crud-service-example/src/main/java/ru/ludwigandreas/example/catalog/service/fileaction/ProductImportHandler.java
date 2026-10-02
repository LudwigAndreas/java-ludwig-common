package ru.ludwigandreas.example.catalog.service.fileaction;

import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.ludwigandreas.example.catalog.repository.CategoryRepository;
import ru.ludwigandreas.example.catalog.repository.ProductRepository;
import ru.ludwigandreas.example.catalog.repository.entity.CategoryEntity;
import ru.ludwigandreas.example.catalog.service.ProductService;
import ru.ludwigandreas.example.catalog.service.model.NewProduct;
import ru.ludwigandreas.example.catalog.service.model.ProductState;
import ru.ludwigandreas.fileaction.api.FileAction;
import ru.ludwigandreas.fileaction.api.FileActionContext;
import ru.ludwigandreas.fileaction.api.RowBinding;
import ru.ludwigandreas.fileaction.api.RowHandler;
import ru.ludwigandreas.fileaction.api.RowOutcome;

/**
 * The reference file action: a product spreadsheet becomes products in the catalogue.
 *
 * <h2>What this demonstrates, and why it is worth a reference</h2>
 *
 * <p>A starter that claims "out of the box" with no working consumer is a starter whose first real consumer
 * discovers the gaps. This action is the same arrangement every real one has: a typed binding, a
 * {@link RowHandler}, and a block of YAML - and no controller, no multipart handling, no error-report code and no
 * polling endpoint, because those belong to the module.
 *
 * <p>It writes through {@link ProductService}, the service's own domain entry point, rather than into a table of
 * its own. That is the point of the shape: an import is a different <em>way in</em> to an action the service
 * already performs, not a second implementation of it. There is consequently no new Liquibase changeset for this
 * action - the products it creates go in {@code catalog_product}, which already exists - and inventing a table to
 * have one would be worse.
 *
 * <h2>Why the handler is idempotent per row, deliberately</h2>
 *
 * <p>{@code RowHandler.apply}'s javadoc states the rule that nothing can check: a deferred submission is claimed
 * under a lease, so if the pod dies after a batch committed but before progress was recorded, another instance
 * re-applies that batch. This handler is safe under that because it looks the SKU up first and skips a row whose
 * product already exists - so applying a row twice applies it once. A handler that called
 * {@code create} unconditionally would create duplicates on the day a node is drained, and the build would be
 * green.
 *
 * <p>The SKU lookup is also what makes the second apply a <em>skip</em> rather than a reject: a row whose product
 * is already there is not a mistake the user has to fix, and counting it as a reject would push a re-applied
 * submission over its reject threshold.
 */
@FileAction("product-import")
public class ProductImportHandler implements RowHandler<ProductImportRow> {

    /**
     * The binding, as a constant.
     *
     * <p>Aliases on every column that a user might reasonably head differently, including the Russian headings -
     * because the people filling these sheets in are the ones whose language the service already speaks. A heading
     * match is case- and whitespace-insensitive, so only genuinely different words need an alias.
     */
    private static final RowBinding<ProductImportRow> BINDING = RowBinding.of(ProductImportRow.class)
            .sheet("Products")
            .column("sku", "SKU").aliases("Article", "Code")
            .column("name", "Name").aliases("Product", "Title")
            .column("description", "Description").aliases("Notes").optional()
            .column("price", "Price")
            .column("stock", "Stock").aliases("Quantity", "Qty")
            .column("category", "Category").aliases("Group")
            .build();

    private final ProductService products;
    private final ProductRepository productRepository;
    private final CategoryRepository categories;

    /**
     * Wires the handler.
     *
     * @param products          the domain service this import is a second way into
     * @param productRepository used to recognise a SKU that already exists, which is what makes a re-applied row
     *                          a skip rather than a duplicate
     * @param categories        used to resolve a category name to an id
     */
    public ProductImportHandler(ProductService products, ProductRepository productRepository,
                                CategoryRepository categories) {
        this.products = products;
        this.productRepository = productRepository;
        this.categories = categories;
    }

    @Override
    public RowBinding<ProductImportRow> binding() {
        return BINDING;
    }

    /**
     * Applies one row.
     *
     * <p>Joins the transaction the engine opened for the batch - {@code MANDATORY} rather than {@code REQUIRED},
     * so that a handler accidentally invoked outside one fails loudly instead of silently committing per row and
     * making the configured {@code commit-policy} a fiction.
     *
     * @param row     the bound row
     * @param context what is known about the submission
     * @return what happened to the row
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public RowOutcome apply(ProductImportRow row, FileActionContext context) {
        if (!seenInThisSubmission(context).add(normalised(row.sku()))) {
            // The same SKU twice in one file. A reject rather than a skip: the user has two rows claiming to be
            // the same product and only they know which one is right.
            return RowOutcome.rejected(ProductImportMessages.DUPLICATE_IN_FILE, row.sku());
        }
        if (productRepository.lookupBySku(row.sku()).isPresent()) {
            // Already there. A skip, not a reject - see the class javadoc: this is what makes the handler safe to
            // re-apply after a lease lapses, and a re-applied submission must not trip its reject threshold.
            return RowOutcome.skipped(ProductImportMessages.SKU_EXISTS, row.sku());
        }
        UUID categoryId = categoryIdFor(row.category());
        if (categoryId == null) {
            return RowOutcome.rejected(ProductImportMessages.UNKNOWN_CATEGORY, row.category());
        }
        products.create(new NewProduct(row.sku(), row.name(), row.description(), row.price(), null,
                ProductState.DRAFT, row.stock() == null ? 0 : row.stock(), categoryId));
        return RowOutcome.applied();
    }

    /**
     * The SKUs this submission has already seen.
     *
     * <p>Keyed by submission, so two uploads running at once on the same instance cannot see each other's rows -
     * which they would if this were one set. Removed when the submission reaches a terminal state would be tidier;
     * it is bounded instead by the action's {@code max-rows}, and a map of a few thousand short strings per
     * in-flight submission is a cost worth naming rather than engineering around.
     */
    private final Map<UUID, Set<String>> seenSkus = new ConcurrentHashMap<>();

    private Set<String> seenInThisSubmission(FileActionContext context) {
        return seenSkus.computeIfAbsent(context.submissionId(),
                submission -> java.util.Collections.synchronizedSet(new HashSet<>()));
    }

    /**
     * Resolves a category name to its id.
     *
     * <p>Case-insensitively, in {@link Locale#ROOT}: a category name is being matched as an identifier here rather
     * than displayed, and lower-casing an identifier in the Turkish locale stops it matching. The same reasoning
     * the module applies to column headings.
     *
     * @param name the name from the spreadsheet
     * @return the id, or null when no category has that name
     */
    private UUID categoryIdFor(String name) {
        String wanted = normalised(name);
        return categories.findAll().stream()
                .collect(Collectors.toMap(category -> normalised(category.getName()),
                        CategoryEntity::getId, (first, second) -> first))
                .get(wanted);
    }

    private static String normalised(String value) {
        return value == null ? null : value.strip().toLowerCase(Locale.ROOT);
    }
}
