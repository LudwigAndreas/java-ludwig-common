--liquibase formatted sql

-- The personal access token table. Holds digests, never secrets: the raw secret is returned in the
-- issuance response exactly once and there is no code path that can read it back.
--
-- Column notes:
--
--   owner_subject              The owner's stable subject - the OIDC sub, never a username or an
--                              email. Those get reassigned, and a credential keyed on a reassigned
--                              identifier attributes one person's tokens to another. The same rule
--                              LudwigPrincipal.subject already states, for the same reason.
--   name                       Human-readable label, so an owner with six tokens can tell them apart.
--                              Never authoritative.
--   key_id                     Unique and indexed because that is what makes verification an indexed
--                              point read rather than a scan with a hash per candidate row. It is
--                              secret-adjacent lookup material: not secret itself, but it travels with
--                              the secret and changes on rotation, which is why the claim and the
--                              audit trail name `id` instead.
--   secret_digest              Nullable, and the null has meaning: a revoked or expired token keeps
--                              its row for audit with the digest CLEARED, so the record still says
--                              what that credential could do while no longer being able to
--                              authenticate anything.
--   previous_*                 The rotation overlap: a SECOND key id and digest on the SAME row, not a
--                              second row. A rotation that invalidates the old secret at once requires
--                              every consumer to be updated atomically. Nothing real can do that, so
--                              the observed outcome is that nobody rotates. Keeping both on one row is
--                              what makes the token's identity, scopes and audit history continuous
--                              across a rotation - a second row would be a second token wearing the
--                              first one's name.
--   scopes, audiences          Two dimensions that answer different questions and must not be
--                              collapsed: scope answers WHAT may be done, audience answers WHERE it
--                              may be presented. A CI token for deployments has no business being
--                              presentable at the billing service, whoever owns it - and for an owner
--                              holding broad roles, "bounded by the owner's authority" is not a
--                              meaningful bound. Both are NOT NULL with no default. There is
--                              deliberately no default audience: every available default is either
--                              "every service this token permits" or "everything", and both are the
--                              condition audience binding exists to prevent.
--   allowed_cidrs              Optional CIDR allowlist. Empty means "from anywhere", the common case.
--   created_by                 Who ISSUED the token, which is not always the owner: an administrator
--                              may mint one for a service account, and that distinction is the whole
--                              reason issuing on behalf of another subject needs its own authority.
--   expires_at                 Nullable in the column so that a deployment which has explicitly turned
--                              on non-expiring tokens can store one, but the service refuses a null
--                              unless ludwig.pat.allow-non-expiring is set: a credential with no
--                              expiry outlives every process that knows it exists.
--   last_used_*, use_count     Debounced last-use tracking, written off the critical path and
--                              best-effort. Per-request writes to one hot row are write amplification
--                              on the exchange path and a contention point under load. Losing an
--                              update costs an operator some precision in a dormancy report; failing
--                              an exchange because a telemetry write failed costs a production
--                              pipeline.
--   version                    Optimistic locking, as every db-core entity has. Load-bearing here
--                              rather than incidental: rotation reads a row, mints a new secret and
--                              writes both key ids back, and two concurrent rotations without this
--                              would leave one secret unreachable - minted, handed to a caller, and
--                              not in the row.

--changeset ludwig-pat:pat-0001-create-token dbms:postgresql
--comment The personal access token table. Holds digests, never secrets.
CREATE TABLE ludwig_pat (
    id                         UUID                     NOT NULL,
    owner_subject              VARCHAR(255)             NOT NULL,
    name                       VARCHAR(200)             NOT NULL,
    key_id                     VARCHAR(64)              NOT NULL,
    secret_digest              VARCHAR(64),
    previous_key_id            VARCHAR(64),
    previous_secret_digest     VARCHAR(64),
    previous_secret_expires_at TIMESTAMP WITH TIME ZONE,
    scopes                     TEXT                     NOT NULL,
    audiences                  TEXT                     NOT NULL,
    allowed_cidrs              TEXT,
    created_at                 TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by                 VARCHAR(255),
    updated_at                 TIMESTAMP WITH TIME ZONE,
    updated_by                 VARCHAR(255),
    expires_at                 TIMESTAMP WITH TIME ZONE,
    revoked_at                 TIMESTAMP WITH TIME ZONE,
    revocation_reason          VARCHAR(64),
    last_used_at               TIMESTAMP WITH TIME ZONE,
    last_used_ip               VARCHAR(64),
    use_count                  BIGINT                   NOT NULL DEFAULT 0,
    version                    BIGINT                   NOT NULL DEFAULT 0,
    CONSTRAINT pk_ludwig_pat PRIMARY KEY (id)
);
--rollback DROP TABLE ludwig_pat
