-- Operator ownership and phase checkpoints; original V14 return effect remains immutable.
CREATE TABLE stock_return_staff_command (
 request_id uuid PRIMARY KEY,
 actor_id uuid NOT NULL REFERENCES staff_account(id),
 ticket_id uuid NOT NULL REFERENCES stock_projection(ticket_id),
 order_id uuid NOT NULL UNIQUE REFERENCES ticket_order(id),
 release_revision bigint NOT NULL CHECK(release_revision>0),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE stock_return_staff_progress (
 request_id uuid PRIMARY KEY REFERENCES stock_return_staff_command(request_id),
 state varchar(12) NOT NULL CHECK(state IN ('REGISTERED','CLOSED','APPLIED','BLOCKED')),
 generation bigint CHECK(generation BETWEEN 1 AND 1000000000),
 version bigint NOT NULL CHECK(version>0),
 failure varchar(48) CHECK(failure ~ '^[A-Z][A-Z0-9_]{0,47}$'),
 updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 CHECK(state NOT IN ('CLOSED','APPLIED') OR generation IS NOT NULL),
 CHECK(state<>'REGISTERED' OR generation IS NULL AND failure IS NULL),
 CHECK(state<>'APPLIED' OR failure IS NULL),
 CHECK(state<>'BLOCKED' OR failure IS NOT NULL)
);
CREATE TABLE stock_return_staff_audit (
 request_id uuid NOT NULL REFERENCES stock_return_staff_command(request_id),
 version bigint NOT NULL,
 state varchar(12) NOT NULL,
 generation bigint,
 failure varchar(48),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(request_id,version)
);
CREATE FUNCTION guard_stock_return_staff_progress() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE command stock_return_staff_command;
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Staff return progress cannot be deleted'; END IF;
 SELECT * INTO STRICT command FROM stock_return_staff_command WHERE request_id=NEW.request_id;
 PERFORM 1 FROM staff_account WHERE id=command.actor_id AND enabled AND role='MANAGER' FOR SHARE;
 IF NOT FOUND THEN RAISE EXCEPTION 'Staff return checkpoint requires current verified manager'; END IF;
 IF TG_OP='INSERT' THEN
  IF NEW.state<>'REGISTERED' OR NEW.version<>1 OR NEW.generation IS NOT NULL OR NEW.failure IS NOT NULL THEN
   RAISE EXCEPTION 'Staff return starts with original registered identity'; END IF;
 ELSE
  IF NEW.request_id<>OLD.request_id OR NEW.version<>OLD.version+1 OR OLD.state IN ('APPLIED','BLOCKED')
   OR (OLD.generation IS NOT NULL AND NEW.generation IS DISTINCT FROM OLD.generation)
   OR (OLD.state='CLOSED' AND NEW.state NOT IN ('CLOSED','APPLIED','BLOCKED'))
   OR (OLD.state='REGISTERED' AND NEW.state NOT IN ('CLOSED','BLOCKED')) THEN
   RAISE EXCEPTION 'Staff return checkpoint is fenced or terminal'; END IF;
 END IF;
 IF NEW.generation IS NOT NULL AND NOT EXISTS(SELECT 1 FROM stock_return_command c
  WHERE c.request_id=NEW.request_id AND c.ticket_id=command.ticket_id AND c.order_id=command.order_id
   AND c.release_revision=command.release_revision AND c.generation=NEW.generation) THEN
  RAISE EXCEPTION 'Staff return requires the original domain command'; END IF;
 IF NEW.state='APPLIED' AND NOT EXISTS(SELECT 1 FROM stock_return_ack WHERE request_id=NEW.request_id) THEN
  RAISE EXCEPTION 'Staff applied requires the original return acknowledgement'; END IF;
 NEW.updated_at:=clock_timestamp(); RETURN NEW;
END $$;
CREATE TRIGGER guarded_stock_return_staff_progress BEFORE INSERT OR UPDATE OR DELETE ON stock_return_staff_progress
 FOR EACH ROW EXECUTE FUNCTION guard_stock_return_staff_progress();
CREATE FUNCTION audit_stock_return_staff_progress() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 INSERT INTO stock_return_staff_audit(request_id,version,state,generation,failure,created_at)
 VALUES(NEW.request_id,NEW.version,NEW.state,NEW.generation,NEW.failure,NEW.updated_at);
 RETURN NEW;
END $$;
CREATE TRIGGER stock_return_staff_audit_writer AFTER INSERT OR UPDATE ON stock_return_staff_progress
 FOR EACH ROW EXECUTE FUNCTION audit_stock_return_staff_progress();
CREATE TRIGGER immutable_stock_return_staff_command BEFORE UPDATE OR DELETE ON stock_return_staff_command
 FOR EACH ROW EXECUTE FUNCTION immutable_stock_record();
CREATE TRIGGER immutable_stock_return_staff_audit BEFORE UPDATE OR DELETE ON stock_return_staff_audit
 FOR EACH ROW EXECUTE FUNCTION immutable_stock_record();
CREATE INDEX stock_return_staff_ticket ON stock_return_staff_command(ticket_id,created_at DESC,request_id);
