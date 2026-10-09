--liquibase formatted sql

-- Role codes exactly as the directory names them; normalization to the ROLE_ prefix happens on
-- read, so a change of convention needs no data migration.

--changeset ludwig-identity:identity-0002-create-security-user-role dbms:postgresql
--comment Role codes as the directory names them; ROLE_ normalization happens on read.
CREATE TABLE security_user_role (
    user_id   VARCHAR(255) NOT NULL,
    role_code VARCHAR(128) NOT NULL,
    CONSTRAINT pk_security_user_role PRIMARY KEY (user_id, role_code),
    CONSTRAINT fk_security_user_role_user FOREIGN KEY (user_id)
        REFERENCES security_user (id) ON DELETE CASCADE
);

CREATE INDEX idx_security_user_role_code ON security_user_role (role_code);
--rollback DROP TABLE security_user_role
