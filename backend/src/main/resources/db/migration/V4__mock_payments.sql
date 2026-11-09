-- V1/V2/V3 remain immutable. These names are PostgreSQL's names for the V3 checks.
ALTER TABLE ticket_order DROP CONSTRAINT ticket_order_status_check;
ALTER TABLE ticket_order DROP CONSTRAINT ticket_order_check4;
ALTER TABLE ticket_order ADD CONSTRAINT order_status CHECK(status IN ('PENDING_PAYMENT','CANCELLED','EXPIRED','PAID'));
ALTER TABLE ticket_order ADD CONSTRAINT order_lifecycle CHECK(
 (status='PENDING_PAYMENT' AND closed_at IS NULL AND version>=1) OR
 (status IN ('CANCELLED','EXPIRED','PAID') AND closed_at IS NOT NULL AND version>=2));
ALTER TABLE ticket_order ADD CONSTRAINT paid_before_deadline CHECK(status<>'PAID' OR closed_at<expires_at);
ALTER TABLE order_command DROP CONSTRAINT order_command_action_check;
ALTER TABLE order_command ADD CONSTRAINT order_action CHECK(action IN ('PLACE','CANCEL','PAY','RECONCILE'));
DROP INDEX order_customer_ticket;
CREATE INDEX order_customer_ticket ON ticket_order(customer_id,ticket_id) WHERE status IN ('PENDING_PAYMENT','PAID');
ALTER TABLE ticket_order ADD CONSTRAINT order_payment_amount UNIQUE(id,total_twd);
ALTER TABLE order_command ADD CONSTRAINT command_order_owner_request UNIQUE(order_id,customer_id,request_id);
CREATE TABLE payment_attempt (
 id uuid PRIMARY KEY,
 order_id uuid NOT NULL UNIQUE REFERENCES ticket_order(id),
 customer_id uuid NOT NULL,
 request_id uuid NOT NULL,
 amount_twd integer NOT NULL CHECK(amount_twd BETWEEN 1 AND 10000000),
 status varchar(16) NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','SUCCEEDED','DECLINED','REVIEW')),
 reason varchar(32),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 processed_at timestamptz,
 UNIQUE(id,order_id),
 FOREIGN KEY(order_id,customer_id) REFERENCES ticket_order(id,customer_id),
 FOREIGN KEY(order_id,amount_twd) REFERENCES ticket_order(id,total_twd),
 FOREIGN KEY(order_id,customer_id,request_id) REFERENCES order_command(order_id,customer_id,request_id) DEFERRABLE INITIALLY DEFERRED,
 CHECK((status='PENDING' AND processed_at IS NULL AND reason IS NULL) OR
       (status IN ('SUCCEEDED','DECLINED') AND processed_at>=created_at AND reason IS NULL) OR
       (status='REVIEW' AND processed_at>=created_at AND reason IN ('LATE_SUCCESS','AMOUNT_MISMATCH','EXTRA_EVENT')))
);
-- A durable free simulator, not an externally trusted callback or a real charge.
CREATE TABLE mock_payment_receipt (
 event_id uuid PRIMARY KEY,
 attempt_id uuid NOT NULL REFERENCES payment_attempt(id),
 outcome varchar(16) NOT NULL CHECK(outcome IN ('SUCCESS','DECLINED')),
 amount integer NOT NULL CHECK(amount BETWEEN 1 AND 10000000),
 currency varchar(8) NOT NULL,
 UNIQUE(event_id,attempt_id),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX mock_receipt_attempt ON mock_payment_receipt(attempt_id,created_at,event_id);
CREATE TABLE payment_event (
 event_id uuid PRIMARY KEY,
 attempt_id uuid NOT NULL,
 order_id uuid NOT NULL REFERENCES ticket_order(id),
 fingerprint char(64) NOT NULL CHECK(fingerprint ~ '^[0-9a-f]{64}$'),
 decision varchar(16) NOT NULL CHECK(decision IN ('SUCCEEDED','DECLINED','REVIEW')),
 reason varchar(32),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(event_id,order_id),
 FOREIGN KEY(event_id,attempt_id) REFERENCES mock_payment_receipt(event_id,attempt_id),
 FOREIGN KEY(attempt_id,order_id) REFERENCES payment_attempt(id,order_id),
 CHECK((decision IN ('SUCCEEDED','DECLINED') AND reason IS NULL) OR
       (decision='REVIEW' AND reason IN ('LATE_SUCCESS','AMOUNT_MISMATCH','EXTRA_EVENT')))
);
CREATE UNIQUE INDEX payment_one_success ON payment_event(order_id) WHERE decision='SUCCEEDED';
-- Exactly one stock conversion per order, alongside the existing held ledger.
CREATE TABLE payment_inventory_event (
 order_id uuid PRIMARY KEY REFERENCES ticket_order(id),
 event_id uuid NOT NULL UNIQUE,
 ticket_id uuid NOT NULL REFERENCES ticket_type(id),
 quantity integer NOT NULL CHECK(quantity BETWEEN 1 AND 10),
 held_before integer NOT NULL,
 held_after integer NOT NULL CHECK(held_after>=0),
 sold_before integer NOT NULL CHECK(sold_before>=0),
 sold_after integer NOT NULL,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 FOREIGN KEY(event_id,order_id) REFERENCES payment_event(event_id,order_id) DEFERRABLE INITIALLY DEFERRED,
 FOREIGN KEY(order_id,ticket_id) REFERENCES ticket_order(id,ticket_id),
 CHECK(held_before-held_after=quantity AND sold_after-sold_before=quantity)
);
