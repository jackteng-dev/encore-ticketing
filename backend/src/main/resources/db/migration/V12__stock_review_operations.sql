-- Review jobs/budgets remain immutable. Operator evidence is recorded separately.
CREATE TABLE stock_review_command (
 request_id uuid PRIMARY KEY,
 actor_id uuid NOT NULL REFERENCES staff_account(id),
 intent_id uuid NOT NULL,
 kind varchar(12) NOT NULL,
 expected_attempt integer NOT NULL CHECK(expected_attempt BETWEEN 1 AND 20),
 expected_decision varchar(8) NOT NULL CHECK(expected_decision IN ('ABANDON','COMMIT','RELEASE')),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 FOREIGN KEY(intent_id,kind) REFERENCES stock_recovery_job(intent_id,kind),
 CHECK((kind='UNUSED' AND expected_decision='ABANDON') OR (kind='SETTLEMENT' AND expected_decision IN ('COMMIT','RELEASE')))
);
CREATE FUNCTION require_original_stock_review() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM stock_recovery_job WHERE intent_id=NEW.intent_id AND kind=NEW.kind AND state='REVIEW' AND attempts=NEW.expected_attempt) THEN
  RAISE EXCEPTION 'Operator review requires the original immutable review attempt';
 END IF;
 IF NEW.kind='UNUSED' AND NOT EXISTS(SELECT 1 FROM stock_hold_progress p WHERE p.intent_id=NEW.intent_id AND p.state='ABORTING'
    AND NOT EXISTS(SELECT 1 FROM stock_hold_acceptance a WHERE a.intent_id=p.intent_id)) THEN
  RAISE EXCEPTION 'Unused review requires committed unused abandonment';
 END IF;
 IF NEW.kind='SETTLEMENT' AND NOT EXISTS(SELECT 1 FROM stock_hold_decision d WHERE d.intent_id=NEW.intent_id AND d.target=NEW.expected_decision
    AND NOT EXISTS(SELECT 1 FROM stock_hold_ack a WHERE a.intent_id=d.intent_id)) THEN
  RAISE EXCEPTION 'Settlement review requires an unacknowledged original decision';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER require_original_stock_review BEFORE INSERT ON stock_review_command FOR EACH ROW EXECUTE FUNCTION require_original_stock_review();
CREATE TRIGGER immutable_stock_review_command BEFORE UPDATE OR DELETE ON stock_review_command FOR EACH ROW EXECUTE FUNCTION immutable_stock_record();
CREATE TABLE stock_review_progress (
 request_id uuid PRIMARY KEY REFERENCES stock_review_command(request_id),
 state varchar(12) NOT NULL CHECK(state IN ('REGISTERED','PENDING','RESOLVED','BLOCKED')),
 version bigint NOT NULL CHECK(version>0),
 resolution varchar(12) CHECK(resolution IN ('RELEASED','COMMITTED','ABSENT','KEY_MISSING')),
 failure varchar(48) CHECK(failure ~ '^[A-Z][A-Z0-9_]{0,47}$'),
 updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 CHECK((state='RESOLVED')=(resolution IS NOT NULL)),
 CHECK(state<>'RESOLVED' OR failure IS NULL),
 CHECK(state<>'BLOCKED' OR failure IS NOT NULL)
);
CREATE TABLE stock_review_audit (
 request_id uuid NOT NULL REFERENCES stock_review_command(request_id),
 version bigint NOT NULL,
 state varchar(12) NOT NULL,
 resolution varchar(12),
 failure varchar(48),
 created_at timestamptz NOT NULL,
 PRIMARY KEY(request_id,version)
);
CREATE TRIGGER immutable_stock_review_audit BEFORE UPDATE OR DELETE ON stock_review_audit FOR EACH ROW EXECUTE FUNCTION immutable_stock_record();
CREATE FUNCTION guard_stock_review_progress() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE command stock_review_command;
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Review progress cannot be deleted'; END IF;
 SELECT * INTO STRICT command FROM stock_review_command WHERE request_id=NEW.request_id;
 IF TG_OP='INSERT' THEN
  IF NEW.state<>'REGISTERED' OR NEW.version<>1 OR NEW.resolution IS NOT NULL OR NEW.failure IS NOT NULL THEN
   RAISE EXCEPTION 'Review starts with registered identity';
  END IF;
 ELSE
  IF NEW.request_id<>OLD.request_id OR NEW.version<>OLD.version+1 OR OLD.state IN ('RESOLVED','BLOCKED') OR NEW.state='REGISTERED' THEN
   RAISE EXCEPTION 'Review checkpoint is fenced or terminal';
  END IF;
 END IF;
 IF NEW.state='RESOLVED' AND NOT (
   (command.kind='UNUSED' AND EXISTS(SELECT 1 FROM stock_hold_progress p WHERE p.intent_id=command.intent_id AND p.state='ABORTED' AND p.resolution=NEW.resolution)
      AND NOT EXISTS(SELECT 1 FROM stock_hold_acceptance WHERE intent_id=command.intent_id)) OR
   (command.kind='SETTLEMENT' AND EXISTS(SELECT 1 FROM stock_hold_decision d JOIN stock_hold_ack a ON a.intent_id=d.intent_id
      WHERE d.intent_id=command.intent_id AND d.target=command.expected_decision AND a.resolution=NEW.resolution))
 ) THEN RAISE EXCEPTION 'Resolved review needs original durable domain evidence'; END IF;
 NEW.updated_at:=clock_timestamp();RETURN NEW;
END $$;
CREATE TRIGGER guarded_stock_review_progress BEFORE INSERT OR UPDATE OR DELETE ON stock_review_progress FOR EACH ROW EXECUTE FUNCTION guard_stock_review_progress();
CREATE FUNCTION record_stock_review_audit() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 INSERT INTO stock_review_audit(request_id,version,state,resolution,failure,created_at)
 VALUES(NEW.request_id,NEW.version,NEW.state,NEW.resolution,NEW.failure,NEW.updated_at);RETURN NEW;
END $$;
CREATE TRIGGER stock_review_audit_writer AFTER INSERT OR UPDATE ON stock_review_progress FOR EACH ROW EXECUTE FUNCTION record_stock_review_audit();
CREATE INDEX stock_review_intent ON stock_review_command(intent_id,created_at DESC,request_id);
