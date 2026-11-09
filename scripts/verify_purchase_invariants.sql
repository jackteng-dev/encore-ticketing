-- Read-only reconciliation of M4A request/outbox state; combine with payment invariants.
SELECT json_build_object(
 'oneOutboxPerRequest',(SELECT count(*) FROM purchase_request p WHERE (SELECT count(*) FROM purchase_outbox o WHERE o.purchase_id=p.id)<>1),
 'outboxShape',(SELECT count(*) FROM purchase_outbox WHERE event_type<>'PURCHASE_REQUESTED' OR schema_version<>1),
 'submitReceipt',(SELECT count(*) FROM purchase_request p WHERE NOT EXISTS(SELECT 1 FROM purchase_command c WHERE c.purchase_id=p.id AND c.customer_id=p.customer_id AND c.request_id=p.request_id AND c.action='SUBMIT' AND c.fingerprint=p.fingerprint)),
 'commandOwnership',(SELECT count(*) FROM purchase_command c JOIN purchase_request p ON p.id=c.purchase_id WHERE c.customer_id<>p.customer_id),
 'pendingAcceptanceLimit',(SELECT count(*) FROM (SELECT customer_id FROM purchase_request WHERE status='PROCESSING' GROUP BY customer_id HAVING count(*)>5) x),
 'requestLifecycle',(SELECT count(*) FROM purchase_request WHERE (status='PROCESSING' AND (version<>1 OR closed_at IS NOT NULL OR order_id IS NOT NULL OR reason IS NOT NULL)) OR (status<>'PROCESSING' AND (version<>2 OR closed_at IS NULL))),
 'reservationProjection',(SELECT count(*) FROM purchase_request p JOIN ticket_order o ON o.id=p.order_id WHERE p.status<>'RESERVED' OR p.customer_id<>o.customer_id OR p.request_id<>o.request_id OR p.ticket_id<>o.ticket_id OR p.quantity<>o.quantity OR p.total_twd<>o.total_twd OR p.closed_at<>o.created_at OR o.created_at<p.created_at OR o.created_at>=p.deadline_at),
 'terminalWithoutReservation',(SELECT count(*) FROM purchase_request p WHERE p.status<>'RESERVED' AND (p.order_id IS NOT NULL OR EXISTS(SELECT 1 FROM order_command c WHERE c.customer_id=p.customer_id AND c.request_id=p.request_id))),
 'reservedPlaceCommand',(SELECT count(*) FROM purchase_request p WHERE p.status='RESERVED' AND NOT EXISTS(SELECT 1 FROM order_command c WHERE c.customer_id=p.customer_id AND c.request_id=p.request_id AND c.order_id=p.order_id AND c.action='PLACE')),
 'commandNamespace',(SELECT count(*) FROM purchase_command p JOIN order_command o USING(customer_id,request_id) JOIN purchase_request r ON r.id=p.purchase_id WHERE p.action<>'SUBMIT' OR r.status<>'RESERVED' OR o.action<>'PLACE' OR o.order_id<>r.order_id),
 'expiryDeadline',(SELECT count(*) FROM purchase_request WHERE status='EXPIRED' AND closed_at<deadline_at),
 'faultConstraintsRemaining',(SELECT count(*) FROM pg_constraint WHERE conname LIKE 'm4a_%fault')
);
