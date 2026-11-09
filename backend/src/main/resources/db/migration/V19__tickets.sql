-- Paid-order issuance is explicit. No backfill, refund, inventory or Redis write.
ALTER TABLE order_command DROP CONSTRAINT order_action;
ALTER TABLE order_command ADD CONSTRAINT order_action CHECK(action IN ('PLACE','CANCEL','PAY','RECONCILE','ISSUE'));
CREATE TABLE ticket_issuance (
 order_id uuid PRIMARY KEY REFERENCES ticket_order(id),
 customer_id uuid NOT NULL,
 request_id uuid NOT NULL,
 order_version bigint NOT NULL CHECK(order_version>=3),
 quantity integer NOT NULL CHECK(quantity BETWEEN 1 AND 10),
 payment_event_id uuid NOT NULL UNIQUE,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(customer_id,request_id),
 FOREIGN KEY(order_id,customer_id) REFERENCES ticket_order(id,customer_id),
 FOREIGN KEY(order_id,customer_id,request_id) REFERENCES order_command(order_id,customer_id,request_id),
 FOREIGN KEY(payment_event_id,order_id) REFERENCES payment_event(event_id,order_id)
);
CREATE TABLE issued_ticket (
 id uuid PRIMARY KEY,
 order_id uuid NOT NULL REFERENCES ticket_issuance(order_id),
 unit integer NOT NULL CHECK(unit BETWEEN 1 AND 10),
 code varchar(43) NOT NULL UNIQUE CHECK(code ~ '^[A-Za-z0-9_-]{43}$'),
 state varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK(state IN ('ACTIVE','CHECKED_IN','REVOKED')),
 version bigint NOT NULL DEFAULT 1,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 changed_at timestamptz,
 changed_request_id uuid,
 UNIQUE(order_id,unit),
 CHECK((state='ACTIVE' AND version=1 AND changed_at IS NULL AND changed_request_id IS NULL) OR
       (state<>'ACTIVE' AND version=2 AND changed_at>=created_at AND changed_request_id IS NOT NULL))
);
CREATE TABLE ticket_admission_command (
 request_id uuid PRIMARY KEY,
 actor_id uuid NOT NULL REFERENCES staff_account(id),
 ticket_id uuid NOT NULL UNIQUE REFERENCES issued_ticket(id),
 session_id uuid NOT NULL REFERENCES event_session(id),
 expected_version bigint NOT NULL CHECK(expected_version=1),
 action varchar(12) NOT NULL CHECK(action IN ('CHECK_IN','REVOKE')),
 reason varchar(24),
 fingerprint char(64) NOT NULL CHECK(fingerprint ~ '^[0-9a-f]{64}$'),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 CHECK((action='CHECK_IN' AND reason IS NULL) OR (action='REVOKE' AND reason IN ('LOST_CODE','SECURITY','OPERATION_ERROR')))
);
ALTER TABLE issued_ticket ADD CONSTRAINT ticket_change_command FOREIGN KEY(changed_request_id) REFERENCES ticket_admission_command(request_id);
CREATE TABLE ticket_issuance_audit (
 order_id uuid PRIMARY KEY REFERENCES ticket_issuance(order_id),
 customer_id uuid NOT NULL REFERENCES customer_account(id),
 request_id uuid NOT NULL,
 quantity integer NOT NULL,
 payment_event_id uuid NOT NULL,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE ticket_admission_audit (
 request_id uuid PRIMARY KEY REFERENCES ticket_admission_command(request_id),
 actor_id uuid NOT NULL REFERENCES staff_account(id),
 ticket_id uuid NOT NULL REFERENCES issued_ticket(id),
 state varchar(16) NOT NULL CHECK(state IN ('CHECKED_IN','REVOKED')),
 version bigint NOT NULL CHECK(version=2),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE FUNCTION ticket_paid_proof(oid uuid) RETURNS boolean LANGUAGE sql STABLE AS $$
 SELECT EXISTS(SELECT 1 FROM ticket_order o JOIN customer_account c ON c.id=o.customer_id
 JOIN payment_attempt a ON a.order_id=o.id JOIN payment_event e ON e.order_id=o.id AND e.attempt_id=a.id
 JOIN payment_inventory_event p ON p.order_id=o.id AND p.event_id=e.event_id
 WHERE o.id=oid AND c.enabled AND o.status='PAID' AND a.status='SUCCEEDED' AND e.decision='SUCCEEDED'
 AND a.customer_id=o.customer_id AND a.amount_twd=o.total_twd AND p.ticket_id=o.ticket_id AND p.quantity=o.quantity)
$$;
CREATE FUNCTION tickets_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
 BEGIN RAISE EXCEPTION 'Immutable ticket evidence' USING ERRCODE='23514'; END $$;
CREATE TRIGGER immutable_issuance BEFORE UPDATE OR DELETE ON ticket_issuance FOR EACH ROW EXECUTE FUNCTION tickets_immutable();
CREATE TRIGGER immutable_admission BEFORE UPDATE OR DELETE ON ticket_admission_command FOR EACH ROW EXECUTE FUNCTION tickets_immutable();
CREATE TRIGGER immutable_issuance_audit BEFORE UPDATE OR DELETE ON ticket_issuance_audit FOR EACH ROW EXECUTE FUNCTION tickets_immutable();
CREATE TRIGGER immutable_admission_audit BEFORE UPDATE OR DELETE ON ticket_admission_audit FOR EACH ROW EXECUTE FUNCTION tickets_immutable();
CREATE FUNCTION ticket_issuance_guard() RETURNS trigger LANGUAGE plpgsql AS $$
 BEGIN
 PERFORM 1 FROM ticket_order WHERE id=NEW.order_id FOR SHARE;
 PERFORM 1 FROM payment_attempt WHERE order_id=NEW.order_id FOR SHARE;
 IF NOT ticket_paid_proof(NEW.order_id) OR NOT EXISTS(
 SELECT 1 FROM ticket_order o JOIN order_command c ON c.order_id=o.id
 JOIN payment_inventory_event p ON p.order_id=o.id
 WHERE o.id=NEW.order_id AND o.customer_id=NEW.customer_id AND o.quantity=NEW.quantity AND o.version=NEW.order_version
 AND c.customer_id=NEW.customer_id AND c.request_id=NEW.request_id AND c.action='ISSUE' AND p.event_id=NEW.payment_event_id)
 THEN RAISE EXCEPTION 'Issuance requires original paid conversion' USING ERRCODE='23514'; END IF;
 INSERT INTO ticket_issuance_audit(order_id,customer_id,request_id,quantity,payment_event_id)
 VALUES(NEW.order_id,NEW.customer_id,NEW.request_id,NEW.quantity,NEW.payment_event_id);
 RETURN NEW;
 END $$;
-- AFTER keeps the audit FK inside this same transaction.
CREATE TRIGGER issuance_proof AFTER INSERT ON ticket_issuance FOR EACH ROW EXECUTE FUNCTION ticket_issuance_guard();
CREATE FUNCTION issued_ticket_guard() RETURNS trigger LANGUAGE plpgsql AS $$
 BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Ticket cannot be deleted' USING ERRCODE='23514'; END IF;
 IF TG_OP='INSERT' THEN
 IF NEW.unit>(SELECT quantity FROM ticket_issuance WHERE order_id=NEW.order_id) OR NEW.state<>'ACTIVE' OR NEW.version<>1
 THEN RAISE EXCEPTION 'Invalid original ticket' USING ERRCODE='23514'; END IF;
 ELSE
 IF ROW(NEW.id,NEW.order_id,NEW.unit,NEW.code,NEW.created_at) IS DISTINCT FROM ROW(OLD.id,OLD.order_id,OLD.unit,OLD.code,OLD.created_at)
 OR OLD.state<>'ACTIVE' OR NOT EXISTS(
 SELECT 1 FROM ticket_admission_command a JOIN staff_account s ON s.id=a.actor_id
 JOIN ticket_order o ON o.id=OLD.order_id JOIN ticket_type t ON t.id=o.ticket_id
 WHERE a.request_id=NEW.changed_request_id AND a.ticket_id=OLD.id AND a.expected_version=OLD.version
 AND a.session_id=t.session_id AND s.enabled AND s.role='MANAGER'
 AND NEW.state=CASE a.action WHEN 'CHECK_IN' THEN 'CHECKED_IN' ELSE 'REVOKED' END
 AND (a.action='REVOKE' OR ticket_paid_proof(OLD.order_id)))
 THEN RAISE EXCEPTION 'Invalid ticket transition' USING ERRCODE='23514'; END IF;
 END IF;
 RETURN NEW;
 END $$;
CREATE TRIGGER ticket_identity BEFORE INSERT OR UPDATE OR DELETE ON issued_ticket FOR EACH ROW EXECUTE FUNCTION issued_ticket_guard();
CREATE FUNCTION ticket_admission_record() RETURNS trigger LANGUAGE plpgsql AS $$
 BEGIN
 INSERT INTO ticket_admission_audit(request_id,actor_id,ticket_id,state,version)
 SELECT NEW.changed_request_id,actor_id,NEW.id,NEW.state,NEW.version FROM ticket_admission_command WHERE request_id=NEW.changed_request_id;
 RETURN NEW;
 END $$;
CREATE TRIGGER admission_audit AFTER UPDATE ON issued_ticket FOR EACH ROW EXECUTE FUNCTION ticket_admission_record();
CREATE FUNCTION ticket_complete_issuance() RETURNS trigger LANGUAGE plpgsql AS $$
 DECLARE oid uuid; BEGIN
 oid:=NEW.order_id;
 IF (SELECT count(*) FROM issued_ticket WHERE order_id=oid)<>(SELECT quantity FROM ticket_issuance WHERE order_id=oid)
 OR NOT EXISTS(SELECT 1 FROM ticket_issuance i JOIN ticket_issuance_audit a USING(order_id)
 WHERE i.order_id=oid AND ROW(i.customer_id,i.request_id,i.quantity,i.payment_event_id)=ROW(a.customer_id,a.request_id,a.quantity,a.payment_event_id))
 THEN RAISE EXCEPTION 'Incomplete issuance' USING ERRCODE='23514'; END IF;
 RETURN NEW;
 END $$;
CREATE CONSTRAINT TRIGGER issuance_complete AFTER INSERT ON ticket_issuance DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION ticket_complete_issuance();
CREATE CONSTRAINT TRIGGER tickets_complete AFTER INSERT ON issued_ticket DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION ticket_complete_issuance();
CREATE FUNCTION ticket_complete_admission() RETURNS trigger LANGUAGE plpgsql AS $$
 BEGIN
 IF NOT EXISTS(SELECT 1 FROM issued_ticket t JOIN ticket_admission_audit a ON a.request_id=NEW.request_id
 WHERE t.id=NEW.ticket_id AND t.changed_request_id=NEW.request_id AND t.state=a.state AND t.version=a.version AND a.actor_id=NEW.actor_id AND a.ticket_id=NEW.ticket_id)
 THEN RAISE EXCEPTION 'Incomplete admission command' USING ERRCODE='23514'; END IF;
 RETURN NEW;
 END $$;
CREATE CONSTRAINT TRIGGER admission_complete AFTER INSERT ON ticket_admission_command DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION ticket_complete_admission();
