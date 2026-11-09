SELECT 'issuance_original_conversion',count(*) FROM ticket_issuance i
 JOIN ticket_order o ON o.id=i.order_id LEFT JOIN payment_inventory_event p ON p.order_id=o.id
 JOIN payment_event e ON e.event_id=i.payment_event_id
 WHERE ROW(i.customer_id,i.quantity,i.payment_event_id,o.ticket_id) IS DISTINCT FROM ROW(o.customer_id,o.quantity,p.event_id,p.ticket_id)
 OR e.order_id<>o.id OR e.decision<>'SUCCEEDED' OR i.order_version>o.version;
SELECT 'issuance_complete_units',count(*) FROM ticket_issuance i
 WHERE i.quantity<>(SELECT count(*) FROM issued_ticket t WHERE t.order_id=i.order_id)
 OR EXISTS(SELECT 1 FROM issued_ticket t WHERE t.order_id=i.order_id AND t.unit>i.quantity);
SELECT 'issuance_exact_audit',count(*) FROM ticket_issuance i LEFT JOIN ticket_issuance_audit a USING(order_id)
 WHERE ROW(i.customer_id,i.request_id,i.quantity,i.payment_event_id) IS DISTINCT FROM ROW(a.customer_id,a.request_id,a.quantity,a.payment_event_id);
SELECT 'issuance_original_command',count(*) FROM ticket_issuance i LEFT JOIN order_command c ON c.customer_id=i.customer_id AND c.request_id=i.request_id
 WHERE c.action IS DISTINCT FROM 'ISSUE' OR c.order_id IS DISTINCT FROM i.order_id;
SELECT 'admission_command_effect',count(*) FROM ticket_admission_command c LEFT JOIN issued_ticket t ON t.id=c.ticket_id
 LEFT JOIN ticket_admission_audit a ON a.request_id=c.request_id JOIN ticket_order o ON o.id=t.order_id JOIN ticket_type ty ON ty.id=o.ticket_id
 WHERE ROW(c.request_id,c.actor_id,c.ticket_id,2::bigint) IS DISTINCT FROM ROW(t.changed_request_id,a.actor_id,a.ticket_id,a.version)
 OR t.version<>a.version OR c.session_id<>ty.session_id OR t.state<>a.state
 OR t.state<>CASE c.action WHEN 'CHECK_IN' THEN 'CHECKED_IN' ELSE 'REVOKED' END;
SELECT 'terminal_ticket_audited',count(*) FROM issued_ticket t
 WHERE t.state<>'ACTIVE' AND NOT EXISTS(SELECT 1 FROM ticket_admission_command c JOIN ticket_admission_audit a USING(request_id)
 WHERE c.request_id=t.changed_request_id AND c.ticket_id=t.id AND a.ticket_id=t.id AND a.state=t.state AND a.version=t.version);
