package ru.ludwigandreas.archrules.fixture.bad.objectstore.catalog.service;

import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.S3Client;

/** Violation: the SDK outside the storage adapters. */
@Service
public class DirectS3Service {

    private final S3Client client;

    public DirectS3Service(S3Client client) {
        this.client = client;
    }

    public byte[] read(String key) {
        return client.getObject("catalog", key);
    }
}
