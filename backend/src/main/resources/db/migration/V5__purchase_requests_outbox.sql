-- Durable acceptance only. No ticket hold and no RabbitMQ delivery claim here.
CREATE TABLE purchase_request (
 id uuid PRIMARY KEY,
 customer_id uuid NOT NULL REFERENCES customer_account(id),
 request_id uuid NOT NULL,
 ticket_id uuid NOT NULL REFERENCES ticket_type(id),
 quantity integer NOT NULL CHECK(quantity BETWEEN 1 AND 10),
 ticket_version bigint NOT NULL CHECK(ticket_version>0),
 total_twd integer NOT NULL CHECK(total_twd BETWEEN 1 AND 10000000),
 session_version bigint NOT NULL CHECK(session_version>0),
 session_hash char(64) NOT NULL CHECK(session_hash ~ '^[0-9a-f]{64}$'),
 fingerprint char(64) NOT NULL CHECK(fingerprint ~ '^[0-9a-f]{64}$'),
 status varchar(16) NOT NULL DEFAULT 'PROCESSING' CHECK(status IN ('PROCESSING','RESERVED','REJECTED','CANCELLED','EXPIRED')),
 version bigint NOT NULL DEFAULT 1 CHECK(version>0),
 reason varchar(40),
 order_id uuid UNIQUE,
 created_at timestamptz NOT NULL,
 deadline_at timestamptz NOT NULL,
 closed_at timestamptz,
 UNIQUE(customer_id,request_id),
 UNIQUE(id,customer_id),
 FOREIGN KEY(order_id,customer_id) REFERENCES ticket_order(id,customer_id),
 FOREIGN KEY(order_id,ticket_id) REFERENCES ticket_order(id,ticket_id),
 CHECK(created_at<deadline_at AND deadline_at<=created_at+interval '120 seconds'),
 CHECK((status='PROCESSING' AND version=1 AND order_id IS NULL AND reason IS NULL AND closed_at IS NULL) OR
       (status='RESERVED' AND version=2 AND order_id IS NOT NULL AND reason IS NULL AND closed_at IS NOT NULL AND closed_at>=created_at AND closed_at<deadline_at) OR
       (status IN ('REJECTED','CANCELLED','EXPIRED') AND version=2 AND order_id IS NULL AND reason IS NOT NULL AND closed_at IS NOT NULL AND closed_at>=created_at)),
 CHECK(status<>'EXPIRED' OR closed_at>=deadline_at)
);
CREATE INDEX purchase_owner ON purchase_request(customer_id,created_at DESC,id);
CREATE INDEX purchase_due ON purchase_request(deadline_at,id) WHERE status='PROCESSING';
CREATE TABLE purchase_command (
 customer_id uuid NOT NULL REFERENCES customer_account(id),
 request_id uuid NOT NULL,
 action varchar(12) NOT NULL CHECK(action IN ('SUBMIT','CANCEL')),
 fingerprint char(64) NOT NULL CHECK(fingerprint ~ '^[0-9a-f]{64}$'),
 purchase_id uuid NOT NULL,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(customer_id,request_id),
 FOREIGN KEY(purchase_id,customer_id) REFERENCES purchase_request(id,customer_id)
);
-- Immutable intent. Publisher leases/confirms/retries are a subsequent milestone.
CREATE TABLE purchase_outbox (
 id uuid PRIMARY KEY,
 purchase_id uuid NOT NULL UNIQUE REFERENCES purchase_request(id),
 event_type varchar(32) NOT NULL CHECK(event_type='PURCHASE_REQUESTED'),
 schema_version integer NOT NULL CHECK(schema_version=1),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX purchase_outbox_created ON purchase_outbox(created_at,id);
