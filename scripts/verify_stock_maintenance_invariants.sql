-- Read-only: historical READY may now have a DIRTY/currently newer projection.
SELECT json_build_object(
 'missing_progress',(SELECT count(*) FROM stock_maintenance_command c LEFT JOIN stock_maintenance_progress p USING(request_id) WHERE p.request_id IS NULL),
 'missing_audit',(SELECT count(*) FROM stock_maintenance_progress p WHERE NOT EXISTS(SELECT 1 FROM stock_maintenance_audit a WHERE a.request_id=p.request_id)),
 'audit_version_gap',(SELECT count(*) FROM stock_maintenance_progress p WHERE p.version<>(SELECT count(*) FROM stock_maintenance_audit a WHERE a.request_id=p.request_id) OR p.version<>(SELECT max(a.version) FROM stock_maintenance_audit a WHERE a.request_id=p.request_id)),
 'latest_audit_mismatch',(SELECT count(*) FROM stock_maintenance_progress p JOIN stock_maintenance_audit a ON a.request_id=p.request_id AND a.version=p.version WHERE ROW(p.state,p.generation,p.failure,p.updated_at) IS DISTINCT FROM ROW(a.state,a.generation,a.failure,a.created_at)),
 'rebuild_identity_mismatch',(SELECT count(*) FROM stock_maintenance_command c JOIN stock_maintenance_progress p USING(request_id) WHERE p.generation IS NOT NULL AND NOT EXISTS(SELECT 1 FROM stock_rebuild_command b WHERE b.request_id=c.request_id AND b.ticket_id=c.ticket_id AND b.source_revision=c.source_revision AND b.generation=p.generation)),
 'registered_with_domain_generation',(SELECT count(*) FROM stock_maintenance_progress p WHERE p.state='REGISTERED' AND (p.generation IS NOT NULL OR EXISTS(SELECT 1 FROM stock_rebuild_command b WHERE b.request_id=p.request_id))),
 'ready_with_failure',(SELECT count(*) FROM stock_maintenance_progress WHERE state='READY' AND failure IS NOT NULL),
 'checkpoint_without_original_command',(SELECT count(*) FROM stock_maintenance_audit a LEFT JOIN stock_maintenance_command c USING(request_id) WHERE c.request_id IS NULL)
);
