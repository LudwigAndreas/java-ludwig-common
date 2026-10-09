--liquibase formatted sql

--changeset ludwig-identity:identity-0004-create-security-partner-role dbms:postgresql
--comment Role codes granted to a partner rather than to a person.
CREATE TABLE security_partner_role (
    partner_id UUID         NOT NULL,
    role_code  VARCHAR(128) NOT NULL,
    CONSTRAINT pk_security_partner_role PRIMARY KEY (partner_id, role_code),
    CONSTRAINT fk_security_partner_role_partner FOREIGN KEY (partner_id)
        REFERENCES security_partner (id) ON DELETE CASCADE
);
--rollback DROP TABLE security_partner_role
