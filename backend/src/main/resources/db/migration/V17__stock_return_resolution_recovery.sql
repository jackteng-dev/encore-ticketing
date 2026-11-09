-- A fence proves no resolution was committed for this exact body, and permanently
-- rejects late/replayed submissions. UUID remains the original return UUID.
CREATE TABLE stock_return_resolution_fence (
 request_id uuid NOT NULL REFERENCES stock_return_staff_command(request_id),
 return_version bigint NOT NULL CHECK(return_version>0),
 snapshot_request_id uuid NOT NULL,
 actor_id uuid NOT NULL REFERENCES staff_account(id),
 state varchar(12) NOT NULL DEFAULT 'NOT_APPLIED' CHECK(state='NOT_APPLIED'),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(request_id,return_version,snapshot_request_id)
);
CREATE TABLE stock_return_resolution_rebind (
 request_id uuid NOT NULL,
 return_version bigint NOT NULL,
 snapshot_request_id uuid NOT NULL REFERENCES stock_rebuild_command(request_id),
 previous_snapshot_request_id uuid NOT NULL,
 actor_id uuid NOT NULL REFERENCES staff_account(id),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(request_id,return_version,snapshot_request_id),
 FOREIGN KEY(request_id,return_version,previous_snapshot_request_id)
  REFERENCES stock_return_resolution_fence(request_id,return_version,snapshot_request_id),
 CHECK(snapshot_request_id<>previous_snapshot_request_id)
);
CREATE TABLE stock_return_resolution_recovery_audit (
 request_id uuid NOT NULL REFERENCES stock_return_staff_command(request_id),
 return_version bigint NOT NULL,
 snapshot_request_id uuid NOT NULL,
 previous_snapshot_request_id uuid,
 actor_id uuid NOT NULL,
 action varchar(12) NOT NULL CHECK(action IN ('NOT_APPLIED','REBOUND')),
 created_at timestamptz NOT NULL,
 PRIMARY KEY(request_id,return_version,snapshot_request_id,action)
);
CREATE FUNCTION validate_stock_return_resolution_recovery() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE original stock_return_staff_command%ROWTYPE; phase stock_return_staff_progress%ROWTYPE;
BEGIN
 SELECT * INTO STRICT original FROM stock_return_staff_command WHERE request_id=NEW.request_id;
 PERFORM 1 FROM staff_account WHERE id=original.actor_id AND enabled AND role='MANAGER' FOR SHARE;
 IF NOT FOUND OR original.actor_id<>NEW.actor_id THEN RAISE EXCEPTION 'Recovery requires original current manager'; END IF;
 SELECT * INTO STRICT phase FROM stock_return_staff_progress WHERE request_id=NEW.request_id FOR UPDATE;
 IF phase.state<>'BLOCKED' OR phase.version<>NEW.return_version
 OR EXISTS(SELECT 1 FROM stock_return_resolution WHERE request_id=NEW.request_id)
 THEN RAISE EXCEPTION 'Recovery requires unresolved original blocked return'; END IF;
 IF TG_TABLE_NAME='stock_return_resolution_rebind' THEN
  PERFORM 1 FROM ticket_type WHERE id=original.ticket_id FOR UPDATE;
  PERFORM 1 FROM stock_projection WHERE ticket_id=original.ticket_id FOR UPDATE;
  IF EXISTS(SELECT 1 FROM stock_return_resolution_fence WHERE request_id=NEW.request_id
   AND return_version=NEW.return_version AND snapshot_request_id=NEW.snapshot_request_id)
  OR NOT EXISTS(SELECT 1 FROM stock_return_resolution_proof WHERE request_id=NEW.request_id
   AND snapshot_request_id=NEW.snapshot_request_id AND publication_xmin<>pg_current_xact_id()::xid)
  THEN RAISE EXCEPTION 'Rebind requires unfenced committed published snapshot'; END IF;
 END IF;
 NEW.created_at:=clock_timestamp();RETURN NEW;
END $$;
CREATE TRIGGER validated_resolution_fence BEFORE INSERT ON stock_return_resolution_fence
 FOR EACH ROW EXECUTE FUNCTION validate_stock_return_resolution_recovery();
CREATE TRIGGER validated_resolution_rebind BEFORE INSERT ON stock_return_resolution_rebind
 FOR EACH ROW EXECUTE FUNCTION validate_stock_return_resolution_recovery();
CREATE FUNCTION audit_stock_return_resolution_recovery() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_TABLE_NAME='stock_return_resolution_fence' THEN
  INSERT INTO stock_return_resolution_recovery_audit VALUES(NEW.request_id,NEW.return_version,
   NEW.snapshot_request_id,NULL,NEW.actor_id,'NOT_APPLIED',NEW.created_at);
 ELSE
  INSERT INTO stock_return_resolution_recovery_audit VALUES(NEW.request_id,NEW.return_version,
   NEW.snapshot_request_id,NEW.previous_snapshot_request_id,NEW.actor_id,'REBOUND',NEW.created_at);
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER audit_resolution_fence AFTER INSERT ON stock_return_resolution_fence
 FOR EACH ROW EXECUTE FUNCTION audit_stock_return_resolution_recovery();
CREATE TRIGGER audit_resolution_rebind AFTER INSERT ON stock_return_resolution_rebind
 FOR EACH ROW EXECUTE FUNCTION audit_stock_return_resolution_recovery();
CREATE FUNCTION fence_stock_return_resolution() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 -- Match the existing actor -> original progress lock order, including direct SQL.
 PERFORM 1 FROM staff_account WHERE id=NEW.actor_id FOR SHARE;
 PERFORM 1 FROM stock_return_staff_progress WHERE request_id=NEW.request_id FOR UPDATE;
 IF EXISTS(SELECT 1 FROM stock_return_resolution_fence WHERE request_id=NEW.request_id
  AND return_version=NEW.return_version AND snapshot_request_id=NEW.snapshot_request_id)
 OR (EXISTS(SELECT 1 FROM stock_return_resolution_fence WHERE request_id=NEW.request_id)
  AND NOT EXISTS(SELECT 1 FROM stock_return_resolution_rebind WHERE request_id=NEW.request_id
   AND return_version=NEW.return_version AND snapshot_request_id=NEW.snapshot_request_id))
 THEN RAISE EXCEPTION 'Fenced body or explicit rebind required'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER fenced_stock_return_resolution BEFORE INSERT ON stock_return_resolution
 FOR EACH ROW EXECUTE FUNCTION fence_stock_return_resolution();
CREATE FUNCTION require_completed_resolution_rebind() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM stock_return_resolution WHERE request_id=NEW.request_id
  AND return_version=NEW.return_version AND snapshot_request_id=NEW.snapshot_request_id AND actor_id=NEW.actor_id)
 THEN RAISE EXCEPTION 'Rebind and resolution must commit together'; END IF;
 RETURN NEW;
END $$;
CREATE CONSTRAINT TRIGGER completed_resolution_rebind AFTER INSERT ON stock_return_resolution_rebind
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION require_completed_resolution_rebind();
CREATE TRIGGER immutable_resolution_fence BEFORE UPDATE OR DELETE ON stock_return_resolution_fence
 FOR EACH ROW EXECUTE FUNCTION immutable_stock_record();
CREATE TRIGGER immutable_resolution_rebind BEFORE UPDATE OR DELETE ON stock_return_resolution_rebind
 FOR EACH ROW EXECUTE FUNCTION immutable_stock_record();
CREATE TRIGGER immutable_resolution_recovery_audit BEFORE UPDATE OR DELETE ON stock_return_resolution_recovery_audit
 FOR EACH ROW EXECUTE FUNCTION immutable_stock_record();
