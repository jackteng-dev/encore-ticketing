-- Read-only reconciliation for the authoritative PostgreSQL M2C schema.
SELECT json_build_object(
 'inventoryBounds',(SELECT count(*) FROM ticket_type WHERE held<0 OR sold<0 OR held+sold>capacity),
 'heldVsPending',(SELECT count(*) FROM ticket_type t WHERE held<>(SELECT COALESCE(sum(quantity),0) FROM ticket_order WHERE ticket_id=t.id AND status='PENDING_PAYMENT')),
 'soldVsPaid',(SELECT count(*) FROM ticket_type t WHERE sold<>(SELECT COALESCE(sum(quantity),0) FROM ticket_order WHERE ticket_id=t.id AND status='PAID')),
 'heldVsLedgers',(SELECT count(*) FROM ticket_type t WHERE held<>(SELECT COALESCE(sum(delta),0) FROM order_inventory_event WHERE ticket_id=t.id)-(SELECT COALESCE(sum(quantity),0) FROM payment_inventory_event WHERE ticket_id=t.id)),
 'soldVsLedger',(SELECT count(*) FROM ticket_type t WHERE sold<>(SELECT COALESCE(sum(quantity),0) FROM payment_inventory_event WHERE ticket_id=t.id)),
 'orderHoldRelease',(SELECT count(*) FROM ticket_order o WHERE (SELECT count(*) FROM order_inventory_event WHERE order_id=o.id AND phase='HOLD' AND delta=o.quantity)<>1 OR (SELECT count(*) FROM order_inventory_event WHERE order_id=o.id AND phase='RELEASE' AND delta=-o.quantity)<>CASE WHEN o.status IN ('CANCELLED','EXPIRED') THEN 1 ELSE 0 END),
 'orderPaidConversion',(SELECT count(*) FROM ticket_order o WHERE (SELECT count(*) FROM payment_inventory_event WHERE order_id=o.id AND quantity=o.quantity)<>CASE WHEN o.status='PAID' THEN 1 ELSE 0 END),
 'purchaseLimits',(SELECT count(*) FROM (SELECT o.customer_id,o.ticket_id FROM ticket_order o JOIN ticket_type t ON t.id=o.ticket_id WHERE o.status IN ('PENDING_PAYMENT','PAID') GROUP BY o.customer_id,o.ticket_id,t.max_per_customer HAVING sum(o.quantity)>t.max_per_customer) x),
 'pendingOrderLimit',(SELECT count(*) FROM (SELECT customer_id FROM ticket_order WHERE status='PENDING_PAYMENT' GROUP BY customer_id HAVING count(*)>5) x),
 'amountDeadlines',(SELECT count(*) FROM ticket_order WHERE total_twd<>price_twd*quantity OR expires_at>sales_end OR expires_at>created_at+interval '10 minutes' OR expires_at<=created_at),
 'orderLifecycle',(SELECT count(*) FROM ticket_order WHERE (status='PENDING_PAYMENT' AND closed_at IS NOT NULL) OR (status<>'PENDING_PAYMENT' AND closed_at IS NULL) OR (status='PAID' AND closed_at>=expires_at) OR (status='EXPIRED' AND closed_at<expires_at)),
 'commandOwnership',(SELECT count(*) FROM order_command c JOIN ticket_order o ON o.id=c.order_id WHERE c.customer_id<>o.customer_id),
 'placeCommand',(SELECT count(*) FROM ticket_order o WHERE NOT EXISTS(SELECT 1 FROM order_command c WHERE c.order_id=o.id AND c.customer_id=o.customer_id AND c.request_id=o.request_id AND c.action='PLACE')),
 'paymentSnapshotCommand',(SELECT count(*) FROM payment_attempt p JOIN ticket_order o ON o.id=p.order_id WHERE p.customer_id<>o.customer_id OR p.amount_twd<>o.total_twd OR NOT EXISTS(SELECT 1 FROM order_command c WHERE c.order_id=p.order_id AND c.customer_id=p.customer_id AND c.request_id=p.request_id AND c.action='PAY')),
 'providerEventAllocation',(SELECT count(*) FROM payment_event e WHERE (SELECT count(*) FROM payment_inventory_event i WHERE i.event_id=e.event_id AND i.order_id=e.order_id)<>CASE WHEN e.decision='SUCCEEDED' THEN 1 ELSE 0 END),
 'attemptState',(SELECT count(*) FROM payment_attempt p JOIN ticket_order o ON o.id=p.order_id WHERE (p.status='SUCCEEDED' AND o.status<>'PAID') OR (p.status IN ('PENDING','DECLINED') AND o.status='PAID') OR (p.status='REVIEW' AND p.reason='LATE_SUCCESS' AND o.status NOT IN ('CANCELLED','EXPIRED'))),
 'orderVersion',(SELECT count(*) FROM ticket_order o WHERE version<>1+(SELECT count(*) FROM payment_attempt WHERE order_id=o.id)+(SELECT count(*) FROM payment_event WHERE order_id=o.id)+(SELECT count(*) FROM order_inventory_event WHERE order_id=o.id AND phase='RELEASE')),
 'faultConstraintsRemaining',(SELECT count(*) FROM pg_constraint WHERE conname LIKE 'm2b_%fault' OR conname LIKE 'm2c_%fault')
);
