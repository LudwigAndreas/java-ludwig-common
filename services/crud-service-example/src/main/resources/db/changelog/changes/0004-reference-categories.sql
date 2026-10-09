--liquibase formatted sql

-- Categories are reference data, not user data: this service exposes no category CRUD, so the rows are
-- owned by the migration. Fixed ids keep them stable across environments and make them usable straight
-- from the README's examples.

--changeset ludwig-catalog:catalog-0004-reference-categories dbms:postgresql
--comment The three seeded categories, with fixed ids so the README's examples work anywhere.
INSERT INTO product_category (id, code, name, version, created_at, updated_at, created_by, updated_by)
VALUES
    ('11111111-1111-1111-1111-111111111111', 'TOOLS', 'Tools', 0, now(), now(), 'system', 'system'),
    ('22222222-2222-2222-2222-222222222222', 'ELECTRONICS', 'Electronics', 0, now(), now(), 'system', 'system'),
    ('33333333-3333-3333-3333-333333333333', 'GARDEN', 'Garden', 0, now(), now(), 'system', 'system');
--rollback DELETE FROM product_category WHERE code IN ('TOOLS', 'ELECTRONICS', 'GARDEN')
