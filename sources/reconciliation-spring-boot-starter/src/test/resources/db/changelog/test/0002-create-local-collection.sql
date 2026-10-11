--liquibase formatted sql

--changeset ludwig-reconciliation-test:reconciliation-test-0002-create-local-collection dbms:postgresql
--comment The test application's scope and member tables, standing in for a consuming service's schema.
CREATE TABLE local_scope (
    id   UUID         NOT NULL,
    code VARCHAR(255) NOT NULL,
    CONSTRAINT pk_local_scope PRIMARY KEY (id),
    CONSTRAINT uq_local_scope_code UNIQUE (code)
);

CREATE TABLE local_item (
    id          UUID         NOT NULL,
    external_id VARCHAR(255) NOT NULL,
    kind        VARCHAR(20)  NOT NULL,
    parent      VARCHAR(255),
    title       VARCHAR(255),
    removed     BOOLEAN      NOT NULL DEFAULT false,
    sync_count  INT          NOT NULL DEFAULT 0,
    CONSTRAINT pk_local_item PRIMARY KEY (id),
    CONSTRAINT uq_local_item_external_id UNIQUE (external_id)
);
--rollback DROP TABLE local_item; DROP TABLE local_scope
