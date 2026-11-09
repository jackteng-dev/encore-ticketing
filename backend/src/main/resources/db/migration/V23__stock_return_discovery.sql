-- Explicit finite, original-manager authorization. No historical-source backfill.
CREATE TABLE stock_return_discovery_policy (
 request_id uuid PRIMARY KEY,
 actor_id uuid NOT NULL REFERENCES staff_account(id),
 ticket_id uuid NOT NULL UNIQUE REFERENCES stock_projection(ticket_id),
 start_revision bigint NOT NULL CHECK(start_revision>=0),
 max_sources integer NOT NULL CHECK(max_sources BETWEEN 1 AND 100),
 state varchar(12) NOT NULL DEFAULT 'ACTIVE' CHECK(state IN ('ACTIVE','EXHAUSTED','STOPPED')),
 version bigint NOT NULL DEFAULT 1 CHECK(version>0),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE stock_return_discovery_stop (
 request_id uuid PRIMARY KEY,
 policy_id uuid NOT NULL REFERENCES stock_return_discovery_policy(request_id),
 actor_id uuid NOT NULL REFERENCES staff_account(id),
 expected_version bigint NOT NULL CHECK(expected_version>0),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE stock_return_discovery_source (
 order_id uuid PRIMARY KEY REFERENCES ticket_order(id),
 ticket_id uuid NOT NULL,
 release_revision bigint NOT NULL,
 policy_id uuid NOT NULL REFERENCES stock_return_discovery_policy(request_id),
 disposition varchar(16) NOT NULL CHECK(disposition IN ('ENROLLED','EXISTING_RETURN')),
 return_request_id uuid UNIQUE REFERENCES stock_return_recovery_job(request_id),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(ticket_id,release_revision),
 FOREIGN KEY(ticket_id,release_revision) REFERENCES stock_revision_origin(ticket_id,to_revision),
 CHECK((disposition='ENROLLED')=(return_request_id IS NOT NULL))
);
CREATE TABLE stock_return_discovery_audit (
 policy_id uuid NOT NULL REFERENCES stock_return_discovery_policy(request_id),
 version bigint NOT NULL,
 state varchar(12) NOT NULL,
 actor_id uuid NOT NULL,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(policy_id,version)
);
CREATE FUNCTION guard_stock_return_discovery_policy() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Discovery policy cannot be deleted'; END IF;
 PERFORM 1 FROM staff_account WHERE id=NEW.actor_id AND enabled AND role='MANAGER' FOR SHARE;
 IF NOT FOUND THEN RAISE EXCEPTION 'Discovery requires current original manager'; END IF;
 IF TG_OP='INSERT' THEN
  PERFORM 1 FROM stock_projection WHERE ticket_id=NEW.ticket_id AND source_revision=NEW.start_revision FOR SHARE;
  IF NOT FOUND OR NEW.state<>'ACTIVE' OR NEW.version<>1 THEN RAISE EXCEPTION 'New policy requires current source boundary'; END IF;
  NEW.created_at:=clock_timestamp();
 ELSE
  IF ROW(NEW.request_id,NEW.actor_id,NEW.ticket_id,NEW.start_revision,NEW.max_sources,NEW.created_at)
    IS DISTINCT FROM ROW(OLD.request_id,OLD.actor_id,OLD.ticket_id,OLD.start_revision,OLD.max_sources,OLD.created_at)
   OR NEW.version<>OLD.version+1 OR OLD.state='STOPPED'
   OR NEW.state NOT IN ('EXHAUSTED','STOPPED') OR NEW.state=OLD.state
  THEN RAISE EXCEPTION 'Discovery identity budget and terminal state are fenced'; END IF;
  IF NEW.state='EXHAUSTED' AND (SELECT count(*) FROM stock_return_discovery_source WHERE policy_id=NEW.request_id)<>NEW.max_sources
   THEN RAISE EXCEPTION 'Exhaustion requires exact consumed source budget'; END IF;
  IF NEW.state='STOPPED' AND NOT EXISTS(SELECT 1 FROM stock_return_discovery_stop WHERE policy_id=NEW.request_id
    AND actor_id=NEW.actor_id AND expected_version=OLD.version)
   THEN RAISE EXCEPTION 'Stop requires original manager command'; END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER guarded_stock_return_discovery_policy BEFORE INSERT OR UPDATE OR DELETE ON stock_return_discovery_policy
 FOR EACH ROW EXECUTE FUNCTION guard_stock_return_discovery_policy();
CREATE FUNCTION audit_stock_return_discovery_policy() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 INSERT INTO stock_return_discovery_audit(policy_id,version,state,actor_id) VALUES(NEW.request_id,NEW.version,NEW.state,NEW.actor_id);
 RETURN NEW;
END $$;
CREATE TRIGGER audited_stock_return_discovery_policy AFTER INSERT OR UPDATE ON stock_return_discovery_policy
 FOR EACH ROW EXECUTE FUNCTION audit_stock_return_discovery_policy();
CREATE FUNCTION guard_stock_return_discovery_source() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE policy stock_return_discovery_policy%ROWTYPE;
BEGIN
 SELECT * INTO STRICT policy FROM stock_return_discovery_policy WHERE request_id=NEW.policy_id FOR UPDATE;
 PERFORM 1 FROM staff_account WHERE id=policy.actor_id AND enabled AND role='MANAGER' FOR SHARE;
 IF NOT FOUND OR policy.state<>'ACTIVE' OR NEW.ticket_id<>policy.ticket_id OR NEW.release_revision<=policy.start_revision
  OR (SELECT count(*) FROM stock_return_discovery_source WHERE policy_id=policy.request_id)>=policy.max_sources
 THEN RAISE EXCEPTION 'Discovery source exceeds original authorization'; END IF;
 PERFORM 1 FROM stock_revision_origin o JOIN stock_revision_context x USING(ticket_id,to_revision)
  JOIN stock_hold_decision d ON d.intent_id=o.intent_id JOIN stock_hold_ack a ON a.intent_id=d.intent_id
  WHERE o.ticket_id=NEW.ticket_id AND o.to_revision=NEW.release_revision AND o.order_id=NEW.order_id
   AND o.kind='ORDER_RELEASE' AND d.order_id=NEW.order_id AND d.target='COMMIT' AND a.resolution='COMMITTED';
 IF NOT FOUND THEN RAISE EXCEPTION 'Exact acknowledged committed release required'; END IF;
 IF NEW.disposition='ENROLLED' THEN
  PERFORM 1 FROM stock_return_staff_command c JOIN stock_return_recovery_job j USING(request_id)
   WHERE c.request_id=NEW.return_request_id AND c.actor_id=policy.actor_id AND c.ticket_id=NEW.ticket_id
    AND c.order_id=NEW.order_id AND c.release_revision=NEW.release_revision
    AND j.actor_id=c.actor_id AND j.release_revision=c.release_revision
    AND c.xmin::text::bigint=txid_current() AND j.xmin::text::bigint=txid_current();
  IF NOT FOUND THEN RAISE EXCEPTION 'Discovery and original job must enroll in the same transaction'; END IF;
 ELSE
  PERFORM 1 FROM stock_return_staff_command WHERE order_id=NEW.order_id;
  IF NOT FOUND AND NOT EXISTS(SELECT 1 FROM stock_return_command WHERE order_id=NEW.order_id)
   THEN RAISE EXCEPTION 'Existing return evidence required'; END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER guarded_stock_return_discovery_source BEFORE INSERT ON stock_return_discovery_source
 FOR EACH ROW EXECUTE FUNCTION guard_stock_return_discovery_source();
CREATE FUNCTION final_stock_return_discovery_budget() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE policy stock_return_discovery_policy%ROWTYPE; used integer;
BEGIN
 SELECT * INTO STRICT policy FROM stock_return_discovery_policy WHERE request_id=NEW.policy_id;
 SELECT count(*) INTO used FROM stock_return_discovery_source WHERE policy_id=NEW.policy_id;
 IF used>policy.max_sources OR (used=policy.max_sources AND policy.state='ACTIVE') THEN
  RAISE EXCEPTION 'Consumed authorization must persist exhaustion';
 END IF;
 RETURN NEW;
END $$;
CREATE CONSTRAINT TRIGGER final_stock_return_discovery_budget AFTER INSERT ON stock_return_discovery_source
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION final_stock_return_discovery_budget();
CREATE TRIGGER immutable_stock_return_discovery_source BEFORE UPDATE OR DELETE ON stock_return_discovery_source
 FOR EACH ROW EXECUTE FUNCTION immutable_stock_record();
CREATE TRIGGER immutable_stock_return_discovery_stop BEFORE UPDATE OR DELETE ON stock_return_discovery_stop
 FOR EACH ROW EXECUTE FUNCTION immutable_stock_record();
CREATE TRIGGER immutable_stock_return_discovery_audit BEFORE UPDATE OR DELETE ON stock_return_discovery_audit
 FOR EACH ROW EXECUTE FUNCTION immutable_stock_record();
CREATE INDEX stock_return_discovery_active ON stock_return_discovery_policy(created_at,request_id) WHERE state='ACTIVE';
