--liquibase formatted sql

--changeset ludwig-catalog:catalog-0002-product dbms:postgresql
--comment The catalogue's one aggregate root.
CREATE TABLE product (
    id             UUID                     NOT NULL,
    sku            VARCHAR(64)              NOT NULL,
    name           VARCHAR(255)             NOT NULL,
    description    VARCHAR(2000),
    price          NUMERIC(19, 2)           NOT NULL,
    supplier_cost  NUMERIC(19, 2),
    status         VARCHAR(32)              NOT NULL,
    stock_quantity INTEGER                  NOT NULL DEFAULT 0,
    category_id    UUID                     NOT NULL,
    version        BIGINT                   NOT NULL DEFAULT 0,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by     VARCHAR(255),
    updated_by     VARCHAR(255),
    CONSTRAINT pk_product PRIMARY KEY (id),
    CONSTRAINT fk_product_category FOREIGN KEY (category_id)
        REFERENCES product_category (id)
);
--rollback DROP TABLE product
