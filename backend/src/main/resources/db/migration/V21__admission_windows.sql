-- No original business row changes or fabricated historical time proof.
-- This registry classifies exactly the CHECK_IN commands present at cutover.
CREATE TABLE ticket_window_legacy (
 request_id uuid PRIMARY KEY REFERENCES ticket_admission_command(request_id)
);
INSERT INTO ticket_window_legacy SELECT request_id FROM ticket_admission_command WHERE action='CHECK_IN';
CREATE TRIGGER immutable_window_legacy BEFORE INSERT OR UPDATE OR DELETE ON ticket_window_legacy FOR EACH ROW EXECUTE FUNCTION tickets_immutable();

CREATE TABLE ticket_window_command (
 request_id uuid PRIMARY KEY,
 actor_id uuid NOT NULL REFERENCES staff_account(id),
 session_id uuid NOT NULL REFERENCES event_session(id),
 expected_version bigint NOT NULL CHECK(expected_version BETWEEN 0 AND 1000000000),
 opens_at_text varchar(20) NOT NULL CHECK(opens_at_text ~ '^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$'),
 closes_at_text varchar(20) NOT NULL CHECK(closes_at_text ~ '^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$'),
 fingerprint char(64) NOT NULL CHECK(fingerprint ~ '^[0-9a-f]{64}$'),
 state varchar(8) NOT NULL CHECK(state IN ('APPLIED','FENCED')),
 policy_version bigint,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 CHECK((state='APPLIED' AND policy_version IS NOT NULL AND policy_version=expected_version+1) OR (state='FENCED' AND policy_version IS NULL))
);
CREATE TABLE ticket_window_policy (
 session_id uuid NOT NULL REFERENCES event_session(id),
 version bigint NOT NULL CHECK(version BETWEEN 1 AND 1000000001),
 request_id uuid NOT NULL UNIQUE REFERENCES ticket_window_command(request_id),
 opens_at timestamptz NOT NULL,
 closes_at timestamptz NOT NULL,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(session_id,version),
 CHECK(isfinite(opens_at) AND isfinite(closes_at) AND opens_at<closes_at)
);
ALTER TABLE ticket_window_command ADD FOREIGN KEY(session_id,policy_version) REFERENCES ticket_window_policy(session_id,version) DEFERRABLE INITIALLY DEFERRED;
CREATE TABLE ticket_window_audit (LIKE ticket_window_command INCLUDING ALL);
ALTER TABLE ticket_window_audit ADD FOREIGN KEY(request_id) REFERENCES ticket_window_command(request_id);
CREATE TABLE ticket_window_proof (
 request_id uuid PRIMARY KEY REFERENCES ticket_admission_command(request_id),
 ticket_id uuid NOT NULL UNIQUE REFERENCES issued_ticket(id),
 session_id uuid NOT NULL,
 policy_version bigint NOT NULL,
 checked_at timestamptz NOT NULL,
 FOREIGN KEY(session_id,policy_version) REFERENCES ticket_window_policy(session_id,version)
);
CREATE FUNCTION ticket_window_request_lock(r uuid) RETURNS void LANGUAGE sql VOLATILE AS $$
 SELECT pg_advisory_xact_lock(hashtextextended('ticket-window:'||r::text,0))
$$;
CREATE FUNCTION ticket_window_contains(opens timestamptz,closes timestamptz,at_time timestamptz) RETURNS boolean LANGUAGE sql IMMUTABLE AS $$
 SELECT isfinite(opens) AND isfinite(closes) AND isfinite(at_time) AND opens<closes AND opens<=at_time AND at_time<closes
$$;
CREATE FUNCTION ticket_window_command_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE current_version bigint;
BEGIN
 PERFORM 1 FROM staff_account WHERE id=NEW.actor_id AND enabled AND role='MANAGER' FOR SHARE;
 IF NOT FOUND THEN RAISE EXCEPTION 'Window requires current manager' USING ERRCODE='23514'; END IF;
 PERFORM ticket_window_request_lock(NEW.request_id);
 IF NEW.state='APPLIED' THEN
  PERFORM 1 FROM event_session WHERE id=NEW.session_id FOR UPDATE;
  SELECT coalesce(max(version),0) INTO current_version FROM ticket_window_policy WHERE session_id=NEW.session_id;
  IF NEW.expected_version<>current_version OR NEW.opens_at_text::timestamptz>=NEW.closes_at_text::timestamptz
  THEN RAISE EXCEPTION 'Stale or invalid admission window' USING ERRCODE='23514'; END IF;
 END IF;
 NEW.created_at:=clock_timestamp();
 RETURN NEW;
END $$;
CREATE TRIGGER window_command_guard BEFORE INSERT ON ticket_window_command FOR EACH ROW EXECUTE FUNCTION ticket_window_command_guard();
CREATE FUNCTION ticket_window_policy_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 PERFORM 1 FROM event_session WHERE id=NEW.session_id FOR UPDATE;
 IF NEW.version<>(SELECT coalesce(max(version),0)+1 FROM ticket_window_policy WHERE session_id=NEW.session_id)
 OR NOT EXISTS(SELECT 1 FROM ticket_window_command c WHERE c.request_id=NEW.request_id AND c.state='APPLIED'
  AND c.session_id=NEW.session_id AND c.policy_version=NEW.version
  AND c.opens_at_text::timestamptz=NEW.opens_at AND c.closes_at_text::timestamptz=NEW.closes_at)
 THEN RAISE EXCEPTION 'Window policy requires exact original command' USING ERRCODE='23514'; END IF;
 NEW.created_at:=(SELECT created_at FROM ticket_window_command WHERE request_id=NEW.request_id);
 RETURN NEW;
END $$;
CREATE TRIGGER window_policy_guard BEFORE INSERT ON ticket_window_policy FOR EACH ROW EXECUTE FUNCTION ticket_window_policy_guard();
CREATE FUNCTION ticket_window_record() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN INSERT INTO ticket_window_audit SELECT NEW.*; RETURN NEW; END $$;
CREATE TRIGGER window_audit AFTER INSERT ON ticket_window_command FOR EACH ROW EXECUTE FUNCTION ticket_window_record();
CREATE FUNCTION ticket_window_complete_command() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM ticket_window_audit a WHERE to_jsonb(a)=to_jsonb(NEW))
 OR (NEW.state='APPLIED' AND NOT EXISTS(SELECT 1 FROM ticket_window_policy p WHERE p.request_id=NEW.request_id
  AND p.session_id=NEW.session_id AND p.version=NEW.policy_version
  AND p.opens_at=NEW.opens_at_text::timestamptz AND p.closes_at=NEW.closes_at_text::timestamptz))
 OR (NEW.state='FENCED' AND EXISTS(SELECT 1 FROM ticket_window_policy WHERE request_id=NEW.request_id))
 THEN RAISE EXCEPTION 'Incomplete window command' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
CREATE CONSTRAINT TRIGGER window_complete AFTER INSERT ON ticket_window_command DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION ticket_window_complete_command();
CREATE TRIGGER immutable_window_command BEFORE UPDATE OR DELETE ON ticket_window_command FOR EACH ROW EXECUTE FUNCTION tickets_immutable();
CREATE TRIGGER immutable_window_policy BEFORE UPDATE OR DELETE ON ticket_window_policy FOR EACH ROW EXECUTE FUNCTION tickets_immutable();
CREATE TRIGGER immutable_window_audit BEFORE UPDATE OR DELETE ON ticket_window_audit FOR EACH ROW EXECUTE FUNCTION tickets_immutable();
CREATE TRIGGER immutable_window_proof BEFORE UPDATE OR DELETE ON ticket_window_proof FOR EACH ROW EXECUTE FUNCTION tickets_immutable();

CREATE FUNCTION ticket_window_proof_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE policy ticket_window_policy;
BEGIN
 PERFORM 1 FROM event_session WHERE id=NEW.session_id FOR SHARE;
 SELECT * INTO policy FROM ticket_window_policy WHERE session_id=NEW.session_id ORDER BY version DESC LIMIT 1;
 NEW.checked_at:=clock_timestamp();
 IF policy.version IS NULL OR NEW.policy_version<>policy.version
 OR NOT ticket_window_contains(policy.opens_at,policy.closes_at,NEW.checked_at)
 OR NOT EXISTS(SELECT 1 FROM ticket_admission_command c WHERE c.request_id=NEW.request_id
  AND c.action='CHECK_IN' AND c.ticket_id=NEW.ticket_id AND c.session_id=NEW.session_id)
 THEN RAISE EXCEPTION 'Admission window not open or proof mismatch' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER window_proof_guard BEFORE INSERT ON ticket_window_proof FOR EACH ROW EXECUTE FUNCTION ticket_window_proof_guard();
CREATE FUNCTION ticket_window_admission_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE sid uuid; policy_version bigint;
BEGIN
 IF NEW.state='CHECKED_IN' THEN
  SELECT session_id INTO sid FROM ticket_admission_command WHERE request_id=NEW.changed_request_id;
  PERFORM 1 FROM event_session WHERE id=sid FOR SHARE;
  SELECT version INTO policy_version FROM ticket_window_policy WHERE session_id=sid ORDER BY version DESC LIMIT 1;
  IF policy_version IS NULL THEN RAISE EXCEPTION 'Admission window unconfigured' USING ERRCODE='23514'; END IF;
  INSERT INTO ticket_window_proof(request_id,ticket_id,session_id,policy_version,checked_at)
  VALUES(NEW.changed_request_id,NEW.id,sid,policy_version,clock_timestamp()) RETURNING checked_at INTO NEW.changed_at;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER ticket_window_admission BEFORE UPDATE ON issued_ticket FOR EACH ROW EXECUTE FUNCTION ticket_window_admission_guard();
CREATE FUNCTION ticket_window_complete_admission() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM issued_ticket t JOIN ticket_admission_command c ON c.request_id=t.changed_request_id
  JOIN ticket_admission_audit a ON a.request_id=c.request_id JOIN ticket_window_proof p ON p.request_id=c.request_id
  JOIN ticket_window_policy w ON w.session_id=p.session_id AND w.version=p.policy_version
  WHERE c.request_id=NEW.request_id AND c.action='CHECK_IN' AND p.ticket_id=c.ticket_id AND p.session_id=c.session_id
  AND t.id=c.ticket_id AND t.state='CHECKED_IN' AND t.changed_at=p.checked_at
  AND a.ticket_id=c.ticket_id AND a.state='CHECKED_IN' AND a.actor_id=c.actor_id
  AND ticket_window_contains(w.opens_at,w.closes_at,p.checked_at))
 THEN RAISE EXCEPTION 'Missing admission time proof' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
CREATE CONSTRAINT TRIGGER window_proof_complete AFTER INSERT ON ticket_window_proof DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION ticket_window_complete_admission();
CREATE FUNCTION ticket_window_require_new_admission() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.action='CHECK_IN' THEN PERFORM ticket_window_complete_admission_check(NEW.request_id); END IF;
 RETURN NEW;
END $$;
-- A separate deferred guard checks every NEW command; historical commands are
-- untouched. No application clock, pre-inserted proof or old registry bypass.
CREATE FUNCTION ticket_window_complete_admission_check(r uuid) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM ticket_window_proof WHERE request_id=r)
 THEN RAISE EXCEPTION 'New check-in requires time proof' USING ERRCODE='23514'; END IF;
END $$;
CREATE CONSTRAINT TRIGGER window_new_admission_complete AFTER INSERT ON ticket_admission_command DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION ticket_window_require_new_admission();
