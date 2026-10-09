--liquibase formatted sql

-- Every scoped query filters on one of these, and an unindexed scope column turns "show me my own
-- products" into a sequential scan on every page of every list request.

--changeset ludwig-catalog:catalog-0006-product-scope-indexes dbms:postgresql
--comment Indexes for the two data-scope columns.
CREATE INDEX idx_product_created_by ON product (created_by);
CREATE INDEX idx_product_supplier_partner ON product (supplier_partner_id);
--rollback DROP INDEX idx_product_created_by;
--rollback DROP INDEX idx_product_supplier_partner;
