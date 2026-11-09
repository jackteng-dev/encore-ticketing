-- M4D2C1 experiment ONLY. Not a Flyway migration or a production schema.
CREATE SCHEMA stock_commit_experiment;
CREATE TABLE stock_commit_experiment.command (
 request_id uuid PRIMARY KEY,
 ticket_id uuid NOT NULL REFERENCES ticket_type(id),
 generation bigint NOT NULL,
 from_revision bigint NOT NULL,
 to_revision bigint NOT NULL CHECK(to_revision=from_revision+1),
 intent_id uuid NOT NULL UNIQUE REFERENCES stock_hold_decision(intent_id),
 order_id uuid NOT NULL REFERENCES ticket_order(id),
 owner_id uuid NOT NULL REFERENCES customer_account(id),
 quantity integer NOT NULL CHECK(quantity BETWEEN 1 AND 10),
 deadline_ms bigint NOT NULL,
 fingerprint char(64) NOT NULL CHECK(fingerprint ~ '^[0-9a-f]{64}$'),
 baseline integer NOT NULL CHECK(baseline BETWEEN 1 AND 1000000),
 UNIQUE(ticket_id,generation,to_revision),
 FOREIGN KEY(ticket_id,from_revision) REFERENCES stock_revision(ticket_id,revision),
 FOREIGN KEY(ticket_id,to_revision) REFERENCES stock_revision(ticket_id,revision)
);
CREATE TABLE stock_commit_experiment.cursor (
 ticket_id uuid NOT NULL,
 generation bigint NOT NULL,
 revision bigint NOT NULL,
 last_request uuid REFERENCES stock_commit_experiment.command(request_id),
 PRIMARY KEY(ticket_id,generation),
 FOREIGN KEY(ticket_id,revision) REFERENCES stock_revision(ticket_id,revision)
);
CREATE TABLE stock_commit_experiment.ack (
 request_id uuid PRIMARY KEY REFERENCES stock_commit_experiment.command(request_id),
 receipt_sha char(64) NOT NULL CHECK(receipt_sha ~ '^[0-9a-f]{64}$')
);
CREATE TABLE stock_commit_experiment.audit (
 request_id uuid NOT NULL REFERENCES stock_commit_experiment.command(request_id),
 phase varchar(12) NOT NULL CHECK(phase IN ('REGISTERED','APPLIED')),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(request_id,phase)
);
CREATE FUNCTION stock_commit_experiment.validate_command() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE i stock_hold_intent%ROWTYPE; d stock_hold_decision%ROWTYPE;
 b stock_revision%ROWTYPE; a stock_revision%ROWTYPE;
BEGIN
 SELECT * INTO STRICT i FROM stock_hold_intent WHERE id=NEW.intent_id;
 SELECT * INTO STRICT d FROM stock_hold_decision WHERE intent_id=i.id;
 SELECT * INTO STRICT b FROM stock_revision WHERE ticket_id=NEW.ticket_id AND revision=NEW.from_revision;
 SELECT * INTO STRICT a FROM stock_revision WHERE ticket_id=NEW.ticket_id AND revision=NEW.to_revision;
 -- New provenance must be in the SAME transaction as the actual formal worker
 -- decision and stock journal row. Never infer attribution from time or quantity.
 IF (SELECT xmin FROM stock_hold_decision WHERE intent_id=i.id)<>txid_current()::text::xid
  OR (SELECT xmin FROM stock_revision WHERE ticket_id=NEW.ticket_id AND revision=NEW.to_revision)<>txid_current()::text::xid
  OR d.target<>'COMMIT' OR d.order_id<>NEW.order_id
  OR ROW(i.ticket_id,i.generation,i.source_revision,i.customer_id,i.quantity,i.claim_fingerprint)
    IS DISTINCT FROM ROW(NEW.ticket_id,NEW.generation,NEW.from_revision,NEW.owner_id,NEW.quantity,NEW.fingerprint)
  OR floor(extract(epoch FROM i.deadline_at)*1000)::bigint<>NEW.deadline_ms
  OR b.capacity<>NEW.baseline OR b.held<>0 OR b.sold<>0
  OR ROW(a.capacity,a.held,a.sold,a.ticket_version)
    IS DISTINCT FROM ROW(b.capacity,b.held+NEW.quantity,b.sold,b.ticket_version)
  OR NOT EXISTS(SELECT 1 FROM stock_projection WHERE ticket_id=i.ticket_id AND generation=i.generation
     AND source_revision=NEW.to_revision AND prepared_revision=NEW.from_revision AND state='DIRTY')
  OR NOT EXISTS(SELECT 1 FROM order_inventory_event WHERE order_id=d.order_id AND phase='HOLD'
     AND ticket_id=i.ticket_id AND delta=i.quantity AND held_before=b.held AND held_after=a.held)
 THEN RAISE EXCEPTION 'Experiment requires exact same-transaction single COMMIT provenance'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER validate_command BEFORE INSERT ON stock_commit_experiment.command
 FOR EACH ROW EXECUTE FUNCTION stock_commit_experiment.validate_command();
CREATE FUNCTION stock_commit_experiment.audit_phase() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 INSERT INTO stock_commit_experiment.audit(request_id,phase)
 VALUES(NEW.request_id,CASE WHEN TG_TABLE_NAME='command' THEN 'REGISTERED' ELSE 'APPLIED' END);
 RETURN NEW;
END $$;
CREATE TRIGGER registered AFTER INSERT ON stock_commit_experiment.command
 FOR EACH ROW EXECUTE FUNCTION stock_commit_experiment.audit_phase();
CREATE TRIGGER applied AFTER INSERT ON stock_commit_experiment.ack
 FOR EACH ROW EXECUTE FUNCTION stock_commit_experiment.audit_phase();
CREATE FUNCTION stock_commit_experiment.guard_cursor() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Experiment cursor cannot be deleted'; END IF;
 IF TG_OP='INSERT' THEN
  IF NEW.last_request IS NOT NULL OR NOT EXISTS(SELECT 1 FROM stock_projection p
   JOIN stock_rebuild_command c ON c.request_id=p.rebuild_request_id
   WHERE p.ticket_id=NEW.ticket_id AND p.generation=NEW.generation AND p.state='READY'
    AND p.source_revision=NEW.revision AND c.source_revision=NEW.revision)
   OR EXISTS(SELECT 1 FROM stock_hold_intent WHERE ticket_id=NEW.ticket_id AND generation=NEW.generation)
  THEN RAISE EXCEPTION 'Experiment seed requires a verified unused READY rebuild'; END IF;
 ELSE
  IF ROW(NEW.ticket_id,NEW.generation) IS DISTINCT FROM ROW(OLD.ticket_id,OLD.generation)
   OR NEW.revision<>OLD.revision+1 OR NOT EXISTS(SELECT 1 FROM stock_commit_experiment.command c
    JOIN stock_commit_experiment.ack a USING(request_id)
    WHERE c.request_id=NEW.last_request AND c.ticket_id=NEW.ticket_id AND c.generation=NEW.generation
     AND c.from_revision=OLD.revision AND c.to_revision=NEW.revision)
  THEN RAISE EXCEPTION 'Experiment cursor requires the original acknowledged next command'; END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER guarded_cursor BEFORE INSERT OR UPDATE OR DELETE ON stock_commit_experiment.cursor
 FOR EACH ROW EXECUTE FUNCTION stock_commit_experiment.guard_cursor();
CREATE TRIGGER immutable_command BEFORE UPDATE OR DELETE ON stock_commit_experiment.command
 FOR EACH ROW EXECUTE FUNCTION immutable_stock_record();
CREATE TRIGGER immutable_ack BEFORE UPDATE OR DELETE ON stock_commit_experiment.ack
 FOR EACH ROW EXECUTE FUNCTION immutable_stock_record();
CREATE TRIGGER immutable_audit BEFORE UPDATE OR DELETE ON stock_commit_experiment.audit
 FOR EACH ROW EXECUTE FUNCTION immutable_stock_record();
CREATE FUNCTION stock_commit_experiment.guard_ack_checkpoint() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM stock_commit_experiment.command c
  JOIN stock_hold_decision d ON d.intent_id=c.intent_id AND d.target='COMMIT' AND d.order_id=c.order_id
  JOIN stock_hold_ack a ON a.intent_id=c.intent_id AND a.resolution='COMMITTED'
  JOIN stock_commit_experiment.cursor x ON x.ticket_id=c.ticket_id AND x.generation=c.generation
  WHERE c.request_id=NEW.request_id AND x.revision=c.to_revision AND x.last_request=c.request_id)
 THEN RAISE EXCEPTION 'Experiment ack must commit with the exact formal evidence and cursor'; END IF;
 RETURN NEW;
END $$;
CREATE CONSTRAINT TRIGGER guarded_ack_checkpoint AFTER INSERT ON stock_commit_experiment.ack
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION stock_commit_experiment.guard_ack_checkpoint();
