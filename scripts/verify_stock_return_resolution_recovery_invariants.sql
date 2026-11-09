-- Historical proofs; no assumption that current actor, snapshot or Redis is healthy.
SELECT 'recovery_fence_original',count(*) FROM stock_return_resolution_fence f
JOIN stock_return_staff_command c USING(request_id) JOIN stock_return_staff_progress p USING(request_id)
WHERE f.actor_id<>c.actor_id OR f.return_version<>p.version OR p.state<>'BLOCKED';
SELECT 'recovery_fence_not_applied',count(*) FROM stock_return_resolution_fence f
JOIN stock_return_resolution r ON r.request_id=f.request_id AND r.return_version=f.return_version
 AND r.snapshot_request_id=f.snapshot_request_id;
SELECT 'recovery_exact_fence_audit',count(*) FROM stock_return_resolution_fence f
LEFT JOIN stock_return_resolution_recovery_audit a ON a.request_id=f.request_id
 AND a.return_version=f.return_version AND a.snapshot_request_id=f.snapshot_request_id AND a.action='NOT_APPLIED'
WHERE ROW(f.actor_id,f.created_at) IS DISTINCT FROM ROW(a.actor_id,a.created_at) OR a.previous_snapshot_request_id IS NOT NULL;
SELECT 'recovery_exact_rebind_audit',count(*) FROM stock_return_resolution_rebind b
LEFT JOIN stock_return_resolution_recovery_audit a ON a.request_id=b.request_id
 AND a.return_version=b.return_version AND a.snapshot_request_id=b.snapshot_request_id AND a.action='REBOUND'
WHERE ROW(b.actor_id,b.previous_snapshot_request_id,b.created_at)
 IS DISTINCT FROM ROW(a.actor_id,a.previous_snapshot_request_id,a.created_at);
SELECT 'recovery_rebind_completed',count(*) FROM stock_return_resolution_rebind b
LEFT JOIN stock_return_resolution r ON r.request_id=b.request_id AND r.return_version=b.return_version
 AND r.snapshot_request_id=b.snapshot_request_id
WHERE r.actor_id IS DISTINCT FROM b.actor_id;
SELECT 'recovery_no_orphan_audit',count(*) FROM stock_return_resolution_recovery_audit a
WHERE (a.action='NOT_APPLIED' AND NOT EXISTS(SELECT 1 FROM stock_return_resolution_fence f
 WHERE ROW(f.request_id,f.return_version,f.snapshot_request_id,f.actor_id,f.created_at)
 =ROW(a.request_id,a.return_version,a.snapshot_request_id,a.actor_id,a.created_at)))
 OR (a.action='REBOUND' AND NOT EXISTS(SELECT 1 FROM stock_return_resolution_rebind b
 WHERE ROW(b.request_id,b.return_version,b.snapshot_request_id,b.actor_id,b.created_at,b.previous_snapshot_request_id)
 =ROW(a.request_id,a.return_version,a.snapshot_request_id,a.actor_id,a.created_at,a.previous_snapshot_request_id)));
