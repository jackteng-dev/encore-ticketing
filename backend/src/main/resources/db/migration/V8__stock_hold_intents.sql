-- Internal pre-debit coordinator only; not a new checkout endpoint.
ALTER TABLE stock_rebuild_command ADD CONSTRAINT stock_rebuild_source_identity UNIQUE(ticket_id,generation,source_revision);
CREATE TABLE stock_hold_intent (
 id uuid PRIMARY KEY,
 customer_id uuid NOT NULL REFERENCES customer_account(id),
 request_id uuid NOT NULL,
 ticket_id uuid NOT NULL,
 generation bigint NOT NULL,
 source_revision bigint NOT NULL,
 quantity integer NOT NULL CHECK(quantity BETWEEN 1 AND 10),
 ticket_version bigint NOT NULL CHECK(ticket_version>0),
 total_twd integer NOT NULL CHECK(total_twd BETWEEN 1 AND 10000000),
 session_version bigint NOT NULL CHECK(session_version>0),
 session_hash varchar(64) NOT NULL CHECK(session_hash ~ '^[0-9a-f]{64}$'),
 fingerprint varchar(64) NOT NULL CHECK(fingerprint ~ '^[0-9a-f]{64}$'),
 claim_fingerprint varchar(64) NOT NULL CHECK(claim_fingerprint ~ '^[0-9a-f]{64}$'),
 created_at timestamptz NOT NULL,
 deadline_at timestamptz NOT NULL,
 UNIQUE(customer_id,request_id),
 FOREIGN KEY(ticket_id,generation,source_revision) REFERENCES stock_rebuild_command(ticket_id,generation,source_revision),
 CHECK(deadline_at>created_at AND deadline_at<=created_at+interval '120 seconds'),
 CHECK(date_trunc('milliseconds',deadline_at)=deadline_at)
);
CREATE TABLE stock_hold_progress (
 intent_id uuid PRIMARY KEY REFERENCES stock_hold_intent(id),
 state varchar(12) NOT NULL DEFAULT 'PREPARED' CHECK(state IN ('PREPARED','HELD','ABORTING','ABORTED')),
 held_at timestamptz,
 abort_at timestamptz,
 abort_reason varchar(32),
 resolved_at timestamptz,
 resolution varchar(12) CHECK(resolution IN ('RELEASED','ABSENT','KEY_MISSING')),
 CHECK((abort_at IS NULL)=(abort_reason IS NULL)),
 CHECK((resolved_at IS NULL)=(resolution IS NULL)),
 CHECK((state='PREPARED' AND held_at IS NULL AND abort_at IS NULL AND resolved_at IS NULL)
  OR (state='HELD' AND held_at IS NOT NULL AND abort_at IS NULL AND resolved_at IS NULL)
  OR (state='ABORTING' AND abort_at IS NOT NULL AND resolved_at IS NULL)
  OR (state='ABORTED' AND abort_at IS NOT NULL AND resolved_at IS NOT NULL))
);
CREATE INDEX stock_intent_ticket ON stock_hold_intent(ticket_id,generation);
CREATE TRIGGER immutable_stock_hold_intent BEFORE UPDATE OR DELETE ON stock_hold_intent FOR EACH ROW EXECUTE FUNCTION immutable_stock_record();
CREATE FUNCTION guard_stock_hold_progress() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Stock hold progress cannot be deleted'; END IF;
 IF NEW.intent_id<>OLD.intent_id OR
  NOT ((OLD.state='PREPARED' AND NEW.state IN ('HELD','ABORTING'))
    OR (OLD.state='HELD' AND NEW.state='ABORTING')
    OR (OLD.state='ABORTING' AND NEW.state='ABORTED')) OR
  (OLD.held_at IS NOT NULL AND NEW.held_at IS DISTINCT FROM OLD.held_at) OR
  (NEW.state<>'HELD' AND NEW.held_at IS DISTINCT FROM OLD.held_at) OR
  (OLD.abort_at IS NOT NULL AND ROW(NEW.abort_at,NEW.abort_reason) IS DISTINCT FROM ROW(OLD.abort_at,OLD.abort_reason))
 THEN RAISE EXCEPTION 'Stock hold transition/evidence is immutable'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER guarded_stock_hold_progress BEFORE UPDATE OR DELETE ON stock_hold_progress FOR EACH ROW EXECUTE FUNCTION guard_stock_hold_progress();
