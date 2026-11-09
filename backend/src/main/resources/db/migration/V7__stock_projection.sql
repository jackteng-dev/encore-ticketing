-- PG is authoritative. These maintenance gates do not enable checkout pre-debits.
CREATE TABLE stock_projection (
 ticket_id uuid PRIMARY KEY REFERENCES ticket_type(id),
 source_revision bigint NOT NULL CHECK(source_revision>0),
 generation bigint NOT NULL DEFAULT 0 CHECK(generation BETWEEN 0 AND 1000000000),
 state varchar(12) NOT NULL DEFAULT 'DIRTY' CHECK(state IN ('CLOSED','READY','DIRTY')),
 rebuild_request_id uuid,
 prepared_revision bigint CHECK(prepared_revision>0),
 prepared_available integer CHECK(prepared_available BETWEEN 0 AND 1000000),
 CHECK((generation=0 AND rebuild_request_id IS NULL) OR (generation>0 AND rebuild_request_id IS NOT NULL)),
 CHECK((prepared_revision IS NULL)=(prepared_available IS NULL)),
 CHECK(state<>'READY' OR (generation>0 AND prepared_revision IS NOT NULL AND prepared_revision=source_revision AND prepared_available IS NOT NULL))
);
CREATE TABLE stock_revision (
 ticket_id uuid NOT NULL REFERENCES ticket_type(id),
 revision bigint NOT NULL CHECK(revision>0),
 capacity integer NOT NULL,
 held integer NOT NULL,
 sold integer NOT NULL,
 ticket_version bigint NOT NULL CHECK(ticket_version>0),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(ticket_id,revision),
 CHECK(capacity BETWEEN 1 AND 1000000 AND held>=0 AND sold>=0 AND held+sold<=capacity)
);
CREATE TABLE stock_rebuild_command (
 request_id uuid PRIMARY KEY,
 ticket_id uuid NOT NULL REFERENCES stock_projection(ticket_id),
 generation bigint NOT NULL CHECK(generation BETWEEN 1 AND 1000000000),
 source_revision bigint NOT NULL,
 available integer NOT NULL CHECK(available BETWEEN 0 AND 1000000),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(ticket_id,generation),
 UNIQUE(ticket_id,generation,request_id),
 FOREIGN KEY(ticket_id,source_revision) REFERENCES stock_revision(ticket_id,revision)
);
-- Snapshot existing V6 stock without changing a ticket, order, request or old audit row.
INSERT INTO stock_projection(ticket_id,source_revision) SELECT id,1 FROM ticket_type;
INSERT INTO stock_revision(ticket_id,revision,capacity,held,sold,ticket_version)
 SELECT id,1,capacity,held,sold,version FROM ticket_type;
ALTER TABLE stock_projection ADD CONSTRAINT projection_revision
 FOREIGN KEY(ticket_id,source_revision) REFERENCES stock_revision(ticket_id,revision) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE stock_projection ADD CONSTRAINT projection_command
 FOREIGN KEY(ticket_id,generation,rebuild_request_id) REFERENCES stock_rebuild_command(ticket_id,generation,request_id);

CREATE FUNCTION record_stock_revision() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE current_revision bigint;
BEGIN
 IF TG_OP='INSERT' THEN
  INSERT INTO stock_projection(ticket_id,source_revision) VALUES(NEW.id,1);
  current_revision:=1;
 ELSE
  IF ROW(OLD.capacity,OLD.held,OLD.sold,OLD.version)=ROW(NEW.capacity,NEW.held,NEW.sold,NEW.version) THEN RETURN NEW; END IF;
  UPDATE stock_projection SET source_revision=source_revision+1,state='DIRTY' WHERE ticket_id=NEW.id RETURNING source_revision INTO current_revision;
  IF NOT FOUND THEN RAISE EXCEPTION 'Missing stock projection; reconciliation required'; END IF;
 END IF;
 INSERT INTO stock_revision(ticket_id,revision,capacity,held,sold,ticket_version)
 VALUES(NEW.id,current_revision,NEW.capacity,NEW.held,NEW.sold,NEW.version);
 RETURN NEW;
END $$;
CREATE TRIGGER stock_revision_writer AFTER INSERT OR UPDATE ON ticket_type FOR EACH ROW EXECUTE FUNCTION record_stock_revision();
CREATE FUNCTION immutable_stock_record() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Stock journal and rebuild commands are immutable'; END $$;
CREATE TRIGGER immutable_stock_revision BEFORE UPDATE OR DELETE ON stock_revision FOR EACH ROW EXECUTE FUNCTION immutable_stock_record();
CREATE TRIGGER immutable_stock_rebuild BEFORE UPDATE OR DELETE ON stock_rebuild_command FOR EACH ROW EXECUTE FUNCTION immutable_stock_record();
