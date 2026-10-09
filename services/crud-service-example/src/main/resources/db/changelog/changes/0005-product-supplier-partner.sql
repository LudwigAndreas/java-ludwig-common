--liquibase formatted sql

-- The columns data-level authorization scopes on. `created_by` already exists (db-core's AuditedEntity
-- writes it), so only the partner column is new.

--changeset ludwig-catalog:catalog-0005-product-supplier-partner dbms:postgresql
--comment Which partner a product belongs to, by stable business code rather than certificate id.
-- Holds the partner's stable business code, not a certificate identifier: certificates are renewed,
-- and a scope column that changed at renewal would silently revoke a partner's access to its own rows.
ALTER TABLE product ADD COLUMN supplier_partner_id VARCHAR(128);
--rollback ALTER TABLE product DROP COLUMN supplier_partner_id
