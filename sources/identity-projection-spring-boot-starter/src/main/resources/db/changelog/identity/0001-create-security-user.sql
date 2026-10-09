--liquibase formatted sql

-- Local projection of the OIDC directory. The primary key is the provider's `sub`: it is what every
-- token carries and what every lookup is by, so a surrogate key would only add a join to the
-- hottest query in the service.
--
-- source_timestamp is when the change happened upstream. The projection compares incoming events
-- against this to drop out-of-order replays, so it is functional data, not provenance decoration.

--changeset ludwig-identity:identity-0001-create-security-user dbms:postgresql
--comment Local projection of the OIDC directory, keyed by the provider's sub.
CREATE TABLE security_user (
    id               VARCHAR(255)             NOT NULL,
    display_name     VARCHAR(255),
    tenant_id        VARCHAR(128),
    status           VARCHAR(32)              NOT NULL DEFAULT 'ACTIVE',
    imported_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    source_system    VARCHAR(64)              NOT NULL,
    source_version   VARCHAR(64),
    source_timestamp TIMESTAMP WITH TIME ZONE,
    CONSTRAINT pk_security_user PRIMARY KEY (id)
);

CREATE INDEX idx_security_user_tenant ON security_user (tenant_id);
--rollback DROP TABLE security_user
