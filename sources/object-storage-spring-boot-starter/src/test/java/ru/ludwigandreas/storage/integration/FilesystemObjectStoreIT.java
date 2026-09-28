package ru.ludwigandreas.storage.integration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import ru.ludwigandreas.storage.api.ObjectStore;
import ru.ludwigandreas.storage.fs.FilesystemObjectStore;

/**
 * The shared contract, run against a directory.
 *
 * <p>Named {@code *IT} and run by failsafe at {@code verify} alongside the container half, although
 * it needs no container. Keeping the two halves in the same phase is what makes "the contract passes"
 * one statement rather than two: a developer who runs only {@code mvn test} would otherwise see the
 * filesystem half pass and reasonably conclude the contract holds.
 *
 * <p>Locations are given in the {@code s3://} scheme on purpose, exercising the substitution this
 * store exists for - the same configuration a deployment points at a bucket, resolved against a
 * temporary directory.
 */
class FilesystemObjectStoreIT extends ObjectStoreContractTest {

    private static final String BUCKET = "contract-bucket";

    private FilesystemObjectStore store;

    @BeforeEach
    void createStore() throws IOException {
        Path root = Files.createDirectories(work.resolve("store"));
        store = new FilesystemObjectStore(root);
    }

    @Override
    protected ObjectStore store() {
        return store;
    }

    @Override
    protected String uri(String key) {
        return "s3://" + BUCKET + "/" + key;
    }

    /**
     * {@inheritDoc}
     *
     * <p>One, because this store does not page at all: its listing is a lazy walk that produces each
     * entry as the consumer asks for it, so every element is its own page in the only sense that
     * matters to the contract. The contract asserts that this is smaller than the number of objects
     * it writes, which it trivially is.
     */
    @Override
    protected int listPageSize() {
        return 1;
    }
}
