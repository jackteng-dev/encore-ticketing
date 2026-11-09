-- Read-only historical evidence. Current READY/actor role/key health can change.
SELECT 'resolution_original_binding',count(*) FROM stock_return_resolution r
JOIN stock_return_staff_command c USING(request_id) JOIN stock_return_staff_progress p USING(request_id)
WHERE ROW(r.actor_id,r.ticket_id,r.order_id,r.return_version,r.release_revision)
 IS DISTINCT FROM ROW(c.actor_id,c.ticket_id,c.order_id,p.version,c.release_revision) OR p.state<>'BLOCKED';
SELECT 'resolution_original_release',count(*) FROM stock_return_resolution r
LEFT JOIN stock_revision_origin o ON o.ticket_id=r.ticket_id AND o.to_revision=r.release_revision
LEFT JOIN stock_revision_context x ON x.ticket_id=o.ticket_id AND x.to_revision=o.to_revision
WHERE o.kind IS DISTINCT FROM 'ORDER_RELEASE' OR o.order_id IS DISTINCT FROM r.order_id
 OR x.generation IS DISTINCT FROM r.source_generation;
SELECT 'resolution_snapshot_binding',count(*) FROM stock_return_resolution r
JOIN stock_rebuild_command b ON b.request_id=r.snapshot_request_id
WHERE ROW(r.ticket_id,r.snapshot_revision,r.generation,r.available)
 IS DISTINCT FROM ROW(b.ticket_id,b.source_revision,b.generation,b.available)
 OR r.generation<=r.source_generation OR r.snapshot_revision<r.release_revision;
SELECT 'resolution_publication_proof',count(*) FROM stock_return_resolution r
WHERE NOT EXISTS(SELECT 1 FROM stock_maintenance_audit m WHERE m.request_id=r.snapshot_request_id
 AND m.state='READY' AND m.generation=r.generation AND m.created_at=r.published_at AND r.proof_kind='MAINTENANCE')
AND NOT EXISTS(SELECT 1 FROM stock_return_ack a WHERE a.request_id=r.snapshot_request_id
 AND a.created_at=r.published_at AND r.proof_kind='RETURN_ACK');
SELECT 'resolution_exact_audit',count(*) FROM stock_return_resolution r
LEFT JOIN stock_return_resolution_audit a USING(request_id)
WHERE ROW(r.actor_id,r.return_version,r.snapshot_request_id,r.generation,r.snapshot_revision,r.state,r.created_at)
 IS DISTINCT FROM ROW(a.actor_id,a.return_version,a.snapshot_request_id,a.generation,a.snapshot_revision,a.state,a.created_at);
SELECT 'resolution_not_original_return_ack',count(*) FROM stock_return_resolution r
JOIN stock_return_ack a USING(request_id);
