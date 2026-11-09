-- Review an original BLOCKED return against an ALREADY published replacement
-- snapshot. No credit, new return command, generation, READY write or Redis IO.
CREATE VIEW stock_return_resolution_proof AS
SELECT c.request_id,c.actor_id,c.ticket_id,c.order_id,p.version return_version,
 c.release_revision,x.generation source_generation,b.request_id snapshot_request_id,
 b.source_revision snapshot_revision,b.generation,b.available,
 CASE WHEN m.request_id IS NOT NULL THEN 'MAINTENANCE' ELSE 'RETURN_ACK' END proof_kind,
 COALESCE(m.created_at,a.created_at) published_at,
 COALESCE(m.xmin,a.xmin) publication_xmin
FROM stock_return_staff_command c JOIN stock_return_staff_progress p USING(request_id)
JOIN stock_revision_origin o ON o.ticket_id=c.ticket_id AND o.to_revision=c.release_revision
JOIN stock_revision_context x ON x.ticket_id=o.ticket_id AND x.to_revision=o.to_revision
JOIN stock_origin_audit z ON z.ticket_id=o.ticket_id AND z.to_revision=o.to_revision
JOIN ticket_order t ON t.id=c.order_id
JOIN stock_hold_decision d ON d.intent_id=o.intent_id
JOIN stock_hold_ack h ON h.intent_id=d.intent_id
JOIN stock_projection g ON g.ticket_id=c.ticket_id
JOIN stock_rebuild_command b ON b.request_id=g.rebuild_request_id
LEFT JOIN stock_maintenance_audit m ON m.request_id=b.request_id AND m.state='READY' AND m.generation=b.generation
LEFT JOIN stock_return_ack a ON a.request_id=b.request_id
JOIN ticket_type ticket ON ticket.id=c.ticket_id
WHERE p.state='BLOCKED' AND o.kind='ORDER_RELEASE' AND o.order_id=c.order_id
 AND t.status IN ('CANCELLED','EXPIRED') AND d.target='COMMIT' AND d.order_id=c.order_id AND h.resolution='COMMITTED'
 AND g.state='READY' AND g.source_revision=b.source_revision AND g.prepared_revision=b.source_revision
 AND g.prepared_available=b.available AND g.generation=b.generation AND b.ticket_id=c.ticket_id
 AND b.source_revision>=c.release_revision AND b.generation>x.generation AND b.request_id<>c.request_id
 AND ticket.capacity-ticket.held-ticket.sold=b.available
 AND (m.request_id IS NOT NULL OR a.request_id IS NOT NULL)
 AND NOT EXISTS(SELECT 1 FROM stock_return_ack original WHERE original.request_id=c.request_id)
 AND NOT EXISTS(SELECT 1 FROM stock_hold_intent i JOIN stock_hold_progress ip ON ip.intent_id=i.id
  WHERE i.ticket_id=c.ticket_id AND ip.state<>'ABORTED' AND NOT EXISTS(SELECT 1 FROM stock_hold_ack ia WHERE ia.intent_id=i.id));
CREATE TABLE stock_return_resolution (
 request_id uuid PRIMARY KEY REFERENCES stock_return_staff_command(request_id),
 actor_id uuid NOT NULL REFERENCES staff_account(id),
 ticket_id uuid NOT NULL REFERENCES ticket_type(id),
 order_id uuid NOT NULL UNIQUE REFERENCES ticket_order(id),
 return_version bigint NOT NULL CHECK(return_version>0),
 release_revision bigint NOT NULL,
 source_generation bigint NOT NULL,
 snapshot_request_id uuid NOT NULL REFERENCES stock_rebuild_command(request_id),
 snapshot_revision bigint NOT NULL CHECK(snapshot_revision>=release_revision),
 generation bigint NOT NULL CHECK(generation>source_generation),
 available integer NOT NULL CHECK(available BETWEEN 0 AND 1000000),
 proof_kind varchar(12) NOT NULL CHECK(proof_kind IN ('MAINTENANCE','RETURN_ACK')),
 published_at timestamptz NOT NULL,
 state varchar(12) NOT NULL DEFAULT 'ABSORBED' CHECK(state='ABSORBED'),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 CHECK(snapshot_request_id<>request_id)
);
CREATE TABLE stock_return_resolution_audit (
 request_id uuid PRIMARY KEY REFERENCES stock_return_resolution(request_id),
 actor_id uuid NOT NULL,
 return_version bigint NOT NULL,
 snapshot_request_id uuid NOT NULL,
 generation bigint NOT NULL,
 snapshot_revision bigint NOT NULL,
 state varchar(12) NOT NULL CHECK(state='ABSORBED'),
 created_at timestamptz NOT NULL
);
CREATE FUNCTION validate_stock_return_resolution() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE original stock_return_staff_command%ROWTYPE; evidence stock_return_resolution_proof%ROWTYPE;
BEGIN
 SELECT * INTO STRICT original FROM stock_return_staff_command WHERE request_id=NEW.request_id;
 PERFORM 1 FROM staff_account WHERE id=original.actor_id AND enabled AND role='MANAGER' FOR SHARE;
 IF NOT FOUND OR NEW.actor_id<>original.actor_id THEN RAISE EXCEPTION 'Resolution requires original current manager'; END IF;
 PERFORM 1 FROM stock_return_staff_progress WHERE request_id=NEW.request_id FOR UPDATE;
 PERFORM 1 FROM ticket_type WHERE id=original.ticket_id FOR UPDATE;
 PERFORM 1 FROM stock_projection WHERE ticket_id=original.ticket_id FOR UPDATE;
 SELECT * INTO evidence FROM stock_return_resolution_proof WHERE request_id=NEW.request_id;
 IF NOT FOUND OR evidence.publication_xmin=pg_current_xact_id()::xid
 OR (SELECT xmin FROM stock_rebuild_command WHERE request_id=NEW.snapshot_request_id)=pg_current_xact_id()::xid
 OR ROW(NEW.actor_id,NEW.ticket_id,NEW.order_id,NEW.return_version,NEW.release_revision,NEW.source_generation,
  NEW.snapshot_request_id,NEW.snapshot_revision,NEW.generation,NEW.available,NEW.proof_kind,NEW.published_at)
 IS DISTINCT FROM ROW(evidence.actor_id,evidence.ticket_id,evidence.order_id,evidence.return_version,
  evidence.release_revision,evidence.source_generation,evidence.snapshot_request_id,evidence.snapshot_revision,
  evidence.generation,evidence.available,evidence.proof_kind,evidence.published_at)
 THEN RAISE EXCEPTION 'Resolution requires exact committed published snapshot and original blocked effect'; END IF;
 NEW.created_at:=clock_timestamp();RETURN NEW;
END $$;
CREATE TRIGGER validated_stock_return_resolution BEFORE INSERT ON stock_return_resolution
 FOR EACH ROW EXECUTE FUNCTION validate_stock_return_resolution();
CREATE FUNCTION audit_stock_return_resolution() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 INSERT INTO stock_return_resolution_audit VALUES(NEW.request_id,NEW.actor_id,NEW.return_version,
  NEW.snapshot_request_id,NEW.generation,NEW.snapshot_revision,NEW.state,NEW.created_at);
 RETURN NEW;
END $$;
CREATE TRIGGER stock_return_resolution_audit_writer AFTER INSERT ON stock_return_resolution
 FOR EACH ROW EXECUTE FUNCTION audit_stock_return_resolution();
CREATE TRIGGER immutable_stock_return_resolution BEFORE UPDATE OR DELETE ON stock_return_resolution
 FOR EACH ROW EXECUTE FUNCTION immutable_stock_record();
CREATE TRIGGER immutable_stock_return_resolution_audit BEFORE UPDATE OR DELETE ON stock_return_resolution_audit
 FOR EACH ROW EXECUTE FUNCTION immutable_stock_record();
