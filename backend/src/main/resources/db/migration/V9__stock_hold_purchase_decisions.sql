-- A durable bridge; existing V8 intents/progress and legacy requests stay unchanged.
CREATE TABLE stock_hold_acceptance (
 intent_id uuid PRIMARY KEY REFERENCES stock_hold_intent(id),
 purchase_id uuid NOT NULL UNIQUE REFERENCES purchase_request(id),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 FOREIGN KEY(purchase_id) REFERENCES purchase_outbox(purchase_id)
);
CREATE TABLE stock_hold_decision (
 intent_id uuid PRIMARY KEY REFERENCES stock_hold_acceptance(intent_id),
 target varchar(8) NOT NULL CHECK(target IN ('COMMIT','RELEASE')),
 purchase_status varchar(16) NOT NULL CHECK(purchase_status IN ('RESERVED','REJECTED','CANCELLED','EXPIRED')),
 order_id uuid REFERENCES ticket_order(id),
 reason varchar(40) NOT NULL,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 CHECK((target='COMMIT' AND purchase_status='RESERVED' AND order_id IS NOT NULL)
  OR (target='RELEASE' AND purchase_status<>'RESERVED' AND order_id IS NULL))
);
CREATE TABLE stock_hold_ack (
 intent_id uuid PRIMARY KEY REFERENCES stock_hold_decision(intent_id),
 resolution varchar(12) NOT NULL CHECK(resolution IN ('COMMITTED','RELEASED','KEY_MISSING','ABSENT')),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TRIGGER immutable_stock_acceptance BEFORE UPDATE OR DELETE ON stock_hold_acceptance FOR EACH ROW EXECUTE FUNCTION immutable_stock_record();
CREATE TRIGGER immutable_stock_decision BEFORE UPDATE OR DELETE ON stock_hold_decision FOR EACH ROW EXECUTE FUNCTION immutable_stock_record();
CREATE TRIGGER immutable_stock_ack BEFORE UPDATE OR DELETE ON stock_hold_ack FOR EACH ROW EXECUTE FUNCTION immutable_stock_record();

CREATE FUNCTION validate_stock_acceptance() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE i stock_hold_intent%ROWTYPE; p purchase_request%ROWTYPE;
BEGIN
 SELECT * INTO STRICT i FROM stock_hold_intent WHERE id=NEW.intent_id;
 SELECT * INTO STRICT p FROM purchase_request WHERE id=NEW.purchase_id;
 IF (SELECT state FROM stock_hold_progress WHERE intent_id=i.id) IS DISTINCT FROM 'HELD' OR p.status<>'PROCESSING' OR
  ROW(i.customer_id,i.request_id,i.ticket_id,i.quantity,i.ticket_version,i.total_twd,i.session_version,i.session_hash,i.deadline_at)
   IS DISTINCT FROM ROW(p.customer_id,p.request_id,p.ticket_id,p.quantity,p.ticket_version,p.total_twd,p.session_version,p.session_hash,p.deadline_at)
  OR p.created_at<i.created_at THEN RAISE EXCEPTION 'Acceptance must bind the original HELD identity, quote and deadline'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER validate_stock_acceptance BEFORE INSERT ON stock_hold_acceptance FOR EACH ROW EXECUTE FUNCTION validate_stock_acceptance();

CREATE FUNCTION guard_accepted_stock_progress() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF EXISTS(SELECT 1 FROM stock_hold_acceptance WHERE intent_id=OLD.intent_id) AND NEW.state<>'HELD'
 THEN RAISE EXCEPTION 'Accepted intent requires a formal purchase decision, not unused abandonment'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER guard_accepted_stock_progress BEFORE UPDATE ON stock_hold_progress FOR EACH ROW EXECUTE FUNCTION guard_accepted_stock_progress();

CREATE FUNCTION guard_stock_purchase_identity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM stock_hold_acceptance WHERE purchase_id=OLD.id) THEN
  IF TG_OP='DELETE' THEN RETURN OLD; END IF; RETURN NEW;
 END IF;
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Accepted stock purchase cannot be deleted'; END IF;
 IF ROW(NEW.id,NEW.customer_id,NEW.request_id,NEW.ticket_id,NEW.quantity,NEW.ticket_version,NEW.total_twd,NEW.session_version,NEW.session_hash,NEW.fingerprint,NEW.created_at,NEW.deadline_at)
   IS DISTINCT FROM ROW(OLD.id,OLD.customer_id,OLD.request_id,OLD.ticket_id,OLD.quantity,OLD.ticket_version,OLD.total_twd,OLD.session_version,OLD.session_hash,OLD.fingerprint,OLD.created_at,OLD.deadline_at)
   OR (OLD.status<>'PROCESSING' AND NEW IS DISTINCT FROM OLD)
 THEN RAISE EXCEPTION 'Accepted purchase identity and terminal result are immutable'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER guard_stock_purchase_identity BEFORE UPDATE OR DELETE ON purchase_request FOR EACH ROW EXECUTE FUNCTION guard_stock_purchase_identity();

CREATE FUNCTION validate_stock_decision() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE p purchase_request%ROWTYPE; o ticket_order%ROWTYPE;
BEGIN
 SELECT r.* INTO STRICT p FROM purchase_request r JOIN stock_hold_acceptance a ON a.purchase_id=r.id WHERE a.intent_id=NEW.intent_id;
 IF p.status<>NEW.purchase_status OR NEW.reason IS DISTINCT FROM COALESCE(p.reason,'FORMAL_RESERVATION_COMMITTED')
  OR p.order_id IS DISTINCT FROM NEW.order_id THEN RAISE EXCEPTION 'Decision must match the committed purchase result'; END IF;
 IF NEW.target='COMMIT' THEN
  SELECT * INTO STRICT o FROM ticket_order WHERE id=NEW.order_id;
  IF ROW(o.customer_id,o.request_id,o.ticket_id,o.quantity,o.total_twd) IS DISTINCT FROM ROW(p.customer_id,p.request_id,p.ticket_id,p.quantity,p.total_twd)
   OR NOT EXISTS(SELECT 1 FROM order_inventory_event WHERE order_id=o.id AND ticket_id=o.ticket_id AND phase='HOLD' AND delta=o.quantity)
  THEN RAISE EXCEPTION 'COMMIT requires the original formal order and inventory event'; END IF;
 ELSE
  IF EXISTS(SELECT 1 FROM ticket_order WHERE customer_id=p.customer_id AND request_id=p.request_id)
  THEN RAISE EXCEPTION 'RELEASE cannot discard a formal order'; END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER validate_stock_decision BEFORE INSERT ON stock_hold_decision FOR EACH ROW EXECUTE FUNCTION validate_stock_decision();

CREATE FUNCTION record_stock_purchase_decision() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF OLD.status=NEW.status OR NEW.status='PROCESSING' THEN RETURN NEW; END IF;
 INSERT INTO stock_hold_decision(intent_id,target,purchase_status,order_id,reason)
 SELECT a.intent_id,CASE WHEN NEW.status='RESERVED' THEN 'COMMIT' ELSE 'RELEASE' END,NEW.status,NEW.order_id,COALESCE(NEW.reason,'FORMAL_RESERVATION_COMMITTED')
 FROM stock_hold_acceptance a WHERE a.purchase_id=NEW.id;
 RETURN NEW;
END $$;
CREATE TRIGGER stock_purchase_decision AFTER UPDATE ON purchase_request FOR EACH ROW EXECUTE FUNCTION record_stock_purchase_decision();

CREATE FUNCTION validate_stock_ack() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE decision varchar(8);
BEGIN
 SELECT target INTO STRICT decision FROM stock_hold_decision WHERE intent_id=NEW.intent_id;
 IF (decision='COMMIT' AND NEW.resolution='RELEASED') OR (decision='RELEASE' AND NEW.resolution='COMMITTED')
 THEN RAISE EXCEPTION 'Redis acknowledgement cannot contradict the durable decision'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER validate_stock_ack BEFORE INSERT ON stock_hold_ack FOR EACH ROW EXECUTE FUNCTION validate_stock_ack();
