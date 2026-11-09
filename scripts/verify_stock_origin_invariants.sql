-- Read-only PG checks. UNATTRIBUTED is an operational fence, not successful causality.
BEGIN TRANSACTION READ ONLY;
SELECT 'missing_new_revision_context' AS check_name,count(*) AS violations
FROM stock_revision r LEFT JOIN stock_origin_boundary b USING(ticket_id)
LEFT JOIN stock_revision_context c ON c.ticket_id=r.ticket_id AND c.to_revision=r.revision
WHERE r.revision>COALESCE(b.through_revision,0) AND c.ticket_id IS NULL;
SELECT 'context_origin_audit_coverage',count(*) FROM stock_revision_context c
LEFT JOIN stock_revision_origin o USING(ticket_id,to_revision)
LEFT JOIN stock_origin_audit a USING(ticket_id,to_revision)
WHERE o.ticket_id IS NULL OR a.ticket_id IS NULL;
SELECT 'new_context_image',count(*) FROM stock_revision_context c
JOIN stock_revision r ON r.ticket_id=c.ticket_id AND r.revision=c.to_revision
WHERE ROW(r.capacity,r.held,r.sold,r.ticket_version) IS DISTINCT FROM
 ROW((c.new_ticket->>'capacity')::int,(c.new_ticket->>'held')::int,(c.new_ticket->>'sold')::int,(c.new_ticket->>'version')::bigint);
SELECT 'previous_context_image',count(*) FROM stock_revision_context c
JOIN stock_revision r ON r.ticket_id=c.ticket_id AND r.revision=c.from_revision
WHERE c.from_revision<>c.to_revision-1 OR ROW(r.capacity,r.held,r.sold,r.ticket_version) IS DISTINCT FROM
 ROW((c.old_ticket->>'capacity')::int,(c.old_ticket->>'held')::int,(c.old_ticket->>'sold')::int,(c.old_ticket->>'version')::bigint);
SELECT 'no_historical_backfill',count(*) FROM stock_revision_context c
JOIN stock_origin_boundary b USING(ticket_id) WHERE c.to_revision<=b.through_revision;
SELECT 'order_event_exact_images',count(*) FROM stock_revision_origin o
JOIN stock_revision_context c USING(ticket_id,to_revision)
JOIN order_inventory_event e ON e.order_id=o.order_id AND e.phase=o.order_phase
JOIN ticket_order t ON t.id=e.order_id WHERE o.kind IN ('ORDER_HOLD','ORDER_RELEASE') AND
 (e.ticket_id<>o.ticket_id OR abs(e.delta)<>t.quantity OR ROW(e.held_before,e.held_after) IS DISTINCT FROM
 ROW((c.old_ticket->>'held')::int,(c.new_ticket->>'held')::int) OR
 (c.old_ticket-'held'-'sold') IS DISTINCT FROM (c.new_ticket-'held'-'sold'));
SELECT 'payment_event_exact_images',count(*) FROM stock_revision_origin o
JOIN stock_revision_context c USING(ticket_id,to_revision)
JOIN payment_inventory_event e ON e.event_id=o.payment_event_id
JOIN payment_event p ON p.event_id=e.event_id JOIN ticket_order t ON t.id=o.order_id
WHERE o.kind='PAYMENT' AND (e.ticket_id<>o.ticket_id OR e.order_id<>o.order_id OR p.decision<>'SUCCEEDED'
 OR e.quantity<>t.quantity OR ROW(e.held_before,e.held_after,e.sold_before,e.sold_after) IS DISTINCT FROM
 ROW((c.old_ticket->>'held')::int,(c.new_ticket->>'held')::int,(c.old_ticket->>'sold')::int,(c.new_ticket->>'sold')::int));
SELECT 'catalog_original_result',count(*) FROM stock_revision_origin o
JOIN stock_revision_context c USING(ticket_id,to_revision)
JOIN catalog_command cmd ON cmd.request_id=o.catalog_request_id
WHERE o.kind IN ('CATALOG_CREATE','CATALOG_UPDATE') AND (cmd.resource_id<>o.ticket_id OR
 cmd.action<>CASE WHEN o.kind='CATALOG_CREATE' THEN 'TICKET_CREATE' ELSE 'TICKET_UPDATE' END OR
 ROW(cmd.result->>'id',cmd.result->>'name',(cmd.result->>'priceTwd')::int,(cmd.result->>'capacity')::int,
 (cmd.result->>'maxPerCustomer')::int,(cmd.result->>'version')::bigint) IS DISTINCT FROM
 ROW(c.new_ticket->>'id',c.new_ticket->>'name',(c.new_ticket->>'price_twd')::int,(c.new_ticket->>'capacity')::int,
 (c.new_ticket->>'max_per_customer')::int,(c.new_ticket->>'version')::bigint));
SELECT 'accepted_original_commit',count(*) FROM stock_revision_origin o
JOIN stock_hold_intent i ON i.id=o.intent_id JOIN stock_hold_acceptance a ON a.intent_id=i.id
JOIN stock_hold_decision d ON d.intent_id=i.id JOIN ticket_order t ON t.id=o.order_id
JOIN purchase_request p ON p.id=a.purchase_id
WHERE d.target<>'COMMIT' OR d.order_id<>o.order_id OR i.ticket_id<>o.ticket_id OR i.customer_id<>t.customer_id
 OR i.request_id<>t.request_id OR i.quantity<>t.quantity OR p.order_id IS DISTINCT FROM o.order_id;
SELECT 'boundary_not_ahead_of_source',count(*) FROM stock_origin_boundary b
JOIN stock_projection p USING(ticket_id) WHERE b.through_revision>p.source_revision;
-- Operational counts: report separately and never reinterpret as violations=0.
SELECT 'unattributed_revisions' AS operational_signal,count(*) FROM stock_revision_origin WHERE kind='UNATTRIBUTED';
COMMIT;
