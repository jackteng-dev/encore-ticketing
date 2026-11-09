-- C3 bounded design experiment ONLY. Never a production migration.
CREATE SCHEMA stock_multi_experiment;
CREATE TABLE stock_multi_experiment.cursor (
 ticket_id uuid NOT NULL REFERENCES ticket_type(id), generation bigint NOT NULL,
 root_revision bigint NOT NULL, baseline integer NOT NULL,
 revision bigint NOT NULL, available integer NOT NULL CHECK(available>=0), last_request uuid,
 PRIMARY KEY(ticket_id,generation),
 FOREIGN KEY(ticket_id,revision) REFERENCES stock_revision(ticket_id,revision)
);
-- The ORIGINAL V13 evidence is authoritative; message/Redis payloads are never provenance.
CREATE FUNCTION stock_multi_experiment.plan(t uuid,g bigint,f bigint) RETURNS jsonb LANGUAGE plpgsql AS $$
DECLARE x stock_multi_experiment.cursor%ROWTYPE; p stock_projection%ROWTYPE;
 b stock_revision%ROWTYPE; a stock_revision%ROWTYPE; origin stock_revision_origin%ROWTYPE;
 claims jsonb; n integer; q integer;
BEGIN
 SELECT * INTO STRICT x FROM stock_multi_experiment.cursor WHERE ticket_id=t AND generation=g;
 SELECT * INTO STRICT p FROM stock_projection WHERE ticket_id=t;
 IF p.generation<>g OR p.state<>'DIRTY' OR p.prepared_revision IS DISTINCT FROM x.root_revision
 THEN RAISE EXCEPTION 'GENERATION_FENCE'; END IF;
 IF p.source_revision-x.root_revision NOT BETWEEN 1 AND 16 OR f<x.root_revision OR f>=p.source_revision
 THEN RAISE EXCEPTION 'BOUNDED_RANGE'; END IF;
 -- Fence the whole bounded range before consuming even its first revision.
 SELECT count(*) INTO n FROM stock_revision_context c JOIN stock_revision_origin o USING(ticket_id,to_revision)
 JOIN stock_origin_audit u USING(ticket_id,to_revision)
 WHERE c.ticket_id=t AND c.to_revision>x.root_revision AND c.to_revision<=p.source_revision
 AND c.from_revision=c.to_revision-1 AND c.generation=g
 AND o.kind IN ('ORDER_HOLD','ORDER_RELEASE','PAYMENT') AND o.intent_id IS NOT NULL
 AND EXISTS(SELECT 1 FROM stock_hold_intent i JOIN stock_hold_decision d ON d.intent_id=i.id
  JOIN stock_hold_ack ack ON ack.intent_id=i.id
  WHERE i.id=o.intent_id AND i.ticket_id=t AND i.generation=g AND d.target='COMMIT'
   AND d.order_id=o.order_id AND ack.resolution='COMMITTED')
 AND (o.kind='ORDER_HOLD' OR EXISTS(SELECT 1 FROM stock_revision_origin h WHERE h.ticket_id=t
  AND h.intent_id=o.intent_id AND h.kind='ORDER_HOLD' AND h.to_revision>x.root_revision AND h.to_revision<c.to_revision));
 IF n<>p.source_revision-x.root_revision THEN RAISE EXCEPTION 'MISSING_OR_UNSUPPORTED_ORIGIN'; END IF;
 -- Every epoch intent must be an exact acknowledged original COMMIT, no active/absent/released claims.
 SELECT count(*) INTO n FROM stock_hold_intent WHERE ticket_id=t AND generation=g;
 IF n NOT BETWEEN 1 AND 8 THEN RAISE EXCEPTION 'CLAIM_BOUND'; END IF;
 SELECT jsonb_agg(jsonb_build_object('generation',i.generation,'requestId',i.id,'owner',i.customer_id,
  'quantity',i.quantity,'deadline',floor(extract(epoch FROM i.deadline_at)*1000)::bigint,
  'fingerprint',i.claim_fingerprint,'status','COMMITTED') ORDER BY i.id),sum(i.quantity) INTO claims,q
 FROM stock_hold_intent i JOIN stock_hold_decision d ON d.intent_id=i.id AND d.target='COMMIT'
 JOIN stock_hold_ack ack ON ack.intent_id=i.id AND ack.resolution='COMMITTED'
 JOIN stock_revision_origin h ON h.ticket_id=i.ticket_id AND h.intent_id=i.id AND h.kind='ORDER_HOLD'
  AND h.to_revision>x.root_revision AND h.to_revision<=p.source_revision
 WHERE i.ticket_id=t AND i.generation=g AND i.source_revision=x.root_revision;
 IF jsonb_array_length(claims) IS DISTINCT FROM n OR q>x.baseline THEN RAISE EXCEPTION 'ACTIVE_OR_UNACKNOWLEDGED_CLAIMS'; END IF;
 SELECT * INTO STRICT b FROM stock_revision WHERE ticket_id=t AND revision=f;
 SELECT * INTO STRICT a FROM stock_revision WHERE ticket_id=t AND revision=f+1;
 SELECT * INTO STRICT origin FROM stock_revision_origin WHERE ticket_id=t AND to_revision=f+1;
 IF ROW(a.capacity,a.ticket_version) IS DISTINCT FROM ROW(b.capacity,b.ticket_version)
 OR a.capacity<>x.baseline THEN RAISE EXCEPTION 'TERMS_FENCE'; END IF;
 RETURN jsonb_build_object('algorithm','c3-frozen-evidence-v1','ticket',t,'generation',g,
  'root',x.root_revision,'head',p.source_revision,'from',f,'to',f+1,'kind',origin.kind,
  'intent',origin.intent_id,'order',origin.order_id,'paymentEvent',origin.payment_event_id,
  'beforeAvailable',b.capacity-b.held-b.sold,'afterAvailable',a.capacity-a.held-a.sold,
  'baseline',x.baseline,'committed',q,'claims',claims);
END $$;
CREATE TABLE stock_multi_experiment.command (
 request_id uuid PRIMARY KEY, ticket_id uuid NOT NULL, generation bigint NOT NULL,
 from_revision bigint NOT NULL, to_revision bigint NOT NULL CHECK(to_revision=from_revision+1),
 plan jsonb NOT NULL, digest char(64) NOT NULL CHECK(digest ~ '^[0-9a-f]{64}$'),
 UNIQUE(ticket_id,generation,to_revision),
 FOREIGN KEY(ticket_id,generation) REFERENCES stock_multi_experiment.cursor(ticket_id,generation),
 FOREIGN KEY(ticket_id,to_revision) REFERENCES stock_revision(ticket_id,revision)
);
CREATE TABLE stock_multi_experiment.ack (
 request_id uuid PRIMARY KEY REFERENCES stock_multi_experiment.command(request_id),
 digest char(64) NOT NULL CHECK(digest ~ '^[0-9a-f]{64}$')
);
CREATE TABLE stock_multi_experiment.audit (
 request_id uuid NOT NULL REFERENCES stock_multi_experiment.command(request_id),
 phase varchar(12) NOT NULL CHECK(phase IN ('REGISTERED','APPLIED')),
 PRIMARY KEY(request_id,phase)
);
ALTER TABLE stock_multi_experiment.cursor ADD FOREIGN KEY(last_request) REFERENCES stock_multi_experiment.command(request_id);
CREATE FUNCTION stock_multi_experiment.validate_command() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.plan IS DISTINCT FROM stock_multi_experiment.plan(NEW.ticket_id,NEW.generation,NEW.from_revision)
 THEN RAISE EXCEPTION 'Command requires exact original committed provenance'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER validate_command BEFORE INSERT ON stock_multi_experiment.command FOR EACH ROW EXECUTE FUNCTION stock_multi_experiment.validate_command();
CREATE FUNCTION stock_multi_experiment.audit_phase() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 INSERT INTO stock_multi_experiment.audit VALUES(NEW.request_id,CASE WHEN TG_TABLE_NAME='command' THEN 'REGISTERED' ELSE 'APPLIED' END);
 RETURN NEW;
END $$;
CREATE TRIGGER registered AFTER INSERT ON stock_multi_experiment.command FOR EACH ROW EXECUTE FUNCTION stock_multi_experiment.audit_phase();
CREATE TRIGGER applied AFTER INSERT ON stock_multi_experiment.ack FOR EACH ROW EXECUTE FUNCTION stock_multi_experiment.audit_phase();
CREATE FUNCTION stock_multi_experiment.guard_cursor() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Cannot delete cursor'; END IF;
 IF TG_OP='INSERT' THEN
  IF NEW.revision<>NEW.root_revision OR NEW.available<>NEW.baseline OR NEW.last_request IS NOT NULL
  OR NOT EXISTS(SELECT 1 FROM stock_projection p JOIN stock_rebuild_command r ON r.request_id=p.rebuild_request_id
   JOIN stock_revision j ON j.ticket_id=p.ticket_id AND j.revision=p.source_revision
   WHERE p.ticket_id=NEW.ticket_id AND p.generation=NEW.generation AND p.state='READY'
    AND p.prepared_revision=NEW.root_revision AND p.source_revision=NEW.root_revision
    AND r.source_revision=NEW.root_revision AND j.held=0 AND j.sold=0 AND j.capacity=NEW.baseline)
  OR EXISTS(SELECT 1 FROM stock_hold_intent WHERE ticket_id=NEW.ticket_id AND generation=NEW.generation)
  THEN RAISE EXCEPTION 'Requires pristine verified READY seed'; END IF;
 ELSE
  IF ROW(NEW.ticket_id,NEW.generation,NEW.root_revision,NEW.baseline) IS DISTINCT FROM ROW(OLD.ticket_id,OLD.generation,OLD.root_revision,OLD.baseline)
  OR NEW.revision<>OLD.revision+1 OR NOT EXISTS(SELECT 1 FROM stock_multi_experiment.command c
   JOIN stock_multi_experiment.ack a USING(request_id) WHERE c.request_id=NEW.last_request
    AND c.ticket_id=NEW.ticket_id AND c.generation=NEW.generation AND c.from_revision=OLD.revision
    AND c.to_revision=NEW.revision AND a.digest=c.digest
    AND (c.plan->>'beforeAvailable')::integer=OLD.available AND (c.plan->>'afterAvailable')::integer=NEW.available)
  THEN RAISE EXCEPTION 'Requires exact next acknowledged command'; END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER guarded_cursor BEFORE INSERT OR UPDATE OR DELETE ON stock_multi_experiment.cursor FOR EACH ROW EXECUTE FUNCTION stock_multi_experiment.guard_cursor();
CREATE FUNCTION stock_multi_experiment.guard_ack() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM stock_multi_experiment.command c JOIN stock_multi_experiment.cursor x USING(ticket_id,generation)
  WHERE c.request_id=NEW.request_id AND c.digest=NEW.digest AND x.revision=c.to_revision AND x.last_request=c.request_id
   AND x.available=(c.plan->>'afterAvailable')::integer
   AND c.plan=stock_multi_experiment.plan(c.ticket_id,c.generation,c.from_revision))
 THEN RAISE EXCEPTION 'Ack must commit with exact checkpoint and domain proof'; END IF;
 RETURN NEW;
END $$;
CREATE CONSTRAINT TRIGGER guarded_ack AFTER INSERT ON stock_multi_experiment.ack DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION stock_multi_experiment.guard_ack();
CREATE TRIGGER immutable_command BEFORE UPDATE OR DELETE ON stock_multi_experiment.command FOR EACH ROW EXECUTE FUNCTION immutable_stock_record();
CREATE TRIGGER immutable_ack BEFORE UPDATE OR DELETE ON stock_multi_experiment.ack FOR EACH ROW EXECUTE FUNCTION immutable_stock_record();
CREATE TRIGGER immutable_audit BEFORE UPDATE OR DELETE ON stock_multi_experiment.audit FOR EACH ROW EXECUTE FUNCTION immutable_stock_record();
