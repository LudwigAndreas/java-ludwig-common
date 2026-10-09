--liquibase formatted sql

--changeset ludwig-reconciliation-test:reconciliation-test-0001-create-local-order dbms:postgresql
--comment The test application's own table, standing in for a consuming service's schema.
CREATE TABLE local_order (
    id               UUID                     NOT NULL,
    external_id      VARCHAR(255)             NOT NULL,
    status           VARCHAR(50)              NOT NULL DEFAULT 'PENDING',
    source_timestamp TIMESTAMP WITH TIME ZONE,
    sync_count       INT                      NOT NULL DEFAULT 0,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    created_by       VARCHAR(255),
    updated_by       VARCHAR(255),
    version          BIGINT                   NOT NULL DEFAULT 0,
    CONSTRAINT pk_local_order PRIMARY KEY (id),
    CONSTRAINT uq_local_order_external_id UNIQUE (external_id)
);
--rollback DROP TABLE local_order
