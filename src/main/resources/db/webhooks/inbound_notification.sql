-- Inbox of providers' signed notifications (ecclesiaflow-platform-core, PostgreSQL 12+).
--
-- Not a migration of the library: Flyway never scans db/webhooks. Each module that sets
-- ecclesiaflow.webhooks.inbox.enabled=true copies this file, unchanged, into its own next
-- migration (db/migration/V0xx__<module>__inbound_notification.sql), in the schema of its primary
-- DataSource. Its application role needs SELECT and INSERT on inbound_notification, and SELECT,
-- INSERT and UPDATE (status, attempts, next_attempt_at, outcome, last_error, done_at, parked_at)
-- on inbound_notification_work; nothing more.
--
-- inbound_notification holds a notification as it was received, once per (provider, account,
-- event_id): its identifiers and the SHA-256 of its body, never the body, which can name a payer
-- and could not be erased from an append-only table. Its rows are never changed.
--
-- inbound_notification_work holds what became of it:
--   PENDING    to be handled once next_attempt_at is due. A claim moves next_attempt_at to the end
--              of a lease and later writes are fenced on that value, so two processors never record
--              over each other.
--   DONE       handled; outcome says how.
--   PARKED     max-attempts failures in a row. Never retried until an operator fixes the cause and
--              replays it, through NotificationInbox.replay or replayParked, or by hand:
--
--                UPDATE inbound_notification_work
--                SET status = 'PENDING', attempts = 0, next_attempt_at = now(), outcome = NULL,
--                    last_error = NULL, done_at = NULL, parked_at = NULL
--                WHERE status IN ('PARKED', 'UNMATCHED') AND notification_id = <id>;
--
--   UNMATCHED  about an account the module does not know: recorded and never handled until an
--              operator replays it the same way.
--
-- Handling is at least once: a processor that stops between the handler's return and its own
-- bookkeeping hands the notification over again after the lease. Handlers re-read the provider.

CREATE TABLE inbound_notification (
    id             bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    provider       text        NOT NULL,
    account        text        NOT NULL,
    event_id       text        NOT NULL,
    event_type     text        NOT NULL,
    live           boolean     NOT NULL,
    object_ref     text,
    api_version    text,
    payload_sha256 char(64)    NOT NULL,
    occurred_at    timestamptz NOT NULL,
    received_at    timestamptz NOT NULL,
    CONSTRAINT uq_inbound_notification UNIQUE (provider, account, event_id),
    CONSTRAINT ck_inbound_notification_digest CHECK (payload_sha256 ~ '^[0-9a-f]{64}$')
);

CREATE FUNCTION inbound_notification_refuse_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'inbound_notification is append-only';
END;
$$;

CREATE TRIGGER inbound_notification_append_only
    BEFORE UPDATE OR DELETE ON inbound_notification
    FOR EACH ROW EXECUTE FUNCTION inbound_notification_refuse_change();

CREATE TABLE inbound_notification_work (
    notification_id bigint      NOT NULL PRIMARY KEY REFERENCES inbound_notification (id),
    status          text        NOT NULL,
    attempts        integer     NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL,
    outcome         text,
    last_error      text,
    done_at         timestamptz,
    parked_at       timestamptz,
    CONSTRAINT ck_inbound_notification_work_status CHECK (status IN ('PENDING', 'DONE', 'PARKED', 'UNMATCHED')),
    CONSTRAINT ck_inbound_notification_work_attempts CHECK (attempts >= 0),
    CONSTRAINT ck_inbound_notification_work_done_at CHECK ((status = 'DONE') = (done_at IS NOT NULL)),
    CONSTRAINT ck_inbound_notification_work_parked_at CHECK ((status = 'PARKED') = (parked_at IS NOT NULL))
);

-- The processor's claim, the pending gauges.
CREATE INDEX ix_inbound_notification_work_pending
    ON inbound_notification_work (next_attempt_at, notification_id) WHERE status = 'PENDING';
-- The parked and unmatched gauges, and the operator's list of notifications to replay.
CREATE INDEX ix_inbound_notification_work_set_aside
    ON inbound_notification_work (status) WHERE status IN ('PARKED', 'UNMATCHED');
