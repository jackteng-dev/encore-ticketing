-- Intent identity/payload in V5 remains immutable. Delivery bookkeeping is separate.
CREATE TABLE outbox_publication (
 event_id uuid PRIMARY KEY REFERENCES purchase_outbox(id),
 attempts bigint NOT NULL DEFAULT 0 CHECK(attempts >= 0),
 next_attempt_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 lease_token uuid,
 lease_until timestamptz,
 published_at timestamptz,
 last_error varchar(32),
 CHECK ((lease_token IS NULL) = (lease_until IS NULL)),
 CHECK (published_at IS NULL OR (lease_token IS NULL AND last_error IS NULL))
);
INSERT INTO outbox_publication(event_id) SELECT id FROM purchase_outbox;
CREATE FUNCTION create_outbox_publication() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 INSERT INTO outbox_publication(event_id) VALUES(NEW.id);
 RETURN NEW;
END $$;
CREATE TRIGGER purchase_outbox_publication AFTER INSERT ON purchase_outbox
 FOR EACH ROW EXECUTE FUNCTION create_outbox_publication();
CREATE INDEX outbox_publication_pending ON outbox_publication(next_attempt_at,event_id)
 WHERE published_at IS NULL;
