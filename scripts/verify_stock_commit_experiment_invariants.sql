-- Read-only, requires the explicitly created TEST-ONLY experiment schema.
-- Does not prove Redis availability, ledger integrity, Cluster or READY safety.
SELECT 'missing_registered_audit',count(*) FROM stock_commit_experiment.command c
 WHERE NOT EXISTS(SELECT 1 FROM stock_commit_experiment.audit a WHERE a.request_id=c.request_id AND phase='REGISTERED')
UNION ALL
SELECT 'command_identity_mismatch',count(*) FROM stock_commit_experiment.command c
 JOIN stock_hold_intent i ON i.id=c.intent_id JOIN stock_hold_decision d ON d.intent_id=i.id
 WHERE ROW(c.ticket_id,c.generation,c.from_revision,c.owner_id,c.quantity,c.fingerprint,c.order_id)
  IS DISTINCT FROM ROW(i.ticket_id,i.generation,i.source_revision,i.customer_id,i.quantity,i.claim_fingerprint,d.order_id)
  OR d.target<>'COMMIT' OR c.deadline_ms<>floor(extract(epoch FROM i.deadline_at)*1000)::bigint
UNION ALL
SELECT 'command_snapshot_mismatch',count(*) FROM stock_commit_experiment.command c
 JOIN stock_revision b ON b.ticket_id=c.ticket_id AND b.revision=c.from_revision
 JOIN stock_revision a ON a.ticket_id=c.ticket_id AND a.revision=c.to_revision
 WHERE c.to_revision<>c.from_revision+1 OR b.held<>0 OR b.sold<>0 OR c.baseline<>b.capacity
  OR ROW(a.capacity,a.held,a.sold,a.ticket_version) IS DISTINCT FROM ROW(b.capacity,b.held+c.quantity,b.sold,b.ticket_version)
UNION ALL
SELECT 'ack_cursor_mismatch',count(*) FROM stock_commit_experiment.ack a
 JOIN stock_commit_experiment.command c USING(request_id)
 LEFT JOIN stock_commit_experiment.cursor x ON x.ticket_id=c.ticket_id AND x.generation=c.generation
 WHERE x.revision IS DISTINCT FROM c.to_revision OR x.last_request IS DISTINCT FROM c.request_id
UNION ALL
SELECT 'ack_domain_evidence_mismatch',count(*) FROM stock_commit_experiment.ack a
 JOIN stock_commit_experiment.command c USING(request_id)
 WHERE NOT EXISTS(SELECT 1 FROM stock_hold_ack x JOIN stock_hold_decision d USING(intent_id)
  WHERE x.intent_id=c.intent_id AND x.resolution='COMMITTED' AND d.target='COMMIT' AND d.order_id=c.order_id)
UNION ALL
SELECT 'audit_state_mismatch',count(*) FROM stock_commit_experiment.command c
 WHERE (SELECT count(*) FROM stock_commit_experiment.audit a WHERE a.request_id=c.request_id)
   <>1+(SELECT count(*) FROM stock_commit_experiment.ack a WHERE a.request_id=c.request_id)
  OR EXISTS(SELECT 1 FROM stock_commit_experiment.audit a WHERE a.request_id=c.request_id AND a.phase='APPLIED'
    AND NOT EXISTS(SELECT 1 FROM stock_commit_experiment.ack x WHERE x.request_id=c.request_id))
UNION ALL
SELECT 'cursor_without_original_command',count(*) FROM stock_commit_experiment.cursor x
 WHERE x.last_request IS NOT NULL AND NOT EXISTS(SELECT 1 FROM stock_commit_experiment.command c
  JOIN stock_commit_experiment.ack a USING(request_id)
  WHERE c.request_id=x.last_request AND c.ticket_id=x.ticket_id AND c.generation=x.generation AND c.to_revision=x.revision)
UNION ALL
SELECT 'command_without_cursor',count(*) FROM stock_commit_experiment.command c
 WHERE NOT EXISTS(SELECT 1 FROM stock_commit_experiment.cursor x WHERE x.ticket_id=c.ticket_id AND x.generation=c.generation);
