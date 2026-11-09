-- Read-only integrity checks; overdue/review counts are operational signals,
-- not evidence of incorrect stock. Redis verification remains a separate IO.
SELECT json_build_object(
 'missing_unused_job',(SELECT count(*) FROM stock_hold_intent i JOIN stock_hold_progress p ON p.intent_id=i.id WHERE p.state<>'ABORTED' AND NOT EXISTS(SELECT 1 FROM stock_hold_acceptance a WHERE a.intent_id=i.id) AND NOT EXISTS(SELECT 1 FROM stock_recovery_job j WHERE j.intent_id=i.id AND j.kind='UNUSED')),
 'missing_settlement_job',(SELECT count(*) FROM stock_hold_decision d WHERE NOT EXISTS(SELECT 1 FROM stock_hold_ack a WHERE a.intent_id=d.intent_id) AND NOT EXISTS(SELECT 1 FROM stock_recovery_job j WHERE j.intent_id=d.intent_id AND j.kind='SETTLEMENT')),
 'settlement_without_decision',(SELECT count(*) FROM stock_recovery_job j WHERE j.kind='SETTLEMENT' AND NOT EXISTS(SELECT 1 FROM stock_hold_decision d WHERE d.intent_id=j.intent_id)),
 'claim_count_mismatch',(SELECT count(*) FROM stock_recovery_job j WHERE j.attempts<>(SELECT count(*) FROM stock_recovery_claim c WHERE c.intent_id=j.intent_id AND c.kind=j.kind)),
 'lease_identity_mismatch',(SELECT count(*) FROM stock_recovery_job j WHERE j.state='LEASED' AND NOT EXISTS(SELECT 1 FROM stock_recovery_claim c WHERE c.intent_id=j.intent_id AND c.kind=j.kind AND c.attempt=j.attempts AND c.token=j.lease_token AND c.lease_until=j.lease_until)),
 'settlement_done_without_ack',(SELECT count(*) FROM stock_recovery_job j WHERE j.state='DONE' AND j.kind='SETTLEMENT' AND NOT EXISTS(SELECT 1 FROM stock_hold_ack a WHERE a.intent_id=j.intent_id)),
 'unused_done_without_evidence',(SELECT count(*) FROM stock_recovery_job j WHERE j.state='DONE' AND j.kind='UNUSED' AND NOT EXISTS(SELECT 1 FROM stock_hold_acceptance a WHERE a.intent_id=j.intent_id) AND NOT EXISTS(SELECT 1 FROM stock_hold_progress p WHERE p.intent_id=j.intent_id AND p.state='ABORTED')),
 'invalid_budget',(SELECT count(*) FROM stock_recovery_job WHERE attempts<0 OR attempts>max_attempts OR (state='WAITING' AND attempts>=max_attempts))
);
