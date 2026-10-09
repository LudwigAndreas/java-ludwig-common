--liquibase formatted sql

-- Subjects named on a product. This is the membership axis of data-level access - "everything I am
-- named on" - which a column on `product` cannot express, because a product has many watchers and a
-- row has one value per column.

--changeset ludwig-catalog:catalog-0007-product-watcher dbms:postgresql
--comment The membership axis of data-level access: subjects named on a product.
CREATE TABLE product_watcher (
    product_id      UUID         NOT NULL,
    watcher_subject VARCHAR(255) NOT NULL,
    CONSTRAINT pk_product_watcher PRIMARY KEY (product_id, watcher_subject),
    CONSTRAINT fk_product_watcher_product FOREIGN KEY (product_id)
        REFERENCES product (id) ON DELETE CASCADE
);
--rollback DROP TABLE product_watcher
