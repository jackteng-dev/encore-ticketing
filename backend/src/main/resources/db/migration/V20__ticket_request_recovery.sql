-- A missing receipt is only UNKNOWN. A committed immutable fence prevents the
-- original UUID ever being applied later. No ticket, payment or stock change.
CREATE TABLE ticket_issuance_fence (
 customer_id uuid NOT NULL REFERENCES customer_account(id),
 request_id uuid NOT NULL,
 order_id uuid NOT NULL,
 expected_version bigint NOT NULL CHECK(expected_version>0),
 fingerprint char(64) NOT NULL CHECK(fingerprint ~ '^[0-9a-f]{64}$'),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(customer_id,request_id),
 FOREIGN KEY(order_id,customer_id) REFERENCES ticket_order(id,customer_id)
);
CREATE TABLE ticket_admission_fence (
 request_id uuid PRIMARY KEY,
 actor_id uuid NOT NULL REFERENCES staff_account(id),
 ticket_id uuid NOT NULL REFERENCES issued_ticket(id),
 -- May be an invalid/wrong scene: fencing does not require a valid command.
 session_id uuid NOT NULL,
 expected_version bigint NOT NULL CHECK(expected_version>0),
 action varchar(12) NOT NULL CHECK(action IN ('CHECK_IN','REVOKE')),
 reason varchar(24),
 fingerprint char(64) NOT NULL CHECK(fingerprint ~ '^[0-9a-f]{64}$'),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 CHECK((action='CHECK_IN' AND reason IS NULL) OR (action='REVOKE' AND reason IN ('LOST_CODE','SECURITY','OPERATION_ERROR')))
);
CREATE TABLE ticket_issuance_fence_audit (LIKE ticket_issuance_fence INCLUDING ALL);
ALTER TABLE ticket_issuance_fence_audit ADD FOREIGN KEY(customer_id,request_id) REFERENCES ticket_issuance_fence(customer_id,request_id);
CREATE TABLE ticket_admission_fence_audit (LIKE ticket_admission_fence INCLUDING ALL);
ALTER TABLE ticket_admission_fence_audit ADD FOREIGN KEY(request_id) REFERENCES ticket_admission_fence(request_id);
CREATE FUNCTION ticket_admission_request_lock(r uuid) RETURNS void LANGUAGE sql VOLATILE AS $$
 SELECT pg_advisory_xact_lock(hashtextextended('ticket-admission:'||r::text,0))
$$;
CREATE FUNCTION ticket_customer_request_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 -- Same owner-first boundary as every customer API. Covers other namespaces
 -- and direct command SQL; the intent guard runs BEFORE any Redis pre-debit.
 PERFORM 1 FROM customer_account WHERE id=NEW.customer_id FOR UPDATE;
 IF TG_TABLE_NAME='ticket_issuance_fence' THEN
  IF NOT EXISTS(SELECT 1 FROM customer_account WHERE id=NEW.customer_id AND enabled)
  OR EXISTS(SELECT 1 FROM order_command WHERE customer_id=NEW.customer_id AND request_id=NEW.request_id)
  OR EXISTS(SELECT 1 FROM purchase_command WHERE customer_id=NEW.customer_id AND request_id=NEW.request_id)
  OR EXISTS(SELECT 1 FROM stock_hold_intent WHERE customer_id=NEW.customer_id AND request_id=NEW.request_id)
  THEN RAISE EXCEPTION 'Cannot fence an applied customer request' USING ERRCODE='23514'; END IF;
  NEW.created_at:=clock_timestamp();
 ELSE
  IF EXISTS(SELECT 1 FROM ticket_issuance_fence WHERE customer_id=NEW.customer_id AND request_id=NEW.request_id)
  THEN RAISE EXCEPTION 'Customer request permanently fenced' USING ERRCODE='23514'; END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER issuance_fence_proof BEFORE INSERT ON ticket_issuance_fence FOR EACH ROW EXECUTE FUNCTION ticket_customer_request_guard();
CREATE TRIGGER ticket_fenced_order_command BEFORE INSERT ON order_command FOR EACH ROW EXECUTE FUNCTION ticket_customer_request_guard();
CREATE TRIGGER ticket_fenced_purchase_command BEFORE INSERT ON purchase_command FOR EACH ROW EXECUTE FUNCTION ticket_customer_request_guard();
CREATE TRIGGER ticket_fenced_stock_intent BEFORE INSERT ON stock_hold_intent FOR EACH ROW EXECUTE FUNCTION ticket_customer_request_guard();
CREATE FUNCTION ticket_admission_request_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 PERFORM ticket_admission_request_lock(NEW.request_id);
 IF TG_TABLE_NAME='ticket_admission_fence' THEN
  PERFORM 1 FROM staff_account WHERE id=NEW.actor_id AND enabled AND role='MANAGER' FOR SHARE;
  IF NOT FOUND OR EXISTS(SELECT 1 FROM ticket_admission_command WHERE request_id=NEW.request_id)
  THEN RAISE EXCEPTION 'Cannot fence an applied admission request' USING ERRCODE='23514'; END IF;
  NEW.created_at:=clock_timestamp();
 ELSE
  IF EXISTS(SELECT 1 FROM ticket_admission_fence WHERE request_id=NEW.request_id)
  THEN RAISE EXCEPTION 'Admission request permanently fenced' USING ERRCODE='23514'; END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER admission_fence_proof BEFORE INSERT ON ticket_admission_fence FOR EACH ROW EXECUTE FUNCTION ticket_admission_request_guard();
CREATE TRIGGER ticket_fenced_admission BEFORE INSERT ON ticket_admission_command FOR EACH ROW EXECUTE FUNCTION ticket_admission_request_guard();
CREATE FUNCTION ticket_fence_audit() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_TABLE_NAME='ticket_issuance_fence' THEN
  INSERT INTO ticket_issuance_fence_audit SELECT NEW.*;
 ELSE
  INSERT INTO ticket_admission_fence_audit SELECT NEW.*;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER issuance_fence_audit AFTER INSERT ON ticket_issuance_fence FOR EACH ROW EXECUTE FUNCTION ticket_fence_audit();
CREATE TRIGGER admission_fence_audit AFTER INSERT ON ticket_admission_fence FOR EACH ROW EXECUTE FUNCTION ticket_fence_audit();
CREATE TRIGGER immutable_issuance_fence BEFORE UPDATE OR DELETE ON ticket_issuance_fence FOR EACH ROW EXECUTE FUNCTION tickets_immutable();
CREATE TRIGGER immutable_admission_fence BEFORE UPDATE OR DELETE ON ticket_admission_fence FOR EACH ROW EXECUTE FUNCTION tickets_immutable();
CREATE TRIGGER immutable_issuance_fence_audit BEFORE UPDATE OR DELETE ON ticket_issuance_fence_audit FOR EACH ROW EXECUTE FUNCTION tickets_immutable();
CREATE TRIGGER immutable_admission_fence_audit BEFORE UPDATE OR DELETE ON ticket_admission_fence_audit FOR EACH ROW EXECUTE FUNCTION tickets_immutable();
