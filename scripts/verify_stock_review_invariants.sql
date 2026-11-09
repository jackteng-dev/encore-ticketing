-- Read-only PG evidence checks. A retained REVIEW history is not a violation.
SELECT json_build_object(
 'missing_progress',(SELECT count(*) FROM stock_review_command c LEFT JOIN stock_review_progress p USING(request_id) WHERE p.request_id IS NULL),
 'review_history_mismatch',(SELECT count(*) FROM stock_review_command c JOIN stock_recovery_job j ON j.intent_id=c.intent_id AND j.kind=c.kind WHERE j.state<>'REVIEW' OR j.attempts<>c.expected_attempt),
 'decision_mismatch',(SELECT count(*) FROM stock_review_command c WHERE (c.kind='UNUSED' AND (c.expected_decision<>'ABANDON' OR EXISTS(SELECT 1 FROM stock_hold_acceptance WHERE intent_id=c.intent_id))) OR (c.kind='SETTLEMENT' AND NOT EXISTS(SELECT 1 FROM stock_hold_decision d WHERE d.intent_id=c.intent_id AND d.target=c.expected_decision))),
 'audit_version_gap',(SELECT count(*) FROM stock_review_progress p WHERE p.version<>(SELECT count(*) FROM stock_review_audit a WHERE a.request_id=p.request_id) OR p.version<>(SELECT max(a.version) FROM stock_review_audit a WHERE a.request_id=p.request_id)),
 'latest_audit_mismatch',(SELECT count(*) FROM stock_review_progress p JOIN stock_review_audit a ON a.request_id=p.request_id AND a.version=p.version WHERE ROW(p.state,p.resolution,p.failure,p.updated_at) IS DISTINCT FROM ROW(a.state,a.resolution,a.failure,a.created_at)),
 'unused_resolved_without_evidence',(SELECT count(*) FROM stock_review_command c JOIN stock_review_progress p USING(request_id) WHERE c.kind='UNUSED' AND p.state='RESOLVED' AND NOT EXISTS(SELECT 1 FROM stock_hold_progress h WHERE h.intent_id=c.intent_id AND h.state='ABORTED' AND h.resolution=p.resolution)),
 'settlement_resolved_without_evidence',(SELECT count(*) FROM stock_review_command c JOIN stock_review_progress p USING(request_id) WHERE c.kind='SETTLEMENT' AND p.state='RESOLVED' AND NOT EXISTS(SELECT 1 FROM stock_hold_ack a WHERE a.intent_id=c.intent_id AND a.resolution=p.resolution)),
 'resolved_with_failure',(SELECT count(*) FROM stock_review_progress WHERE state='RESOLVED' AND failure IS NOT NULL)
);
