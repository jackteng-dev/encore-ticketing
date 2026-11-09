-- Explicit original-manager enrollment only. No backfill, new return ID or body.
CREATE TABLE stock_return_recovery_job (
 request_id uuid PRIMARY KEY REFERENCES stock_return_staff_command(request_id),
 actor_id uuid NOT NULL REFERENCES staff_account(id),
 release_revision bigint NOT NULL CHECK(release_revision>0),
 state varchar(12) NOT NULL DEFAULT 'WAITING' CHECK(state IN ('WAITING','LEASED','DONE','REVIEW')),
 attempts integer NOT NULL DEFAULT 0,
 max_attempts integer NOT NULL DEFAULT 6 CHECK(max_attempts=6),
 version bigint NOT NULL DEFAULT 1 CHECK(version>0),
 next_attempt_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 lease_token uuid,
 lease_until timestamptz,
 last_code varchar(48) CHECK(last_code ~ '^[A-Z][A-Z0-9_]{0,47}$'),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 completed_at timestamptz,
 CHECK(attempts BETWEEN 0 AND max_attempts),
 CHECK((state='LEASED')=(lease_token IS NOT NULL AND lease_until IS NOT NULL)),
 CHECK(state='LEASED' OR (lease_token IS NULL AND lease_until IS NULL)),
 CHECK((state IN ('DONE','REVIEW'))=(completed_at IS NOT NULL)),
 CHECK(state<>'WAITING' OR attempts<max_attempts)
);
CREATE INDEX stock_return_recovery_due ON stock_return_recovery_job(next_attempt_at,request_id)
 WHERE state IN ('WAITING','LEASED');
CREATE TABLE stock_return_recovery_claim (
 request_id uuid NOT NULL REFERENCES stock_return_recovery_job(request_id),
 attempt integer NOT NULL CHECK(attempt BETWEEN 1 AND 6),
 token uuid NOT NULL UNIQUE,
 claimed_at timestamptz NOT NULL,
 lease_until timestamptz NOT NULL CHECK(lease_until>claimed_at),
 PRIMARY KEY(request_id,attempt)
);
CREATE TABLE stock_return_recovery_audit (
 request_id uuid NOT NULL REFERENCES stock_return_recovery_job(request_id),
 version bigint NOT NULL,
 actor_id uuid NOT NULL,
 release_revision bigint NOT NULL,
 state varchar(12) NOT NULL,
 attempts integer NOT NULL,
 last_code varchar(48),
 created_at timestamptz NOT NULL,
 PRIMARY KEY(request_id,version)
);
CREATE FUNCTION guard_stock_return_recovery() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE original stock_return_staff_command%ROWTYPE;
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Return recovery jobs cannot be deleted'; END IF;
 IF TG_OP='INSERT' THEN
  SELECT * INTO STRICT original FROM stock_return_staff_command WHERE request_id=NEW.request_id;
  PERFORM 1 FROM staff_account WHERE id=original.actor_id AND enabled AND role='MANAGER' FOR SHARE;
  IF NOT FOUND OR ROW(NEW.actor_id,NEW.release_revision) IS DISTINCT FROM ROW(original.actor_id,original.release_revision)
   OR NEW.state<>'WAITING' OR NEW.attempts<>0 OR NEW.version<>1 OR NEW.last_code IS NOT NULL
   OR NEW.completed_at IS NOT NULL
  THEN RAISE EXCEPTION 'Enrollment requires original current manager and exact return body'; END IF;
  PERFORM 1 FROM stock_return_staff_progress WHERE request_id=NEW.request_id AND state IN ('REGISTERED','CLOSED') FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION 'Only pending original return can enroll'; END IF;
  NEW.created_at:=clock_timestamp();NEW.next_attempt_at:=NEW.created_at;
 ELSE
  IF ROW(NEW.request_id,NEW.actor_id,NEW.release_revision,NEW.max_attempts,NEW.created_at)
    IS DISTINCT FROM ROW(OLD.request_id,OLD.actor_id,OLD.release_revision,OLD.max_attempts,OLD.created_at)
   OR OLD.state IN ('DONE','REVIEW') OR NEW.version<>OLD.version+1
  THEN RAISE EXCEPTION 'Original identity, budget and terminal history are immutable'; END IF;
  IF NEW.state='LEASED' THEN
   IF NEW.attempts<>OLD.attempts+1 OR NEW.lease_token IS NOT DISTINCT FROM OLD.lease_token
    OR NEW.lease_until<=clock_timestamp() OR NEW.lease_until>clock_timestamp()+interval '121 seconds'
    OR (OLD.state='WAITING' AND OLD.next_attempt_at>clock_timestamp())
    OR (OLD.state='LEASED' AND OLD.lease_until>clock_timestamp())
   THEN RAISE EXCEPTION 'Claim requires due task and a new finite attempt'; END IF;
  ELSIF OLD.state<>'LEASED' OR NEW.attempts<>OLD.attempts THEN
   RAISE EXCEPTION 'Only leased attempts can finish';
  END IF;
  IF NEW.state='DONE' AND NOT EXISTS(SELECT 1 FROM stock_return_staff_progress p
   JOIN stock_return_ack a USING(request_id) WHERE p.request_id=NEW.request_id AND p.state='APPLIED')
  THEN RAISE EXCEPTION 'Done requires acknowledged original APPLIED evidence'; END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER guarded_stock_return_recovery BEFORE INSERT OR UPDATE OR DELETE ON stock_return_recovery_job
 FOR EACH ROW EXECUTE FUNCTION guard_stock_return_recovery();
CREATE FUNCTION audit_stock_return_recovery() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE stamp timestamptz:=clock_timestamp();
BEGIN
 INSERT INTO stock_return_recovery_audit VALUES(NEW.request_id,NEW.version,NEW.actor_id,
  NEW.release_revision,NEW.state,NEW.attempts,NEW.last_code,stamp);
 IF NEW.state='LEASED' THEN
  INSERT INTO stock_return_recovery_claim VALUES(NEW.request_id,NEW.attempts,NEW.lease_token,stamp,NEW.lease_until);
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER audited_stock_return_recovery AFTER INSERT OR UPDATE ON stock_return_recovery_job
 FOR EACH ROW EXECUTE FUNCTION audit_stock_return_recovery();
CREATE TRIGGER immutable_stock_return_recovery_claim BEFORE UPDATE OR DELETE ON stock_return_recovery_claim
 FOR EACH ROW EXECUTE FUNCTION immutable_stock_record();
CREATE TRIGGER immutable_stock_return_recovery_audit BEFORE UPDATE OR DELETE ON stock_return_recovery_audit
 FOR EACH ROW EXECUTE FUNCTION immutable_stock_record();
