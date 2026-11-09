-- Staff identity and the original maintenance request survive an unknown HTTP
-- result. Domain rebuild and audit checkpoints commit in the same PG phase.
CREATE TABLE stock_maintenance_command (
 request_id uuid PRIMARY KEY,
 actor_id uuid NOT NULL REFERENCES staff_account(id),
 ticket_id uuid NOT NULL REFERENCES stock_projection(ticket_id),
 source_revision bigint NOT NULL CHECK(source_revision>0),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TRIGGER immutable_stock_maintenance_command BEFORE UPDATE OR DELETE ON stock_maintenance_command
 FOR EACH ROW EXECUTE FUNCTION immutable_stock_record();
CREATE TABLE stock_maintenance_progress (
 request_id uuid PRIMARY KEY REFERENCES stock_maintenance_command(request_id),
 state varchar(12) NOT NULL CHECK(state IN ('REGISTERED','CLOSED','READY','DIRTY','BLOCKED')),
 generation bigint CHECK(generation BETWEEN 1 AND 1000000000),
 version bigint NOT NULL CHECK(version>0),
 failure varchar(48) CHECK(failure ~ '^[A-Z][A-Z0-9_]{0,47}$'),
 updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 CHECK(state NOT IN ('CLOSED','READY','DIRTY') OR generation IS NOT NULL),
 CHECK(state<>'REGISTERED' OR generation IS NULL),
 CHECK(state<>'READY' OR failure IS NULL)
);
CREATE TABLE stock_maintenance_audit (
 request_id uuid NOT NULL REFERENCES stock_maintenance_command(request_id),
 version bigint NOT NULL,
 state varchar(12) NOT NULL,
 generation bigint,
 failure varchar(48),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(request_id,version)
);
CREATE TRIGGER immutable_stock_maintenance_audit BEFORE UPDATE OR DELETE ON stock_maintenance_audit
 FOR EACH ROW EXECUTE FUNCTION immutable_stock_record();
CREATE FUNCTION guard_stock_maintenance_progress() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE command stock_maintenance_command;
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Maintenance progress cannot be deleted'; END IF;
 SELECT * INTO STRICT command FROM stock_maintenance_command WHERE request_id=NEW.request_id;
 IF TG_OP='INSERT' THEN
  IF NEW.state<>'REGISTERED' OR NEW.version<>1 OR NEW.generation IS NOT NULL OR NEW.failure IS NOT NULL THEN
   RAISE EXCEPTION 'Maintenance starts with registered identity';
  END IF;
 ELSE
  IF NEW.request_id<>OLD.request_id OR NEW.version<>OLD.version+1 OR OLD.state IN ('READY','DIRTY','BLOCKED')
    OR (OLD.generation IS NOT NULL AND NEW.generation IS DISTINCT FROM OLD.generation)
    OR (OLD.state='CLOSED' AND NEW.state NOT IN ('CLOSED','READY','DIRTY','BLOCKED'))
    OR (OLD.state='REGISTERED' AND NEW.state NOT IN ('CLOSED','BLOCKED')) THEN
   RAISE EXCEPTION 'Maintenance checkpoint is fenced or terminal';
  END IF;
 END IF;
 IF NEW.generation IS NOT NULL AND NOT EXISTS(SELECT 1 FROM stock_rebuild_command b
   WHERE b.request_id=NEW.request_id AND b.ticket_id=command.ticket_id
   AND b.source_revision=command.source_revision AND b.generation=NEW.generation) THEN
  RAISE EXCEPTION 'Maintenance requires original domain rebuild evidence';
 END IF;
 IF NEW.state='READY' AND NOT EXISTS(SELECT 1 FROM stock_projection p WHERE p.ticket_id=command.ticket_id
   AND p.state='READY' AND p.rebuild_request_id=NEW.request_id AND p.generation=NEW.generation
   AND p.prepared_revision=command.source_revision) THEN
  RAISE EXCEPTION 'Maintenance ready requires a committed matching projection';
 END IF;
 IF NEW.state='DIRTY' AND NOT EXISTS(SELECT 1 FROM stock_projection p WHERE p.ticket_id=command.ticket_id
   AND p.state='DIRTY' AND p.rebuild_request_id=NEW.request_id AND p.generation=NEW.generation) THEN
  RAISE EXCEPTION 'Dirty checkpoint requires a matching closed gate';
 END IF;
 NEW.updated_at:=clock_timestamp();RETURN NEW;
END $$;
CREATE TRIGGER guarded_stock_maintenance_progress BEFORE INSERT OR UPDATE OR DELETE ON stock_maintenance_progress
 FOR EACH ROW EXECUTE FUNCTION guard_stock_maintenance_progress();
CREATE FUNCTION record_stock_maintenance_audit() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 INSERT INTO stock_maintenance_audit(request_id,version,state,generation,failure,created_at)
 VALUES(NEW.request_id,NEW.version,NEW.state,NEW.generation,NEW.failure,NEW.updated_at);
 RETURN NEW;
END $$;
CREATE TRIGGER stock_maintenance_audit_writer AFTER INSERT OR UPDATE ON stock_maintenance_progress
 FOR EACH ROW EXECUTE FUNCTION record_stock_maintenance_audit();
CREATE INDEX stock_maintenance_ticket ON stock_maintenance_command(ticket_id,created_at DESC,request_id);
