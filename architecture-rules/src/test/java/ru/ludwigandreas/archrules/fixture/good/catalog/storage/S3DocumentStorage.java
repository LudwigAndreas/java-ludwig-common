package ru.ludwigandreas.archrules.fixture.good.catalog.storage;

import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;

import ru.ludwigandreas.archrules.fixture.good.catalog.service.DocumentStorage;

/** The only place that knows S3 exists. */
@Component
public class S3DocumentStorage implements DocumentStorage {

    private final S3Client client;

    public S3DocumentStorage(S3Client client) {
        this.client = client;
    }

    @Override
    public byte[] read(String key) {
        return client.getObject("catalog", key);
    }
}
