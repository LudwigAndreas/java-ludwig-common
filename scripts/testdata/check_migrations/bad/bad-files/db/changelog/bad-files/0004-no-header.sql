--changeset ludwig-bad-files:bad-files-0004-no-header dbms:postgresql
CREATE TABLE d (id UUID NOT NULL);
--rollback DROP TABLE d
