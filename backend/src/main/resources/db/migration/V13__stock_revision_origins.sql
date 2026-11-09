-- C2 records causality; it does NOT consume Redis journals or publish READY.
-- Existing revisions remain unclassified historical evidence, never backfilled.
CREATE TABLE stock_origin_boundary (
 ticket_id uuid PRIMARY KEY REFERENCES ticket_type(id),
 through_revision bigint NOT NULL,
 FOREIGN KEY(ticket_id,through_revision) REFERENCES stock_revision(ticket_id,revision)
);
INSERT INTO stock_origin_boundary SELECT ticket_id,source_revision FROM stock_projection;
CREATE TRIGGER sealed_origin_boundary BEFORE INSERT ON stock_origin_boundary FOR EACH ROW EXECUTE FUNCTION immutable_stock_record();

CREATE TABLE stock_revision_context (
 ticket_id uuid NOT NULL,
 to_revision bigint NOT NULL,
 from_revision bigint,
 generation bigint NOT NULL CHECK(generation BETWEEN 0 AND 1000000000),
 old_ticket jsonb,
 new_ticket jsonb NOT NULL,
 transaction_id xid8 NOT NULL DEFAULT pg_current_xact_id(),
 PRIMARY KEY(ticket_id,to_revision),
 FOREIGN KEY(ticket_id,to_revision) REFERENCES stock_revision(ticket_id,revision),
 FOREIGN KEY(ticket_id,from_revision) REFERENCES stock_revision(ticket_id,revision),
 CHECK(((to_revision=1 AND from_revision IS NULL AND old_ticket IS NULL)
  OR (to_revision>1 AND from_revision=to_revision-1 AND old_ticket IS NOT NULL)) IS TRUE),
 CHECK((new_ticket->>'id'=ticket_id::text) IS TRUE)
);
CREATE TABLE stock_revision_origin (
 ticket_id uuid NOT NULL,
 to_revision bigint NOT NULL,
 kind varchar(20) NOT NULL CHECK(kind IN ('CATALOG_CREATE','CATALOG_UPDATE','ORDER_HOLD','ORDER_RELEASE','PAYMENT','UNATTRIBUTED')),
 order_id uuid,
 order_phase varchar(12),
 payment_event_id uuid UNIQUE,
 catalog_request_id uuid UNIQUE REFERENCES catalog_command(request_id),
 intent_id uuid REFERENCES stock_hold_decision(intent_id) DEFERRABLE INITIALLY DEFERRED,
 PRIMARY KEY(ticket_id,to_revision),
 FOREIGN KEY(ticket_id,to_revision) REFERENCES stock_revision_context(ticket_id,to_revision),
 FOREIGN KEY(order_id,order_phase) REFERENCES order_inventory_event(order_id,phase),
 FOREIGN KEY(payment_event_id) REFERENCES payment_inventory_event(event_id),
 UNIQUE(order_id,order_phase),
 CHECK(((kind IN ('CATALOG_CREATE','CATALOG_UPDATE') AND catalog_request_id IS NOT NULL AND order_id IS NULL AND order_phase IS NULL AND payment_event_id IS NULL AND intent_id IS NULL)
  OR (kind IN ('ORDER_HOLD','ORDER_RELEASE') AND order_id IS NOT NULL AND order_phase=CASE WHEN kind='ORDER_HOLD' THEN 'HOLD' ELSE 'RELEASE' END AND payment_event_id IS NULL AND catalog_request_id IS NULL)
  OR (kind='PAYMENT' AND order_id IS NOT NULL AND order_phase IS NULL AND payment_event_id IS NOT NULL AND catalog_request_id IS NULL)
  OR (kind='UNATTRIBUTED' AND order_id IS NULL AND order_phase IS NULL AND payment_event_id IS NULL AND catalog_request_id IS NULL AND intent_id IS NULL)) IS TRUE)
);
CREATE TABLE stock_origin_audit (
 ticket_id uuid NOT NULL,
 to_revision bigint NOT NULL,
 recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(ticket_id,to_revision),
 FOREIGN KEY(ticket_id,to_revision) REFERENCES stock_revision_origin(ticket_id,to_revision)
);
CREATE TRIGGER immutable_origin_boundary BEFORE UPDATE OR DELETE ON stock_origin_boundary FOR EACH ROW EXECUTE FUNCTION immutable_stock_record();
CREATE TRIGGER immutable_revision_context BEFORE UPDATE OR DELETE ON stock_revision_context FOR EACH ROW EXECUTE FUNCTION immutable_stock_record();
CREATE TRIGGER immutable_revision_origin BEFORE UPDATE OR DELETE ON stock_revision_origin FOR EACH ROW EXECUTE FUNCTION immutable_stock_record();
CREATE TRIGGER immutable_origin_audit BEFORE UPDATE OR DELETE ON stock_origin_audit FOR EACH ROW EXECUTE FUNCTION immutable_stock_record();

CREATE FUNCTION capture_stock_origin_context() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE p stock_projection%ROWTYPE;
BEGIN
 IF TG_OP='UPDATE' AND ROW(OLD.capacity,OLD.held,OLD.sold,OLD.version)=ROW(NEW.capacity,NEW.held,NEW.sold,NEW.version) THEN
  IF ROW(OLD.session_id,OLD.name,OLD.price_twd,OLD.max_per_customer) IS DISTINCT FROM ROW(NEW.session_id,NEW.name,NEW.price_twd,NEW.max_per_customer)
  THEN RAISE EXCEPTION 'Unversioned ticket terms cannot bypass stock journal'; END IF;
  RETURN NEW;
 END IF;
 SELECT * INTO STRICT p FROM stock_projection WHERE ticket_id=NEW.id;
 INSERT INTO stock_revision_context(ticket_id,to_revision,from_revision,generation,old_ticket,new_ticket)
 VALUES(NEW.id,p.source_revision,CASE WHEN TG_OP='INSERT' THEN NULL ELSE p.source_revision-1 END,
  p.generation,CASE WHEN TG_OP='INSERT' THEN NULL ELSE to_jsonb(OLD) END,to_jsonb(NEW));
 RETURN NEW;
END $$;
-- PG executes same-kind triggers by name: after the unchanged V7 journal writer.
CREATE TRIGGER zz_stock_origin_context AFTER INSERT OR UPDATE ON ticket_type
 FOR EACH ROW EXECUTE FUNCTION capture_stock_origin_context();

CREATE FUNCTION validate_stock_origin_context() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE b stock_revision%ROWTYPE; a stock_revision%ROWTYPE;
BEGIN
 SELECT * INTO STRICT a FROM stock_revision WHERE ticket_id=NEW.ticket_id AND revision=NEW.to_revision;
 IF NEW.transaction_id<>pg_current_xact_id()
  OR (SELECT xmin FROM stock_revision WHERE ticket_id=NEW.ticket_id AND revision=NEW.to_revision)<>pg_current_xact_id()::xid
  OR ROW(a.capacity,a.held,a.sold,a.ticket_version) IS DISTINCT FROM ROW(
    (NEW.new_ticket->>'capacity')::integer,(NEW.new_ticket->>'held')::integer,(NEW.new_ticket->>'sold')::integer,(NEW.new_ticket->>'version')::bigint)
 THEN RAISE EXCEPTION 'Context requires the exact new same-transaction stock revision'; END IF;
 IF NEW.from_revision IS NOT NULL THEN
  SELECT * INTO STRICT b FROM stock_revision WHERE ticket_id=NEW.ticket_id AND revision=NEW.from_revision;
  IF (NEW.old_ticket->>'id') IS DISTINCT FROM NEW.ticket_id::text OR ROW(b.capacity,b.held,b.sold,b.ticket_version) IS DISTINCT FROM ROW(
    (NEW.old_ticket->>'capacity')::integer,(NEW.old_ticket->>'held')::integer,(NEW.old_ticket->>'sold')::integer,(NEW.old_ticket->>'version')::bigint)
  THEN RAISE EXCEPTION 'Context must preserve the exact previous stock image'; END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER validate_origin_context BEFORE INSERT ON stock_revision_context FOR EACH ROW EXECUTE FUNCTION validate_stock_origin_context();

CREATE FUNCTION origin_for_business_event() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE revision bigint; claim uuid;
BEGIN
 SELECT source_revision INTO STRICT revision FROM stock_projection WHERE ticket_id=NEW.ticket_id;
 SELECT i.id INTO claim FROM stock_hold_intent i JOIN stock_hold_acceptance a ON a.intent_id=i.id
  JOIN ticket_order o ON o.customer_id=i.customer_id AND o.request_id=i.request_id AND o.ticket_id=i.ticket_id AND o.quantity=i.quantity
  WHERE o.id=NEW.order_id;
 IF TG_TABLE_NAME='order_inventory_event' THEN
  INSERT INTO stock_revision_origin(ticket_id,to_revision,kind,order_id,order_phase,intent_id)
  VALUES(NEW.ticket_id,revision,CASE WHEN NEW.phase='HOLD' THEN 'ORDER_HOLD' ELSE 'ORDER_RELEASE' END,NEW.order_id,NEW.phase,claim);
 ELSE
  INSERT INTO stock_revision_origin(ticket_id,to_revision,kind,order_id,payment_event_id,intent_id)
  VALUES(NEW.ticket_id,revision,'PAYMENT',NEW.order_id,NEW.event_id,claim);
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER origin_for_order_event AFTER INSERT ON order_inventory_event FOR EACH ROW EXECUTE FUNCTION origin_for_business_event();
CREATE TRIGGER origin_for_payment_event AFTER INSERT ON payment_inventory_event FOR EACH ROW EXECUTE FUNCTION origin_for_business_event();
CREATE FUNCTION origin_for_catalog_command() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE revision bigint;
BEGIN
 IF NEW.action NOT IN ('TICKET_CREATE','TICKET_UPDATE') THEN RETURN NEW; END IF;
 SELECT source_revision INTO STRICT revision FROM stock_projection WHERE ticket_id=NEW.resource_id;
 INSERT INTO stock_revision_origin(ticket_id,to_revision,kind,catalog_request_id)
 VALUES(NEW.resource_id,revision,CASE WHEN NEW.action='TICKET_CREATE' THEN 'CATALOG_CREATE' ELSE 'CATALOG_UPDATE' END,NEW.request_id);
 RETURN NEW;
END $$;
CREATE TRIGGER origin_for_catalog AFTER INSERT ON catalog_command FOR EACH ROW EXECUTE FUNCTION origin_for_catalog_command();

CREATE FUNCTION validate_stock_revision_origin() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE c stock_revision_context%ROWTYPE; b jsonb; a jsonb;
 e order_inventory_event%ROWTYPE; pay payment_inventory_event%ROWTYPE; cmd catalog_command%ROWTYPE;
BEGIN
 SELECT * INTO STRICT c FROM stock_revision_context WHERE ticket_id=NEW.ticket_id AND to_revision=NEW.to_revision;
 b:=c.old_ticket; a:=c.new_ticket;
 IF c.transaction_id<>pg_current_xact_id() THEN RAISE EXCEPTION 'No retroactive attribution of journal history'; END IF;
 IF NEW.kind='UNATTRIBUTED' THEN RETURN NEW; END IF;
 IF NEW.kind IN ('ORDER_HOLD','ORDER_RELEASE','PAYMENT') THEN
  IF b IS NULL OR (a-'held'-'sold') IS DISTINCT FROM (b-'held'-'sold')
  THEN RAISE EXCEPTION 'Inventory event cannot change ticket terms or identity'; END IF;
  IF NEW.kind='PAYMENT' THEN
   SELECT * INTO STRICT pay FROM payment_inventory_event WHERE event_id=NEW.payment_event_id;
   IF pay.order_id<>NEW.order_id OR pay.ticket_id<>NEW.ticket_id
    OR pay.quantity<>(SELECT quantity FROM ticket_order WHERE id=NEW.order_id)
    OR ROW(pay.held_before,pay.held_after,pay.sold_before,pay.sold_after) IS DISTINCT FROM ROW(
      (b->>'held')::integer,(a->>'held')::integer,(b->>'sold')::integer,(a->>'sold')::integer)
    OR (SELECT xmin FROM payment_inventory_event WHERE event_id=NEW.payment_event_id)<>pg_current_xact_id()::xid
   THEN RAISE EXCEPTION 'Payment origin must bind its exact same-transaction conversion'; END IF;
  ELSE
   SELECT * INTO STRICT e FROM order_inventory_event WHERE order_id=NEW.order_id AND phase=NEW.order_phase;
   IF e.ticket_id<>NEW.ticket_id OR (a->>'sold')::integer<>(b->>'sold')::integer
    OR ROW(e.held_before,e.held_after) IS DISTINCT FROM ROW((b->>'held')::integer,(a->>'held')::integer)
    OR abs(e.delta)<>(SELECT quantity FROM ticket_order WHERE id=e.order_id)
    OR (SELECT xmin FROM order_inventory_event WHERE order_id=e.order_id AND phase=e.phase)<>pg_current_xact_id()::xid
   THEN RAISE EXCEPTION 'Order origin must bind its exact same-transaction HOLD or RELEASE'; END IF;
  END IF;
 ELSE
  SELECT * INTO STRICT cmd FROM catalog_command WHERE request_id=NEW.catalog_request_id;
  IF cmd.resource_id<>NEW.ticket_id OR cmd.action<>(CASE WHEN NEW.kind='CATALOG_CREATE' THEN 'TICKET_CREATE' ELSE 'TICKET_UPDATE' END)
   OR (SELECT xmin FROM catalog_command WHERE request_id=cmd.request_id)<>pg_current_xact_id()::xid
   OR ROW(cmd.result->>'id',cmd.result->>'name',(cmd.result->>'priceTwd')::integer,(cmd.result->>'capacity')::integer,
          (cmd.result->>'maxPerCustomer')::integer,(cmd.result->>'version')::bigint,(cmd.result->>'available')::integer)
      IS DISTINCT FROM ROW(a->>'id',a->>'name',(a->>'price_twd')::integer,(a->>'capacity')::integer,
          (a->>'max_per_customer')::integer,(a->>'version')::bigint,(a->>'capacity')::integer-(a->>'held')::integer-(a->>'sold')::integer)
   OR (NEW.kind='CATALOG_CREATE' AND (b IS NOT NULL OR (a->>'held')::integer<>0 OR (a->>'sold')::integer<>0))
   OR (NEW.kind='CATALOG_UPDATE' AND (b IS NULL OR (a->>'held')::integer<>(b->>'held')::integer OR (a->>'sold')::integer<>(b->>'sold')::integer
       OR a->>'id'<>b->>'id' OR a->>'session_id'<>b->>'session_id' OR (a->>'version')::bigint<>(b->>'version')::bigint+1))
  THEN RAISE EXCEPTION 'Catalog origin must bind the exact verified original command result'; END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER validate_revision_origin BEFORE INSERT ON stock_revision_origin FOR EACH ROW EXECUTE FUNCTION validate_stock_revision_origin();
CREATE FUNCTION finish_stock_origin_evidence() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.intent_id IS NOT NULL AND NOT EXISTS(
  SELECT 1 FROM stock_hold_intent i JOIN stock_hold_acceptance a ON a.intent_id=i.id
  JOIN stock_hold_decision d ON d.intent_id=i.id AND d.target='COMMIT' AND d.order_id=NEW.order_id
  JOIN ticket_order o ON o.id=d.order_id
  WHERE i.id=NEW.intent_id AND i.ticket_id=NEW.ticket_id AND i.customer_id=o.customer_id
   AND i.request_id=o.request_id AND i.quantity=o.quantity AND a.purchase_id=(SELECT id FROM purchase_request WHERE id=a.purchase_id AND order_id=o.id)
   AND (NEW.kind<>'ORDER_HOLD' OR d.xmin=pg_current_xact_id()::xid))
 THEN RAISE EXCEPTION 'Accepted origin requires the original formal COMMIT evidence'; END IF;
 IF NEW.kind='PAYMENT' AND NOT EXISTS(SELECT 1 FROM payment_event e WHERE e.event_id=NEW.payment_event_id
  AND e.order_id=NEW.order_id AND e.decision='SUCCEEDED' AND e.xmin=pg_current_xact_id()::xid)
 THEN RAISE EXCEPTION 'Payment origin requires its same-transaction successful event'; END IF;
 RETURN NEW;
END $$;
CREATE CONSTRAINT TRIGGER finish_origin_evidence AFTER INSERT ON stock_revision_origin
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION finish_stock_origin_evidence();
CREATE FUNCTION audit_stock_origin() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN INSERT INTO stock_origin_audit(ticket_id,to_revision) VALUES(NEW.ticket_id,NEW.to_revision); RETURN NEW; END $$;
CREATE TRIGGER audit_revision_origin AFTER INSERT ON stock_revision_origin FOR EACH ROW EXECUTE FUNCTION audit_stock_origin();
CREATE FUNCTION finalize_unattributed_stock_revision() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM stock_revision_origin WHERE ticket_id=NEW.ticket_id AND to_revision=NEW.to_revision) THEN
  INSERT INTO stock_revision_origin(ticket_id,to_revision,kind) VALUES(NEW.ticket_id,NEW.to_revision,'UNATTRIBUTED');
 END IF;
 RETURN NEW;
END $$;
CREATE CONSTRAINT TRIGGER finalize_stock_origin AFTER INSERT ON stock_revision_context
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION finalize_unattributed_stock_revision();

-- New origins cannot be undermined by subsequently editing their original causes.
CREATE FUNCTION guard_linked_stock_cause() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF (TG_TABLE_NAME='order_inventory_event' AND EXISTS(SELECT 1 FROM stock_revision_origin WHERE order_id=OLD.order_id AND order_phase=OLD.phase))
 THEN RAISE EXCEPTION 'Linked stock cause is immutable'; END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; END IF; RETURN NEW;
END $$;
CREATE TRIGGER guard_origin_order_cause BEFORE UPDATE OR DELETE ON order_inventory_event FOR EACH ROW EXECUTE FUNCTION guard_linked_stock_cause();
CREATE FUNCTION guard_linked_payment_cause() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF EXISTS(SELECT 1 FROM stock_revision_origin WHERE payment_event_id=OLD.event_id)
 THEN RAISE EXCEPTION 'Linked payment stock cause is immutable'; END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; END IF; RETURN NEW;
END $$;
CREATE TRIGGER guard_origin_payment_cause BEFORE UPDATE OR DELETE ON payment_inventory_event FOR EACH ROW EXECUTE FUNCTION guard_linked_payment_cause();
CREATE TRIGGER guard_origin_payment_decision BEFORE UPDATE OR DELETE ON payment_event FOR EACH ROW EXECUTE FUNCTION guard_linked_payment_cause();
CREATE FUNCTION guard_linked_catalog_cause() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF EXISTS(SELECT 1 FROM stock_revision_origin WHERE catalog_request_id=OLD.request_id)
 THEN RAISE EXCEPTION 'Linked catalog stock cause is immutable'; END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; END IF; RETURN NEW;
END $$;
CREATE TRIGGER guard_origin_catalog_cause BEFORE UPDATE OR DELETE ON catalog_command FOR EACH ROW EXECUTE FUNCTION guard_linked_catalog_cause();
CREATE FUNCTION guard_origin_order_identity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF EXISTS(SELECT 1 FROM stock_revision_origin WHERE order_id=OLD.id) AND
  (TG_OP='DELETE' OR ROW(NEW.id,NEW.customer_id,NEW.request_id,NEW.ticket_id,NEW.quantity,NEW.price_twd,NEW.total_twd)
   IS DISTINCT FROM ROW(OLD.id,OLD.customer_id,OLD.request_id,OLD.ticket_id,OLD.quantity,OLD.price_twd,OLD.total_twd))
 THEN RAISE EXCEPTION 'Linked stock order identity is immutable'; END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; END IF; RETURN NEW;
END $$;
CREATE TRIGGER guard_origin_order_identity BEFORE UPDATE OR DELETE ON ticket_order FOR EACH ROW EXECUTE FUNCTION guard_origin_order_identity();
