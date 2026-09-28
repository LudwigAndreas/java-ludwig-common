package ru.ludwigandreas.archrules.fixture.bad.objectstore.catalog.storage;

import software.amazon.awssdk.services.s3.S3Client;

/** Violation of the opt-in rule only: the adapter hands the SDK client out. */
public class S3Adapter {

    private final S3Client client;

    public S3Adapter(S3Client client) {
        this.client = client;
    }

    public S3Client client() {
        return client;
    }
}
