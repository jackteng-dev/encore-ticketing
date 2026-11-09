SELECT 'return_job_original_body',count(*) FROM stock_return_recovery_job j JOIN stock_return_staff_command c USING(request_id)
 WHERE ROW(j.actor_id,j.release_revision) IS DISTINCT FROM ROW(c.actor_id,c.release_revision);
SELECT 'return_job_done_acknowledged',count(*) FROM stock_return_recovery_job j
 WHERE j.state='DONE' AND NOT EXISTS(SELECT 1 FROM stock_return_staff_progress p JOIN stock_return_ack a USING(request_id)
 WHERE p.request_id=j.request_id AND p.state='APPLIED');
SELECT 'return_job_claim_budget',count(*) FROM stock_return_recovery_job j
 WHERE j.attempts<>(SELECT count(*) FROM stock_return_recovery_claim c WHERE c.request_id=j.request_id)
 OR j.max_attempts<>6 OR j.attempts>6;
SELECT 'return_job_exact_latest_audit',count(*) FROM stock_return_recovery_job j
 LEFT JOIN stock_return_recovery_audit a ON a.request_id=j.request_id AND a.version=j.version
 WHERE ROW(j.actor_id,j.release_revision,j.state,j.attempts,j.last_code)
 IS DISTINCT FROM ROW(a.actor_id,a.release_revision,a.state,a.attempts,a.last_code);
SELECT 'return_job_audit_sequence',count(*) FROM stock_return_recovery_job j
 WHERE j.version<>(SELECT count(*) FROM stock_return_recovery_audit a WHERE a.request_id=j.request_id);
SELECT 'return_job_live_claim_identity',count(*) FROM stock_return_recovery_job j
 WHERE j.state='LEASED' AND NOT EXISTS(SELECT 1 FROM stock_return_recovery_claim c WHERE c.request_id=j.request_id
 AND c.attempt=j.attempts AND c.token=j.lease_token AND c.lease_until=j.lease_until);
