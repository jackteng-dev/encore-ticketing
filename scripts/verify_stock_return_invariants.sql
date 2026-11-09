-- READ ONLY historical checks. Do not require every old APPLIED to equal current READY.
BEGIN READ ONLY;
SELECT 'return_original_source' AS check_name,count(*) AS violations FROM stock_return_command c
JOIN stock_revision_origin o ON o.ticket_id=c.ticket_id AND o.to_revision=c.release_revision
JOIN stock_revision_context x ON x.ticket_id=c.ticket_id AND x.to_revision=c.release_revision
WHERE o.kind<>'ORDER_RELEASE' OR o.order_id IS DISTINCT FROM c.order_id OR o.intent_id IS DISTINCT FROM c.intent_id
 OR x.generation<>c.source_generation OR c.generation<>c.source_generation+1;
SELECT 'return_original_commit_ack',count(*) FROM stock_return_command c
WHERE NOT EXISTS(SELECT 1 FROM stock_hold_decision d JOIN stock_hold_ack a USING(intent_id)
 WHERE d.intent_id=c.intent_id AND d.order_id=c.order_id AND d.target='COMMIT' AND a.resolution='COMMITTED');
SELECT 'return_snapshot_command',count(*) FROM stock_return_command c
JOIN stock_rebuild_command b USING(request_id) JOIN stock_revision r ON r.ticket_id=c.ticket_id AND r.revision=c.release_revision
WHERE ROW(b.ticket_id,b.generation,b.source_revision,b.available) IS DISTINCT FROM ROW(c.ticket_id,c.generation,c.release_revision,c.available)
 OR r.capacity-r.held-r.sold<>c.available;
SELECT 'return_phase_audits',count(*) FROM stock_return_command c
WHERE NOT EXISTS(SELECT 1 FROM stock_return_audit a WHERE a.request_id=c.request_id AND a.phase='REGISTERED')
 OR EXISTS(SELECT 1 FROM stock_return_ack a WHERE a.request_id=c.request_id) IS DISTINCT FROM
 EXISTS(SELECT 1 FROM stock_return_audit a WHERE a.request_id=c.request_id AND a.phase='APPLIED');
SELECT 'return_quantity_cause',count(*) FROM stock_return_command c
JOIN order_inventory_event e ON e.order_id=c.order_id AND e.phase='RELEASE' JOIN ticket_order o ON o.id=c.order_id
WHERE e.ticket_id<>c.ticket_id OR e.delta<>-o.quantity OR e.order_status NOT IN('CANCELLED','EXPIRED');
COMMIT;
