--liquibase formatted sql

-- Column types follow what db-core's AuditedEntity maps to, because the application starts with
-- hibernate.ddl-auto=validate: uuid ids, a bigint version, and timestamptz audit columns (Hibernate 6
-- maps java.time.Instant to TIMESTAMP WITH TIME ZONE).

--changeset ludwig-catalog:catalog-0001-product-category dbms:postgresql
--comment Reference table of product categories.
CREATE TABLE product_category (
    id         UUID                     NOT NULL,
    code       VARCHAR(64)              NOT NULL,
    name       VARCHAR(255)             NOT NULL,
    version    BIGINT                   NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by VARCHAR(255),
    updated_by VARCHAR(255),
    CONSTRAINT pk_product_category PRIMARY KEY (id),
    CONSTRAINT uk_product_category_code UNIQUE (code)
);
--rollback DROP TABLE product_category
