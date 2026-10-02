-- Transactional outbox of domain events (ecclesiaflow-platform-core, PostgreSQL 12+).
--
-- Not a migration of the library: Flyway never scans db/outbox. Each module that sets
-- ecclesiaflow.events.outbox.enabled=true copies this file, unchanged, into its own next
-- migration (db/migration/V0xx__<module>__outbox_event.sql), in the schema of its primary
-- DataSource, the one its business transactions run on.
--
-- Lifecycle of a row:
--   PENDING  written by OutboxPublisher.append in the business transaction; claimed by the relay
--            once next_attempt_at is due. A claim moves next_attempt_at to the end of a lease
--            and later writes are fenced on that value, so two relays never record over each other.
--   SENT     the broker confirmed the message and routed it to a queue. Deleted after
--            ecclesiaflow.events.outbox.sent-retention (7 days by default).
--   PARKED   max-attempts failures at the broker (nack, no confirm, no bound queue). Never retried
--            nor purged: fix the cause, then replay it by hand:
--
--              UPDATE outbox_event
--              SET status = 'PENDING', parked_at = NULL, attempts = 0, next_attempt_at = now()
--              WHERE status = 'PARKED' AND id = <id>;
--
-- Delivery is at least once: a relay that stops between the broker's confirm and its own
-- bookkeeping publishes the row again after the lease. Consumers dedupe by event id.

CREATE TABLE outbox_event (
    id               bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    exchange         text        NOT NULL,
    routing_key      text        NOT NULL,
    payload          bytea       NOT NULL,
    content_type     text,
    content_encoding text,
    message_id       text,
    headers          jsonb       NOT NULL DEFAULT '{}'::jsonb,
    status           text        NOT NULL DEFAULT 'PENDING',
    attempts         integer     NOT NULL DEFAULT 0,
    next_attempt_at  timestamptz NOT NULL,
    last_error       text,
    created_at       timestamptz NOT NULL,
    sent_at          timestamptz,
    parked_at        timestamptz,
    CONSTRAINT ck_outbox_event_status CHECK (status IN ('PENDING', 'SENT', 'PARKED')),
    CONSTRAINT ck_outbox_event_attempts CHECK (attempts >= 0),
    CONSTRAINT ck_outbox_event_sent_at CHECK ((status = 'SENT') = (sent_at IS NOT NULL)),
    CONSTRAINT ck_outbox_event_parked_at CHECK ((status = 'PARKED') = (parked_at IS NOT NULL))
);

-- The relay's claim, the pending gauges.
CREATE INDEX ix_outbox_event_pending ON outbox_event (next_attempt_at, id) WHERE status = 'PENDING';
-- The purge of relayed rows.
CREATE INDEX ix_outbox_event_sent ON outbox_event (sent_at) WHERE status = 'SENT';
-- The parked gauge, and the operator's list of rows to replay.
CREATE INDEX ix_outbox_event_parked ON outbox_event (parked_at) WHERE status = 'PARKED';
