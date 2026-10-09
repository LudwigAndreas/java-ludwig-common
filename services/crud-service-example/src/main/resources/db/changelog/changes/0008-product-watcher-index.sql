--liquibase formatted sql

-- The scoped query asks "which products is this subject named on", so the index has to lead with the
-- subject - the primary key leads with product_id and cannot serve it.

--changeset ludwig-catalog:catalog-0008-product-watcher-index dbms:postgresql
--comment Leads with the subject, which the composite primary key cannot serve.
CREATE INDEX idx_product_watcher_subject ON product_watcher (watcher_subject);
--rollback DROP INDEX idx_product_watcher_subject
