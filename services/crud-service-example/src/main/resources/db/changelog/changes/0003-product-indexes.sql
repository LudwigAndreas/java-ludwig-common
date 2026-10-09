--liquibase formatted sql

-- SKU uniqueness is enforced case-insensitively because that is how the application looks products up
-- (ProductQueryRepositoryImpl#lookupBySku). A plain unique constraint would let 'ABC-1' and 'abc-1'
-- both exist while the lookup treats them as the same product.

--changeset ludwig-catalog:catalog-0003-product-indexes dbms:postgresql
--comment Case-insensitive SKU uniqueness, plus the foreign-key, status and default-order indexes.
CREATE UNIQUE INDEX uk_product_sku_lower ON product (lower(sku));

CREATE INDEX ix_product_category_id ON product (category_id);
CREATE INDEX ix_product_status ON product (status);
-- Backs the default ordering of the search endpoint (created_at desc).
CREATE INDEX ix_product_created_at ON product (created_at DESC);
--rollback DROP INDEX uk_product_sku_lower;
--rollback DROP INDEX ix_product_category_id;
--rollback DROP INDEX ix_product_status;
--rollback DROP INDEX ix_product_created_at;
