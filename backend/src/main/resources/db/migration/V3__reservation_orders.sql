ALTER TABLE ticket_type ADD COLUMN held integer NOT NULL DEFAULT 0;
ALTER TABLE ticket_type ADD COLUMN sold integer NOT NULL DEFAULT 0;
ALTER TABLE ticket_type ADD CONSTRAINT ticket_inventory CHECK (held>=0 AND sold>=0 AND held+sold<=capacity);
CREATE TABLE ticket_order (
 id uuid PRIMARY KEY,
 customer_id uuid NOT NULL REFERENCES customer_account(id),
 ticket_id uuid NOT NULL REFERENCES ticket_type(id),
 request_id uuid NOT NULL,
 event_title varchar(120) NOT NULL,
 session_name varchar(120) NOT NULL,
 starts_at timestamptz NOT NULL,
 ticket_name varchar(100) NOT NULL,
 price_twd integer NOT NULL CHECK(price_twd BETWEEN 1 AND 1000000),
 quantity integer NOT NULL CHECK(quantity BETWEEN 1 AND 10),
 total_twd integer NOT NULL CHECK(total_twd=price_twd*quantity),
 status varchar(24) NOT NULL CHECK(status IN ('PENDING_PAYMENT','CANCELLED','EXPIRED')),
 version bigint NOT NULL DEFAULT 1 CHECK(version>0),
 created_at timestamptz NOT NULL,
 expires_at timestamptz NOT NULL,
 sales_end timestamptz NOT NULL,
 closed_at timestamptz,
 UNIQUE(customer_id,request_id),
 UNIQUE(id,customer_id),
 UNIQUE(id,ticket_id),
 CHECK(created_at<expires_at AND expires_at<=sales_end AND sales_end<=starts_at),
 CHECK(expires_at<=created_at+interval '10 minutes'),
 CHECK(closed_at IS NULL OR (closed_at>=created_at AND (status<>'EXPIRED' OR closed_at>=expires_at))),
 CHECK((status='PENDING_PAYMENT' AND closed_at IS NULL AND version=1) OR
       (status IN ('CANCELLED','EXPIRED') AND closed_at IS NOT NULL AND version=2))
);
CREATE INDEX order_owner ON ticket_order(customer_id,created_at DESC,id);
CREATE INDEX order_customer_ticket ON ticket_order(customer_id,ticket_id) WHERE status='PENDING_PAYMENT';
CREATE INDEX order_due ON ticket_order(expires_at,id) WHERE status='PENDING_PAYMENT';
CREATE TABLE order_command (
 customer_id uuid NOT NULL REFERENCES customer_account(id),
 request_id uuid NOT NULL,
 action varchar(12) NOT NULL CHECK(action IN ('PLACE','CANCEL')),
 fingerprint char(64) NOT NULL CHECK(fingerprint ~ '^[0-9a-f]{64}$'),
 order_id uuid NOT NULL REFERENCES ticket_order(id),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(customer_id,request_id),
 FOREIGN KEY(order_id,customer_id) REFERENCES ticket_order(id,customer_id)
);
CREATE TABLE order_inventory_event (
 order_id uuid NOT NULL REFERENCES ticket_order(id),
 phase varchar(12) NOT NULL CHECK(phase IN ('HOLD','RELEASE')),
 ticket_id uuid NOT NULL REFERENCES ticket_type(id),
 actor varchar(12) NOT NULL CHECK(actor IN ('CUSTOMER','SYSTEM')),
 order_status varchar(24) NOT NULL CHECK(order_status IN ('PENDING_PAYMENT','CANCELLED','EXPIRED')),
 delta integer NOT NULL CHECK(delta BETWEEN -10 AND 10 AND delta<>0),
 held_before integer NOT NULL CHECK(held_before>=0),
 held_after integer NOT NULL CHECK(held_after>=0 AND held_after=held_before+delta),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(order_id,phase),
 FOREIGN KEY(order_id,ticket_id) REFERENCES ticket_order(id,ticket_id),
 CHECK((phase='HOLD' AND delta>0 AND order_status='PENDING_PAYMENT' AND actor='CUSTOMER') OR
       (phase='RELEASE' AND delta<0 AND order_status IN ('CANCELLED','EXPIRED')))
);
CREATE INDEX inventory_ticket ON order_inventory_event(ticket_id,created_at);
