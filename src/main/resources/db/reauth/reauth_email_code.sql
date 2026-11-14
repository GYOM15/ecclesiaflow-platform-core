-- E-mail codes confirming the sensitive gestures of linked accounts (ecclesiaflow-platform-core,
-- PostgreSQL 12+).
--
-- Not a migration of the library: Flyway never scans db/reauth. Each module that sets
-- ecclesiaflow.security.reauth.email-code.enabled=true copies this file, unchanged, into its own
-- next migration (db/migration/V0xx__<module>__reauth_email_code.sql), in the schema of its
-- primary DataSource.
--
-- Neither a code nor an address is ever stored: code_digest and binding_digest are HMAC-SHA256
-- under the module's key, and subject is the account's Keycloak sub.
--
-- Lifecycle of a challenge (reauth_email_code):
--   PENDING     waiting for its code. One per subject at most (ux_reauth_email_code_pending); a
--               new gesture closes it as SUPERSEDED, and a lapsed one stays PENDING until then.
--   CONSUMED    its code was accepted and committed before the gesture ran. A keyed challenge
--               (the request carried an Idempotency-Key) still passes the same request with the
--               same code until code_expires_at.
--   SUPERSEDED  replaced by another gesture, or presented with another request than its own.
--   VOID        its code could not be sent; code_digest is erased.
--
-- The caps count reauth_email_code_occurrence rows per subject, in sliding windows: SENT for a
-- code handed to the mailer (taken back when the mailer refused it), FAILED for a wrong code.
-- Nothing deletes a FAILED row before the purge, so no success, resend, new gesture or new key
-- resets the ten failures a day.
--
-- Retention: the module's scheduled job calls EmailCodeService.purge(), which deletes the
-- challenges created and the occurrences recorded more than seven days ago.

CREATE TABLE reauth_email_code (
    id                  uuid        PRIMARY KEY,
    subject             text        NOT NULL,
    action              text        NOT NULL,
    gesture             text        NOT NULL,
    binding_digest      text        NOT NULL,
    keyed               boolean     NOT NULL,
    code_digest         text,
    code_failures       integer     NOT NULL DEFAULT 0,
    code_issued_at      timestamptz NOT NULL,
    code_expires_at     timestamptz NOT NULL,
    resend_available_at timestamptz NOT NULL,
    status              text        NOT NULL,
    created_at          timestamptz NOT NULL,
    expires_at          timestamptz NOT NULL,
    closed_at           timestamptz,
    CONSTRAINT ck_reauth_email_code_status CHECK (status IN ('PENDING', 'CONSUMED', 'SUPERSEDED', 'VOID')),
    CONSTRAINT ck_reauth_email_code_failures CHECK (code_failures >= 0),
    CONSTRAINT ck_reauth_email_code_void CHECK (status <> 'VOID' OR code_digest IS NULL),
    CONSTRAINT ck_reauth_email_code_closed_at CHECK ((status = 'PENDING') = (closed_at IS NULL))
);

-- The account's open challenge, and the guarantee that it has one at most.
CREATE UNIQUE INDEX ux_reauth_email_code_pending ON reauth_email_code (subject) WHERE status = 'PENDING';
-- The purge.
CREATE INDEX ix_reauth_email_code_created ON reauth_email_code (created_at);

CREATE TABLE reauth_email_code_occurrence (
    id           bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    subject      text        NOT NULL,
    kind         text        NOT NULL,
    challenge_id uuid        NOT NULL,
    occurred_at  timestamptz NOT NULL,
    CONSTRAINT ck_reauth_email_code_occurrence_kind CHECK (kind IN ('SENT', 'FAILED'))
);

-- The caps' windows.
CREATE INDEX ix_reauth_email_code_occurrence_window
    ON reauth_email_code_occurrence (subject, kind, occurred_at);
-- The purge.
CREATE INDEX ix_reauth_email_code_occurrence_occurred ON reauth_email_code_occurrence (occurred_at);
