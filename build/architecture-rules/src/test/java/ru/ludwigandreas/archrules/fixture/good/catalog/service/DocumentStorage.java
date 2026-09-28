package ru.ludwigandreas.archrules.fixture.good.catalog.service;

/** Outbound port; the S3 adapter implements it, so the service never sees the SDK. */
public interface DocumentStorage {

    byte[] read(String key);
}
