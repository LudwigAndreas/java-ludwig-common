--liquibase formatted sql

-- Explicit, optionally time-boxed data-scope grants. These widen what the role policy allows and can
-- never narrow it - narrowing is done by removing roles.
--
-- created_by/created_at are kept because who granted this, and when, is the question an auditor asks
-- first.

--changeset ludwig-identity:identity-0005-create-security-grant dbms:postgresql
--comment Explicit, optionally time-boxed data-scope grants; they widen and never narrow.
CREATE TABLE security_grant (
    id              UUID                     NOT NULL,
    version         BIGINT                   NOT NULL DEFAULT 0,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by      VARCHAR(255),
    updated_by      VARCHAR(255),
    subject         VARCHAR(255)             NOT NULL,
    principal_type  VARCHAR(32)              NOT NULL,
    resource_type   VARCHAR(128)             NOT NULL,
    action          VARCHAR(64)              NOT NULL,
    dimension       VARCHAR(64),
    dimension_value VARCHAR(255),
    expires_at      TIMESTAMP WITH TIME ZONE,
    CONSTRAINT pk_security_grant PRIMARY KEY (id)
);

-- Exactly the lookup DatabaseDataScopeProvider runs, in column order.
CREATE INDEX idx_security_grant_lookup
    ON security_grant (subject, principal_type, resource_type, action);
--rollback DROP TABLE security_grant
