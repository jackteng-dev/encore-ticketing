-- READ ONLY: fences exclude all original command effects and keep exact audit.
SELECT 'issuance_fence_excludes_commands',count(*) FROM ticket_issuance_fence f
 WHERE EXISTS(SELECT 1 FROM order_command c WHERE c.customer_id=f.customer_id AND c.request_id=f.request_id)
 OR EXISTS(SELECT 1 FROM purchase_command c WHERE c.customer_id=f.customer_id AND c.request_id=f.request_id)
 OR EXISTS(SELECT 1 FROM stock_hold_intent c WHERE c.customer_id=f.customer_id AND c.request_id=f.request_id);
SELECT 'admission_fence_excludes_command',count(*) FROM ticket_admission_fence f
 WHERE EXISTS(SELECT 1 FROM ticket_admission_command c WHERE c.request_id=f.request_id);
SELECT 'issuance_fence_exact_audit',count(*) FROM ticket_issuance_fence f FULL JOIN ticket_issuance_fence_audit a USING(customer_id,request_id)
 WHERE ROW(f.order_id,f.expected_version,f.fingerprint,f.created_at) IS DISTINCT FROM ROW(a.order_id,a.expected_version,a.fingerprint,a.created_at);
SELECT 'admission_fence_exact_audit',count(*) FROM ticket_admission_fence f FULL JOIN ticket_admission_fence_audit a USING(request_id)
 WHERE ROW(f.actor_id,f.ticket_id,f.session_id,f.expected_version,f.action,f.reason,f.fingerprint,f.created_at)
 IS DISTINCT FROM ROW(a.actor_id,a.ticket_id,a.session_id,a.expected_version,a.action,a.reason,a.fingerprint,a.created_at);
