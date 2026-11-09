-- Internal operations only: no tokens, owners, request bodies or raw errors.
SELECT json_build_object(
 'waiting',(SELECT count(*) FROM stock_recovery_job WHERE state='WAITING'),
 'due',(SELECT count(*) FROM stock_recovery_job WHERE state='WAITING' AND next_attempt_at<=clock_timestamp()),
 'decided_unused_ready',(SELECT count(*) FROM stock_recovery_job j JOIN stock_hold_progress p ON p.intent_id=j.intent_id WHERE j.kind='UNUSED' AND j.state='WAITING' AND j.attempts=0 AND p.state='ABORTING'),
 'leased',(SELECT count(*) FROM stock_recovery_job WHERE state='LEASED'),
 'expired_leases',(SELECT count(*) FROM stock_recovery_job WHERE state='LEASED' AND lease_until<=clock_timestamp()),
 'done',(SELECT count(*) FROM stock_recovery_job WHERE state='DONE'),
 'review_history',(SELECT count(*) FROM stock_recovery_job WHERE state='REVIEW'),
 'review_unresolved',(SELECT count(*) FROM stock_recovery_job j JOIN stock_hold_progress p ON p.intent_id=j.intent_id WHERE j.state='REVIEW' AND p.state<>'ABORTED' AND NOT EXISTS(SELECT 1 FROM stock_hold_ack a WHERE a.intent_id=j.intent_id)),
 'unacked_decisions',(SELECT count(*) FROM stock_hold_decision d WHERE NOT EXISTS(SELECT 1 FROM stock_hold_ack a WHERE a.intent_id=d.intent_id)),
 'aborting_unused',(SELECT count(*) FROM stock_hold_progress WHERE state='ABORTING'),
 'oldest_due_seconds',(SELECT COALESCE(max(extract(epoch FROM clock_timestamp()-next_attempt_at)),0) FROM stock_recovery_job WHERE state IN ('WAITING','LEASED') AND next_attempt_at<=clock_timestamp())
);
