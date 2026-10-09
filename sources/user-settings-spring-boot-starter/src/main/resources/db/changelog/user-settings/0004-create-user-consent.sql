--liquibase formatted sql

-- The consent ledger: append-only, one row per decision. `text_version` is what makes the record worth
-- keeping - "did they accept?" is answerable without it and "what did they accept?" is not. Revocation
-- is a new row with decision = REVOKED, never an update.
--
-- The primary key is assigned by the owner and reused verbatim by every projection, so a redelivered
-- event collides on the key instead of appending a second copy of the same decision.
--
-- imported_at, source_system, source_version and source_timestamp are provenance, from db-core's
-- ExternalEntity. source_system records which deployment witnessed the decision: `self` in owner mode,
-- the upstream topic in a projection.
--
-- occurred_at is when the person decided, as opposed to when this row was written. The two differ in a
-- projection, and this is the one with legal meaning.

--changeset ludwig-user-settings:user-settings-0004-create-user-consent dbms:postgresql
--comment The append-only consent ledger, one row per decision.
CREATE TABLE user_consent (
    id                  UUID                     NOT NULL,
    imported_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    source_system       VARCHAR(64)              NOT NULL,
    source_version      VARCHAR(64),
    source_timestamp    TIMESTAMP WITH TIME ZONE,
    tenant_id           VARCHAR(128)             NOT NULL,
    subject             VARCHAR(255)             NOT NULL,
    consent_key         VARCHAR(128)             NOT NULL,
    text_version        VARCHAR(64)              NOT NULL,
    decision            VARCHAR(16)              NOT NULL,
    locale              VARCHAR(35),
    occurred_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    actor               VARCHAR(255)             NOT NULL,
    evidence_ip         VARCHAR(64),
    evidence_user_agent VARCHAR(512),
    correlation_id      VARCHAR(128),
    CONSTRAINT pk_user_consent PRIMARY KEY (id)
);

CREATE INDEX idx_user_consent_subject
    ON user_consent (tenant_id, subject, consent_key, occurred_at);
CREATE INDEX idx_user_consent_occurred
    ON user_consent (occurred_at);

ALTER TABLE user_consent
    ADD CONSTRAINT ck_user_consent_decision CHECK (decision IN ('GRANTED', 'REVOKED'));
--rollback DROP TABLE user_consent
