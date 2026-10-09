--liquibase formatted sql

-- Cluster-wide throughput counters, one row per channel per window.
--
-- An in-process token bucket would be wrong at more than one replica and wrong in the direction nobody
-- notices: three pods each holding a 100-per-minute bucket send 300 per minute, so the number in the
-- configuration file would mean nothing and would change meaning every time the deployment scaled.

--changeset ludwig-notification:notification-0011-rate-limit-window dbms:postgresql
--comment Cluster-wide throughput counters, one row per channel per window.
CREATE TABLE notification_rate_limit_window (
    id           UUID                     NOT NULL,
    channel      VARCHAR(16)              NOT NULL,
    window_start TIMESTAMP WITH TIME ZONE NOT NULL,
    permits_used INTEGER                  NOT NULL DEFAULT 0,
    CONSTRAINT pk_notification_rate_limit_window PRIMARY KEY (id)
);

-- The conflict target of the reservation upsert, and what makes one window one row.
ALTER TABLE notification_rate_limit_window
    ADD CONSTRAINT uk_notification_rate_limit_window UNIQUE (channel, window_start);
--rollback DROP TABLE notification_rate_limit_window
