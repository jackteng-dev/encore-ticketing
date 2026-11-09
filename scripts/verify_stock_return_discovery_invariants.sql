-- Run against the intended database inside BEGIN READ ONLY; counts are violations, not mutations.
SELECT 'discovery_source_future_boundary',count(*)
 FROM stock_return_discovery_source s JOIN stock_return_discovery_policy p ON p.request_id=s.policy_id
 WHERE s.ticket_id<>p.ticket_id OR s.release_revision<=p.start_revision;
SELECT 'discovery_source_original_release',count(*) FROM stock_return_discovery_source s
 WHERE NOT EXISTS(SELECT 1 FROM stock_revision_origin o JOIN stock_revision_context x USING(ticket_id,to_revision)
 JOIN stock_hold_decision d ON d.intent_id=o.intent_id JOIN stock_hold_ack a ON a.intent_id=d.intent_id
 WHERE o.ticket_id=s.ticket_id AND o.to_revision=s.release_revision AND o.order_id=s.order_id
 AND o.kind='ORDER_RELEASE' AND d.order_id=s.order_id AND d.target='COMMIT' AND a.resolution='COMMITTED');
SELECT 'discovery_source_original_job',count(*) FROM stock_return_discovery_source s
 JOIN stock_return_discovery_policy p ON p.request_id=s.policy_id
 LEFT JOIN stock_return_staff_command c ON c.request_id=s.return_request_id
 LEFT JOIN stock_return_recovery_job j ON j.request_id=s.return_request_id
 WHERE s.disposition='ENROLLED' AND
 (ROW(c.actor_id,c.ticket_id,c.order_id,c.release_revision) IS DISTINCT FROM ROW(p.actor_id,s.ticket_id,s.order_id,s.release_revision)
 OR ROW(j.actor_id,j.release_revision) IS DISTINCT FROM ROW(p.actor_id,s.release_revision));
SELECT 'discovery_existing_return_proof',count(*) FROM stock_return_discovery_source s
 WHERE s.disposition='EXISTING_RETURN' AND NOT EXISTS(SELECT 1 FROM stock_return_staff_command c WHERE c.order_id=s.order_id)
 AND NOT EXISTS(SELECT 1 FROM stock_return_command c WHERE c.order_id=s.order_id);
SELECT 'discovery_finite_source_budget',count(*) FROM stock_return_discovery_policy p
 WHERE p.max_sources NOT BETWEEN 1 AND 100
 OR (SELECT count(*) FROM stock_return_discovery_source s WHERE s.policy_id=p.request_id)>p.max_sources
 OR (p.state='ACTIVE' AND (SELECT count(*) FROM stock_return_discovery_source s WHERE s.policy_id=p.request_id)=p.max_sources)
 OR (p.state='EXHAUSTED' AND (SELECT count(*) FROM stock_return_discovery_source s WHERE s.policy_id=p.request_id)<>p.max_sources);
SELECT 'discovery_exact_latest_audit',count(*) FROM stock_return_discovery_policy p
 LEFT JOIN stock_return_discovery_audit a ON a.policy_id=p.request_id AND a.version=p.version
 WHERE ROW(p.actor_id,p.state) IS DISTINCT FROM ROW(a.actor_id,a.state);
SELECT 'discovery_audit_sequence',count(*) FROM stock_return_discovery_policy p
 WHERE p.version<>(SELECT count(*) FROM stock_return_discovery_audit a WHERE a.policy_id=p.request_id)
 OR NOT EXISTS(SELECT 1 FROM stock_return_discovery_audit a WHERE a.policy_id=p.request_id AND a.version=1 AND a.state='ACTIVE' AND a.actor_id=p.actor_id);
SELECT 'discovery_stop_original_command',count(*) FROM stock_return_discovery_policy p
 WHERE p.state='STOPPED' AND NOT EXISTS(SELECT 1 FROM stock_return_discovery_stop c
 WHERE c.policy_id=p.request_id AND c.actor_id=p.actor_id AND c.expected_version=p.version-1);
SELECT 'discovery_stop_original_audit',count(*) FROM stock_return_discovery_stop c
 JOIN stock_return_discovery_policy p ON p.request_id=c.policy_id
 WHERE c.actor_id<>p.actor_id OR NOT EXISTS(SELECT 1 FROM stock_return_discovery_audit a
 WHERE a.policy_id=c.policy_id AND a.version=c.expected_version+1 AND a.actor_id=c.actor_id AND a.state='STOPPED');
