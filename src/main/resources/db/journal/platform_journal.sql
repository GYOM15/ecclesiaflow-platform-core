-- Tamper-evident journal of platform gestures (ecclesiaflow-platform-core, PostgreSQL 14+).
--
-- Not a migration of the library: Flyway never scans db/journal. A module that sets
-- ecclesiaflow.journal.enabled=true copies this file, unchanged, into the next migration of a
-- Flyway line that runs as the journal's owner, a NOLOGIN role that never connects (the way
-- church's finance line runs as finance_ddl). Before that line runs, the ops pre-boot step creates
-- the role and the schema, owned by it:
--
--     CREATE ROLE <module>_journal_owner NOLOGIN;
--     CREATE SCHEMA platform_journal AUTHORIZATION <module>_journal_owner;
--
-- The Flyway placeholder journal_writer names the module's application role. It gets INSERT and
-- SELECT on the lines and EXECUTE on the head's reader and on the purge, nothing else: it cannot
-- rewrite, delete or truncate a line, and holds no right on the head. The module refuses to start
-- when its role is a superuser, a member of the schema's owner, or holds more than that.
--
-- What it holds to:
--   1. One chain per module, not per church: platform gestures cross churches. seq is a line's
--      position, set by the insert trigger under the head's row lock, so concurrent gestures queue
--      for the length of an insert and never fork; seq is also the primary key.
--   2. row_hash = SHA-256(previous row_hash, or 0x00 for the first line || canonical form). The
--      canonical form is frozen: every column but the hashes, in a fixed order, text escaped,
--      instants in epoch microseconds, roles and details pair by pair, so the rendering depends on
--      no session setting and JournalCanonicalForm mirrors it byte for byte. The verifier recomputes
--      in Java, never trusting a function the owner could redefine.
--   3. UPDATE, DELETE and TRUNCATE are refused by triggers on the lines and on the head, whoever
--      runs them. The owner can still drop a trigger, which is why the head is also copied off the
--      server every night.
--   4. The head (last position and hash) is written by the insert trigger alone, one position at a
--      time, onto the line just written. A chain does not prove its own end: a removed tail leaves
--      the head ahead of the lines.
--   5. purge_expired() removes the lines older than the retention, oldest first, and moves the
--      head's start point (position and hash of the last line removed) onto them in the same
--      transaction; the remaining chain verifies from that point.
--
-- A line carries ids and codes only: never a name, an address, a phone number, a content or free
-- text. PlatformJournal refuses any other value before it reaches this table.

DO
$$
BEGIN
    IF NOT EXISTS (SELECT 1
                   FROM pg_namespace n
                   WHERE n.nspname = 'platform_journal'
                     AND n.nspowner = (SELECT r.oid FROM pg_roles r WHERE r.rolname = current_user)) THEN
        -- The pre-boot step creates the schema: CREATE SCHEMA platform_journal AUTHORIZATION <owner>.
        RAISE EXCEPTION 'platform journal: run this file as the owner of the platform_journal schema';
    END IF;
    IF '${journal_writer}' = current_user THEN
        RAISE EXCEPTION 'platform journal: the application role must not own the journal';
    END IF;
END
$$;

REVOKE ALL ON SCHEMA platform_journal FROM PUBLIC;
ALTER DEFAULT PRIVILEGES IN SCHEMA platform_journal REVOKE ALL ON TABLES FROM PUBLIC;
ALTER DEFAULT PRIVILEGES IN SCHEMA platform_journal REVOKE ALL ON FUNCTIONS FROM PUBLIC;

-- ---------------------------------------------------------------------------
-- Helpers of the canonical form
-- ---------------------------------------------------------------------------

-- Backslash first, then the separator, so the mapping is reversible and no value slides into the
-- next field. chr(92) rather than a literal: a backslash literal's meaning depends on
-- standard_conforming_strings, and this must render the same bytes in every session.
CREATE FUNCTION platform_journal.escape(value text) RETURNS text
    LANGUAGE sql IMMUTABLE PARALLEL SAFE AS
$$ SELECT coalesce(replace(replace(value, chr(92), chr(92) || chr(92)), '|', chr(92) || '|'), '') $$;

-- Details are flat string pairs: a number or a nested object would render one way here and another
-- way in Java, and every honest line would then read as rewritten.
CREATE FUNCTION platform_journal.details_are_flat(details jsonb) RETURNS boolean
    LANGUAGE sql IMMUTABLE PARALLEL SAFE AS
$$
SELECT jsonb_typeof(details) = 'object'
       AND NOT EXISTS (SELECT 1 FROM jsonb_each(details) AS pair WHERE jsonb_typeof(pair.value) <> 'string')
$$;

-- ---------------------------------------------------------------------------
-- The lines
-- ---------------------------------------------------------------------------

CREATE TABLE platform_journal.journal_line (
    seq           bigint       NOT NULL,
    occurred_at   timestamptz  NOT NULL,
    actor_subject varchar(64)  NOT NULL,
    actor_roles   text[]       NOT NULL DEFAULT '{}',
    actor_session varchar(64),
    request_id    varchar(64),
    gesture       varchar(64)  NOT NULL,
    outcome       varchar(16)  NOT NULL,
    refusal_code  varchar(64),
    target_kind   varchar(32)  NOT NULL,
    target_ref    varchar(128),
    case_ref      varchar(128),
    reason_code   varchar(64),
    details       jsonb        NOT NULL DEFAULT '{}'::jsonb,
    prev_hash     bytea,
    row_hash      bytea        NOT NULL,
    CONSTRAINT pk_journal_line PRIMARY KEY (seq),
    CONSTRAINT ck_journal_line_outcome CHECK (outcome IN ('DONE', 'REFUSED')),
    CONSTRAINT ck_journal_line_refusal CHECK ((outcome = 'REFUSED') = (refusal_code IS NOT NULL)),
    CONSTRAINT ck_journal_line_roles CHECK (array_position(actor_roles, NULL) IS NULL),
    CONSTRAINT ck_journal_line_details CHECK (platform_journal.details_are_flat(details)),
    CONSTRAINT ck_journal_line_link CHECK ((seq = 1) = (prev_hash IS NULL))
);

COMMENT ON TABLE platform_journal.journal_line IS
    'Append-only, hash-chained journal of platform gestures. Ids and codes only, never personal data.';

-- The purge's cut and the reading by period.
CREATE INDEX ix_journal_line_occurred ON platform_journal.journal_line (occurred_at);
-- What an operator did, newest first.
CREATE INDEX ix_journal_line_actor ON platform_journal.journal_line (actor_subject, seq);
-- What was done to an account, a church or a report, newest first.
CREATE INDEX ix_journal_line_target ON platform_journal.journal_line (target_kind, target_ref, seq);

-- Frozen: changing it invalidates every line already written. Mirrored by JournalCanonicalForm.
CREATE FUNCTION platform_journal.canonical(line platform_journal.journal_line) RETURNS text
    LANGUAGE sql STABLE AS
$$
SELECT concat_ws('|',
    line.seq::text,
    ((extract(epoch FROM line.occurred_at) * 1000000)::bigint)::text,
    platform_journal.escape(line.actor_subject),
    platform_journal.escape(
        coalesce((SELECT string_agg(platform_journal.escape(granted.role_name), '|' ORDER BY granted.role_order)
                  FROM unnest(line.actor_roles) WITH ORDINALITY AS granted(role_name, role_order)), '')),
    platform_journal.escape(line.actor_session),
    platform_journal.escape(line.request_id),
    platform_journal.escape(line.gesture),
    platform_journal.escape(line.outcome),
    platform_journal.escape(line.refusal_code),
    platform_journal.escape(line.target_kind),
    platform_journal.escape(line.target_ref),
    platform_journal.escape(line.case_ref),
    platform_journal.escape(line.reason_code),
    platform_journal.escape(
        coalesce((SELECT string_agg(platform_journal.escape(pair.key) || '|' || platform_journal.escape(pair.value),
                                    '|' ORDER BY pair.key COLLATE "C")
                  FROM jsonb_each_text(line.details) AS pair), '')))
$$;

-- ---------------------------------------------------------------------------
-- The head: how far the chain went, and where it starts after a purge
-- ---------------------------------------------------------------------------

CREATE TABLE platform_journal.journal_head (
    singleton  boolean      NOT NULL DEFAULT true,
    last_seq   bigint       NOT NULL DEFAULT 0,
    last_hash  bytea,
    start_seq  bigint       NOT NULL DEFAULT 0,
    start_hash bytea,
    updated_at timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_journal_head PRIMARY KEY (singleton),
    CONSTRAINT ck_journal_head_singleton CHECK (singleton),
    CONSTRAINT ck_journal_head_last CHECK (last_seq >= 0 AND (last_seq = 0) = (last_hash IS NULL)),
    CONSTRAINT ck_journal_head_start CHECK (start_seq >= 0 AND (start_seq = 0) = (start_hash IS NULL)
                                            AND start_seq <= last_seq)
);

COMMENT ON TABLE platform_journal.journal_head IS
    'Last position and hash of the journal, and its start point after a purge. Written by triggers and the purge only.';

-- Written once, here: the inserts lock this row, so even the first two gestures queue.
INSERT INTO platform_journal.journal_head DEFAULT VALUES;

-- The head moves its end one position onto the line just written, or its start forward onto a line
-- about to be purged; never both, never back, and never onto a position no line holds.
CREATE FUNCTION platform_journal.guard_head() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS
$$
DECLARE
    end_moved   boolean;
    start_moved boolean;
    named_seq   bigint;
    named_hash  bytea;
BEGIN
    end_moved := NEW.last_seq <> OLD.last_seq OR NEW.last_hash IS DISTINCT FROM OLD.last_hash;
    start_moved := NEW.start_seq <> OLD.start_seq OR NEW.start_hash IS DISTINCT FROM OLD.start_hash;
    IF end_moved AND start_moved THEN
        RAISE EXCEPTION 'platform journal: the head moves its end or its start, not both'
            USING ERRCODE = 'restrict_violation';
    END IF;
    IF end_moved AND NEW.last_seq <> OLD.last_seq + 1 THEN
        RAISE EXCEPTION 'platform journal: the head advances one position at a time, not % to %',
            OLD.last_seq, NEW.last_seq USING ERRCODE = 'restrict_violation';
    END IF;
    IF start_moved AND NEW.start_seq <= OLD.start_seq THEN
        RAISE EXCEPTION 'platform journal: the start point only moves forward, not % to %',
            OLD.start_seq, NEW.start_seq USING ERRCODE = 'restrict_violation';
    END IF;
    IF end_moved OR start_moved THEN
        named_seq := CASE WHEN end_moved THEN NEW.last_seq ELSE NEW.start_seq END;
        named_hash := CASE WHEN end_moved THEN NEW.last_hash ELSE NEW.start_hash END;
        IF NOT EXISTS (SELECT 1 FROM platform_journal.journal_line line
                       WHERE line.seq = named_seq AND line.row_hash = named_hash) THEN
            RAISE EXCEPTION 'platform journal: the head must name a written line, not position %', named_seq
                USING ERRCODE = 'restrict_violation';
        END IF;
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER trg_journal_head_guard
    BEFORE UPDATE ON platform_journal.journal_head
    FOR EACH ROW EXECUTE FUNCTION platform_journal.guard_head();

-- ---------------------------------------------------------------------------
-- The chain, set by the database
-- ---------------------------------------------------------------------------

-- SECURITY DEFINER: the application role holds no right on the head, which these read and move.
-- Whatever position or hash the insert carried is overwritten.
CREATE FUNCTION platform_journal.chain_line() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS
$$
DECLARE
    head platform_journal.journal_head%ROWTYPE;
BEGIN
    SELECT * INTO head FROM platform_journal.journal_head FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'platform journal: the head is missing' USING ERRCODE = 'restrict_violation';
    END IF;
    NEW.seq := head.last_seq + 1;
    NEW.prev_hash := head.last_hash;
    NEW.row_hash := sha256(coalesce(head.last_hash, decode('00', 'hex'))
                           || convert_to(platform_journal.canonical(NEW), 'UTF8'));
    RETURN NEW;
END
$$;

-- After the line exists, so the head can only name a line that was really written. A multi-row
-- insert gives two lines the same position and fails on the primary key: PlatformJournal writes one
-- line per statement.
CREATE FUNCTION platform_journal.advance_head() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS
$$
BEGIN
    UPDATE platform_journal.journal_head
    SET last_seq = NEW.seq, last_hash = NEW.row_hash, updated_at = now();
    RETURN NULL;
END
$$;

CREATE TRIGGER trg_journal_line_chain
    BEFORE INSERT ON platform_journal.journal_line
    FOR EACH ROW EXECUTE FUNCTION platform_journal.chain_line();

CREATE TRIGGER trg_journal_line_head
    AFTER INSERT ON platform_journal.journal_line
    FOR EACH ROW EXECUTE FUNCTION platform_journal.advance_head();

-- ---------------------------------------------------------------------------
-- Append only
-- ---------------------------------------------------------------------------

CREATE FUNCTION platform_journal.refuse_change() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'platform journal: % on % is refused', TG_OP, TG_TABLE_NAME
        USING ERRCODE = 'restrict_violation';
END
$$;

CREATE TRIGGER trg_journal_line_no_update
    BEFORE UPDATE ON platform_journal.journal_line
    FOR EACH ROW EXECUTE FUNCTION platform_journal.refuse_change();

-- Apart from the UPDATE trigger: the purge lifts this one alone, inside its own transaction.
CREATE TRIGGER trg_journal_line_no_delete
    BEFORE DELETE ON platform_journal.journal_line
    FOR EACH ROW EXECUTE FUNCTION platform_journal.refuse_change();

CREATE TRIGGER trg_journal_line_no_truncate
    BEFORE TRUNCATE ON platform_journal.journal_line
    FOR EACH STATEMENT EXECUTE FUNCTION platform_journal.refuse_change();

CREATE TRIGGER trg_journal_head_no_delete
    BEFORE DELETE ON platform_journal.journal_head
    FOR EACH ROW EXECUTE FUNCTION platform_journal.refuse_change();

CREATE TRIGGER trg_journal_head_no_truncate
    BEFORE TRUNCATE ON platform_journal.journal_head
    FOR EACH STATEMENT EXECUTE FUNCTION platform_journal.refuse_change();

-- ---------------------------------------------------------------------------
-- What the application calls
-- ---------------------------------------------------------------------------

-- The head, read without a right on its table.
CREATE FUNCTION platform_journal.current_head()
    RETURNS TABLE (last_seq bigint, last_hash bytea, start_seq bigint, start_hash bytea)
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS
$$ SELECT head.last_seq, head.last_hash, head.start_seq, head.start_hash FROM platform_journal.journal_head head $$;

-- Removes the longest run of oldest lines all older than the retention (24 months, owner decision
-- C3) and returns how many. The term lives here, not in a parameter: a caller holding the
-- application role cannot empty the journal by naming a later cut. The lines' lock first, as an
-- insert takes it before the head's, so a purge and a gesture never deadlock; it also keeps every
-- other session out while the delete trigger is lifted.
CREATE FUNCTION platform_journal.purge_expired() RETURNS bigint
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS
$$
DECLARE
    cutoff       timestamptz := now() - interval '24 months';
    head         platform_journal.journal_head%ROWTYPE;
    through_seq  bigint;
    through_hash bytea;
    removed      bigint;
BEGIN
    LOCK TABLE platform_journal.journal_line IN ACCESS EXCLUSIVE MODE;
    SELECT * INTO head FROM platform_journal.journal_head FOR UPDATE;

    SELECT coalesce(min(line.seq), head.last_seq + 1) - 1 INTO through_seq
    FROM platform_journal.journal_line line
    WHERE line.occurred_at >= cutoff;

    IF through_seq <= head.start_seq THEN
        RETURN 0;
    END IF;

    SELECT line.row_hash INTO through_hash FROM platform_journal.journal_line line WHERE line.seq = through_seq;
    UPDATE platform_journal.journal_head SET start_seq = through_seq, start_hash = through_hash, updated_at = now();

    ALTER TABLE platform_journal.journal_line DISABLE TRIGGER trg_journal_line_no_delete;
    DELETE FROM platform_journal.journal_line line WHERE line.seq <= through_seq;
    GET DIAGNOSTICS removed = ROW_COUNT;
    ALTER TABLE platform_journal.journal_line ENABLE TRIGGER trg_journal_line_no_delete;
    RETURN removed;
END
$$;

-- ---------------------------------------------------------------------------
-- Privileges
-- ---------------------------------------------------------------------------

REVOKE ALL ON platform_journal.journal_line, platform_journal.journal_head FROM PUBLIC;
REVOKE ALL ON FUNCTION platform_journal.escape(text), platform_journal.details_are_flat(jsonb),
    platform_journal.canonical(platform_journal.journal_line), platform_journal.guard_head(),
    platform_journal.chain_line(), platform_journal.advance_head(), platform_journal.refuse_change(),
    platform_journal.current_head(), platform_journal.purge_expired() FROM PUBLIC;

GRANT USAGE ON SCHEMA platform_journal TO ${journal_writer};
GRANT SELECT, INSERT ON platform_journal.journal_line TO ${journal_writer};
-- The CHECK on details runs with the inserting role's rights.
GRANT EXECUTE ON FUNCTION platform_journal.details_are_flat(jsonb) TO ${journal_writer};
GRANT EXECUTE ON FUNCTION platform_journal.current_head() TO ${journal_writer};
GRANT EXECUTE ON FUNCTION platform_journal.purge_expired() TO ${journal_writer};
