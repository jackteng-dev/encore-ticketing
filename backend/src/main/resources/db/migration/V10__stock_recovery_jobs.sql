-- Queue identity is durable before Redis IO and before the broker ACK.
-- Historical business records are not changed by this migration.
CREATE TABLE stock_recovery_job (
 intent_id uuid NOT NULL REFERENCES stock_hold_intent(id),
 kind varchar(12) NOT NULL CHECK(kind IN ('UNUSED','SETTLEMENT')),
 state varchar(12) NOT NULL DEFAULT 'WAITING' CHECK(state IN ('WAITING','LEASED','DONE','REVIEW')),
 attempts integer NOT NULL DEFAULT 0,
 max_attempts integer NOT NULL DEFAULT 6 CHECK(max_attempts BETWEEN 1 AND 20),
 next_attempt_at timestamptz NOT NULL,
 lease_token uuid,
 lease_until timestamptz,
 last_code varchar(48) CHECK(last_code ~ '^[A-Z][A-Z0-9_]{0,47}$'),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 completed_at timestamptz,
 PRIMARY KEY(intent_id,kind),
 CHECK(attempts BETWEEN 0 AND max_attempts),
 CHECK((state='LEASED' AND lease_token IS NOT NULL AND lease_until IS NOT NULL)
    OR (state<>'LEASED' AND lease_token IS NULL AND lease_until IS NULL)),
 CHECK((state IN ('DONE','REVIEW'))=(completed_at IS NOT NULL)),
 CHECK(state<>'WAITING' OR attempts<max_attempts)
);
CREATE INDEX stock_recovery_due ON stock_recovery_job(next_attempt_at,intent_id,kind)
 WHERE state IN ('WAITING','LEASED');
CREATE TABLE stock_recovery_claim (
 intent_id uuid NOT NULL,
 kind varchar(12) NOT NULL,
 attempt integer NOT NULL CHECK(attempt BETWEEN 1 AND 20),
 token uuid NOT NULL UNIQUE,
 claimed_at timestamptz NOT NULL,
 lease_until timestamptz NOT NULL CHECK(lease_until>claimed_at),
 PRIMARY KEY(intent_id,kind,attempt),
 FOREIGN KEY(intent_id,kind) REFERENCES stock_recovery_job(intent_id,kind)
);
CREATE TRIGGER immutable_stock_recovery_claim BEFORE UPDATE OR DELETE ON stock_recovery_claim
 FOR EACH ROW EXECUTE FUNCTION immutable_stock_record();

CREATE FUNCTION guard_stock_recovery_job() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Recovery jobs cannot be deleted'; END IF;
 IF TG_OP='INSERT' THEN
  IF NEW.state<>'WAITING' OR NEW.attempts<>0 OR NEW.last_code IS NOT NULL THEN
   RAISE EXCEPTION 'Recovery jobs must start waiting';
  END IF;
  IF NEW.kind='SETTLEMENT' AND NOT EXISTS(SELECT 1 FROM stock_hold_decision WHERE intent_id=NEW.intent_id) THEN
   RAISE EXCEPTION 'Settlement needs an immutable formal decision';
  END IF;
 ELSE
  IF ROW(NEW.intent_id,NEW.kind,NEW.max_attempts,NEW.created_at) IS DISTINCT FROM
     ROW(OLD.intent_id,OLD.kind,OLD.max_attempts,OLD.created_at) OR OLD.state IN ('DONE','REVIEW') THEN
   RAISE EXCEPTION 'Recovery identity, budget and terminal results are immutable';
  END IF;
  IF NEW.state='LEASED' THEN
   IF NEW.attempts<>OLD.attempts+1 OR NEW.lease_token IS NOT DISTINCT FROM OLD.lease_token
     OR (OLD.state='LEASED' AND OLD.lease_until>clock_timestamp()) THEN
    RAISE EXCEPTION 'Claim requires a new attempt and an available lease';
   END IF;
  ELSIF NEW.attempts<>OLD.attempts OR OLD.state<>'LEASED' THEN
   RAISE EXCEPTION 'Only a claimed attempt can finish';
  END IF;
  IF NEW.state='DONE' AND NOT (
    (NEW.kind='SETTLEMENT' AND EXISTS(SELECT 1 FROM stock_hold_ack WHERE intent_id=NEW.intent_id)) OR
    (NEW.kind='UNUSED' AND (EXISTS(SELECT 1 FROM stock_hold_acceptance WHERE intent_id=NEW.intent_id) OR
      EXISTS(SELECT 1 FROM stock_hold_progress WHERE intent_id=NEW.intent_id AND state='ABORTED')))) THEN
   RAISE EXCEPTION 'Completed recovery needs durable domain evidence';
  END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER guarded_stock_recovery_job BEFORE INSERT OR UPDATE OR DELETE ON stock_recovery_job
 FOR EACH ROW EXECUTE FUNCTION guard_stock_recovery_job();

CREATE FUNCTION enqueue_stock_recovery() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_TABLE_NAME='stock_hold_intent' THEN
  INSERT INTO stock_recovery_job(intent_id,kind,next_attempt_at) VALUES(NEW.id,'UNUSED',NEW.deadline_at);
 ELSE
  INSERT INTO stock_recovery_job(intent_id,kind,next_attempt_at) VALUES(NEW.intent_id,'SETTLEMENT',clock_timestamp());
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER enqueue_unused_stock_recovery AFTER INSERT ON stock_hold_intent
 FOR EACH ROW EXECUTE FUNCTION enqueue_stock_recovery();
CREATE TRIGGER enqueue_formal_stock_recovery AFTER INSERT ON stock_hold_decision
 FOR EACH ROW EXECUTE FUNCTION enqueue_stock_recovery();
-- Backfill unresolved V8/V9 work; accepted PROCESSING requests continue to use
-- the formal purchase expiry/worker and never enter unused abandonment.
INSERT INTO stock_recovery_job(intent_id,kind,next_attempt_at)
 SELECT i.id,'UNUSED',CASE WHEN p.state='ABORTING' THEN clock_timestamp() ELSE i.deadline_at END
 FROM stock_hold_intent i JOIN stock_hold_progress p ON p.intent_id=i.id
 WHERE p.state<>'ABORTED' AND NOT EXISTS(SELECT 1 FROM stock_hold_acceptance a WHERE a.intent_id=i.id);
INSERT INTO stock_recovery_job(intent_id,kind,next_attempt_at)
 SELECT d.intent_id,'SETTLEMENT',clock_timestamp() FROM stock_hold_decision d
 WHERE NOT EXISTS(SELECT 1 FROM stock_hold_ack a WHERE a.intent_id=d.intent_id);
