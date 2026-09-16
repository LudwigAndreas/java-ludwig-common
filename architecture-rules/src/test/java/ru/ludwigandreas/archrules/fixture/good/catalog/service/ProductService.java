package ru.ludwigandreas.archrules.fixture.good.catalog.service;

import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ru.ludwigandreas.archrules.fixture.good.catalog.domain.ProductCode;
import ru.ludwigandreas.archrules.fixture.good.catalog.repository.ProductRepository;
import ru.ludwigandreas.archrules.fixture.good.catalog.repository.entity.ProductEntity;
import ru.ludwigandreas.archrules.fixture.good.catalog.service.event.ProductCreatedEvent;
import ru.ludwigandreas.archrules.fixture.good.catalog.service.model.Product;

/** The only layer that queries the database, demarcates transactions and publishes events. */
@Service
public class ProductService {

    private final ProductRepository repository;
    private final ProductEventPublisher publisher;
    private final DocumentStorage storage;

    public ProductService(ProductRepository repository, ProductEventPublisher publisher, DocumentStorage storage) {
        this.repository = repository;
        this.publisher = publisher;
        this.storage = storage;
    }

    @Transactional
    public Product create(String code, String name) {
        ProductEntity entity = new ProductEntity();
        entity.setCode(code);
        entity.setName(name);
        repository.save(entity);
        publisher.publish(new ProductCreatedEvent(code, name));
        return new Product(new ProductCode(code), name);
    }

    public Optional<Product> find(String code) {
        return repository.findById(code).map(entity -> new Product(new ProductCode(entity.getCode()),
                entity.getName()));
    }

    public byte[] datasheet(String code) {
        return storage.read(code);
    }
}
