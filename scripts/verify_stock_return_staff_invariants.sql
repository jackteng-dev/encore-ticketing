-- READ ONLY. Historical checkpoints do not assert current actor role or current READY.
BEGIN READ ONLY;
SELECT 'staff_return_domain_binding' AS check_name,count(*) AS violations FROM stock_return_staff_command c
JOIN stock_return_staff_progress p USING(request_id) LEFT JOIN stock_return_command d USING(request_id)
WHERE p.generation IS NOT NULL AND (d.request_id IS NULL OR
 ROW(c.ticket_id,c.order_id,c.release_revision,p.generation) IS DISTINCT FROM ROW(d.ticket_id,d.order_id,d.release_revision,d.generation));
SELECT 'staff_return_applied_proof',count(*) FROM stock_return_staff_progress p
WHERE p.state='APPLIED' AND NOT EXISTS(SELECT 1 FROM stock_return_ack a WHERE a.request_id=p.request_id);
SELECT 'staff_return_latest_audit',count(*) FROM stock_return_staff_progress p
WHERE NOT EXISTS(SELECT 1 FROM stock_return_staff_audit a WHERE a.request_id=p.request_id AND
 ROW(a.version,a.state,a.generation,a.failure,a.created_at) IS NOT DISTINCT FROM ROW(p.version,p.state,p.generation,p.failure,p.updated_at));
SELECT 'staff_return_consecutive_audits',count(*) FROM stock_return_staff_progress p
WHERE (SELECT count(*) FROM stock_return_staff_audit a WHERE a.request_id=p.request_id)<>p.version
 OR (SELECT min(version) FROM stock_return_staff_audit a WHERE a.request_id=p.request_id)<>1
 OR (SELECT max(version) FROM stock_return_staff_audit a WHERE a.request_id=p.request_id)<>p.version;
SELECT 'staff_return_original_release',count(*) FROM stock_return_staff_command c
WHERE NOT EXISTS(SELECT 1 FROM stock_revision_origin o WHERE o.ticket_id=c.ticket_id AND o.order_id=c.order_id
 AND o.to_revision=c.release_revision AND o.kind='ORDER_RELEASE' AND o.intent_id IS NOT NULL);
COMMIT;
