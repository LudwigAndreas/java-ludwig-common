--liquibase formatted sql

-- Registry of external organizations allowed to call with a client certificate. `code` is the stable
-- business id used in grants and in row-level partner_id columns; the certificate identifiers change
-- at every renewal and the code never does.

--changeset ludwig-identity:identity-0003-create-security-partner dbms:postgresql
--comment Registry of partners recognised by client certificate; `code` is the stable business id.
CREATE TABLE security_partner (
    id               UUID                     NOT NULL,
    version          BIGINT                   NOT NULL DEFAULT 0,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by       VARCHAR(255),
    updated_by       VARCHAR(255),
    code             VARCHAR(128)             NOT NULL,
    display_name     VARCHAR(255)             NOT NULL,
    spiffe_id        VARCHAR(512),
    dns_san          VARCHAR(255),
    subject_dn       VARCHAR(512),
    certificate_hash VARCHAR(128),
    status           VARCHAR(32)              NOT NULL DEFAULT 'ACTIVE',
    CONSTRAINT pk_security_partner PRIMARY KEY (id),
    CONSTRAINT uq_security_partner_code UNIQUE (code)
);

-- Certificate identifiers are unique across partners: two partners recognized by the same SPIFFE id
-- would make which one a request is attributed to depend on row order.
ALTER TABLE security_partner
    ADD CONSTRAINT uq_security_partner_spiffe_id UNIQUE (spiffe_id);

CREATE INDEX idx_security_partner_dns_san ON security_partner (dns_san);
CREATE INDEX idx_security_partner_subject_dn ON security_partner (subject_dn);
--rollback DROP TABLE security_partner
