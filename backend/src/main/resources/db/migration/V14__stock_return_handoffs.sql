-- Explicit return handoff only; no automatic consumer, re-credit or READY scheduler.
-- V13's original ORDER_RELEASE is the business effect; no historical backfill.
CREATE TABLE stock_return_command (
 request_id uuid PRIMARY KEY REFERENCES stock_rebuild_command(request_id),
 ticket_id uuid NOT NULL,
 order_id uuid NOT NULL UNIQUE REFERENCES ticket_order(id),
 intent_id uuid NOT NULL REFERENCES stock_hold_decision(intent_id),
 release_revision bigint NOT NULL,
 source_generation bigint NOT NULL CHECK(source_generation BETWEEN 1 AND 1000000000),
 generation bigint NOT NULL CHECK(generation=source_generation+1),
 available integer NOT NULL CHECK(available BETWEEN 0 AND 1000000),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(ticket_id,release_revision),
 FOREIGN KEY(ticket_id,release_revision) REFERENCES stock_revision_origin(ticket_id,to_revision),
 FOREIGN KEY(ticket_id,generation,request_id) REFERENCES stock_rebuild_command(ticket_id,generation,request_id)
);
CREATE TABLE stock_return_ack (
 request_id uuid PRIMARY KEY REFERENCES stock_return_command(request_id),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE stock_return_audit (
 request_id uuid NOT NULL REFERENCES stock_return_command(request_id),
 phase varchar(12) NOT NULL CHECK(phase IN ('REGISTERED','APPLIED')),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(request_id,phase)
);
CREATE FUNCTION validate_stock_return_command() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE origin stock_revision_origin%ROWTYPE; context stock_revision_context%ROWTYPE;
 rebuild stock_rebuild_command%ROWTYPE; effect order_inventory_event%ROWTYPE;
BEGIN
 SELECT * INTO STRICT origin FROM stock_revision_origin WHERE ticket_id=NEW.ticket_id AND to_revision=NEW.release_revision;
 SELECT * INTO STRICT context FROM stock_revision_context WHERE ticket_id=NEW.ticket_id AND to_revision=NEW.release_revision;
 SELECT * INTO STRICT rebuild FROM stock_rebuild_command WHERE request_id=NEW.request_id;
 SELECT * INTO STRICT effect FROM order_inventory_event WHERE order_id=NEW.order_id AND phase='RELEASE';
 IF origin.kind<>'ORDER_RELEASE' OR origin.order_id IS DISTINCT FROM NEW.order_id OR origin.intent_id IS DISTINCT FROM NEW.intent_id
 OR context.generation<>NEW.source_generation
 OR ROW(rebuild.ticket_id,rebuild.generation,rebuild.source_revision,rebuild.available) IS DISTINCT FROM
    ROW(NEW.ticket_id,NEW.generation,NEW.release_revision,NEW.available)
 OR (SELECT xmin FROM stock_rebuild_command WHERE request_id=NEW.request_id)<>pg_current_xact_id()::xid
 OR effect.ticket_id<>NEW.ticket_id OR effect.order_status NOT IN ('CANCELLED','EXPIRED')
 OR effect.delta<>-(SELECT quantity FROM ticket_order WHERE id=NEW.order_id)
 OR NOT EXISTS(SELECT 1 FROM stock_origin_audit WHERE ticket_id=NEW.ticket_id AND to_revision=NEW.release_revision)
 OR NOT EXISTS(SELECT 1 FROM stock_hold_intent i JOIN stock_hold_decision d ON d.intent_id=i.id
   JOIN stock_hold_ack a ON a.intent_id=i.id WHERE i.id=NEW.intent_id AND i.ticket_id=NEW.ticket_id
    AND d.target='COMMIT' AND d.order_id=NEW.order_id AND a.resolution='COMMITTED')
 OR NOT EXISTS(SELECT 1 FROM stock_projection p JOIN ticket_type t ON t.id=p.ticket_id
   WHERE p.ticket_id=NEW.ticket_id AND p.generation=NEW.generation AND p.rebuild_request_id=NEW.request_id
    AND p.source_revision=NEW.release_revision AND p.state='CLOSED' AND p.prepared_revision IS NULL
    AND t.capacity-t.held-t.sold=NEW.available)
 OR EXISTS(SELECT 1 FROM stock_hold_intent i JOIN stock_hold_progress p ON p.intent_id=i.id
   WHERE i.ticket_id=NEW.ticket_id AND p.state<>'ABORTED' AND NOT EXISTS(SELECT 1 FROM stock_hold_ack a WHERE a.intent_id=i.id))
 THEN RAISE EXCEPTION 'Return handoff requires original acknowledged COMMIT, latest RELEASE and same-transaction fresh snapshot'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER validate_stock_return BEFORE INSERT ON stock_return_command FOR EACH ROW EXECUTE FUNCTION validate_stock_return_command();
CREATE FUNCTION audit_stock_return_phase() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 INSERT INTO stock_return_audit(request_id,phase) VALUES(NEW.request_id,
  CASE WHEN TG_TABLE_NAME='stock_return_command' THEN 'REGISTERED' ELSE 'APPLIED' END);
 RETURN NEW;
END $$;
CREATE TRIGGER registered_stock_return AFTER INSERT ON stock_return_command FOR EACH ROW EXECUTE FUNCTION audit_stock_return_phase();
CREATE TRIGGER applied_stock_return AFTER INSERT ON stock_return_ack FOR EACH ROW EXECUTE FUNCTION audit_stock_return_phase();
CREATE FUNCTION guard_stock_return_ack() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM stock_return_command c JOIN stock_projection p ON p.ticket_id=c.ticket_id
  JOIN ticket_type t ON t.id=c.ticket_id WHERE c.request_id=NEW.request_id AND p.generation=c.generation
   AND p.rebuild_request_id=c.request_id AND p.state='READY' AND p.source_revision=c.release_revision
   AND p.prepared_revision=c.release_revision AND p.prepared_available=c.available AND t.capacity-t.held-t.sold=c.available)
 OR EXISTS(SELECT 1 FROM stock_return_command c JOIN stock_hold_intent i ON i.ticket_id=c.ticket_id AND i.generation=c.generation
  WHERE c.request_id=NEW.request_id)
 THEN RAISE EXCEPTION 'Return ack requires exact unused READY snapshot and original generation'; END IF;
 RETURN NEW;
END $$;
CREATE CONSTRAINT TRIGGER guarded_stock_return_ack AFTER INSERT ON stock_return_ack
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION guard_stock_return_ack();
CREATE TRIGGER immutable_stock_return_command BEFORE UPDATE OR DELETE ON stock_return_command FOR EACH ROW EXECUTE FUNCTION immutable_stock_record();
CREATE TRIGGER immutable_stock_return_ack BEFORE UPDATE OR DELETE ON stock_return_ack FOR EACH ROW EXECUTE FUNCTION immutable_stock_record();
CREATE TRIGGER immutable_stock_return_audit BEFORE UPDATE OR DELETE ON stock_return_audit FOR EACH ROW EXECUTE FUNCTION immutable_stock_record();
