-- Read-only PG checks. Redis receipt/counter reconciliation is a separate operation.
SELECT json_build_object(
 'missing_progress',(SELECT count(*) FROM stock_hold_intent i LEFT JOIN stock_hold_progress p ON p.intent_id=i.id WHERE p.intent_id IS NULL),
 'wrong_epoch_source',(SELECT count(*) FROM stock_hold_intent i LEFT JOIN stock_rebuild_command c ON c.ticket_id=i.ticket_id AND c.generation=i.generation AND c.source_revision=i.source_revision WHERE c.request_id IS NULL),
 'wrong_quote_revision',(SELECT count(*) FROM stock_hold_intent i JOIN stock_revision r ON r.ticket_id=i.ticket_id AND r.revision=i.source_revision WHERE i.ticket_version<>r.ticket_version),
 'invalid_deadline',(SELECT count(*) FROM stock_hold_intent WHERE deadline_at<=created_at OR deadline_at>created_at+interval '120 seconds' OR deadline_at<>date_trunc('milliseconds',deadline_at)),
 'legacy_command_collision',(SELECT count(*) FROM stock_hold_intent i WHERE NOT EXISTS(SELECT 1 FROM stock_hold_acceptance a WHERE a.intent_id=i.id) AND (EXISTS(SELECT 1 FROM order_command c WHERE c.customer_id=i.customer_id AND c.request_id=i.request_id) OR EXISTS(SELECT 1 FROM purchase_command c WHERE c.customer_id=i.customer_id AND c.request_id=i.request_id) OR EXISTS(SELECT 1 FROM ticket_order c WHERE c.customer_id=i.customer_id AND c.request_id=i.request_id) OR EXISTS(SELECT 1 FROM purchase_request c WHERE c.customer_id=i.customer_id AND c.request_id=i.request_id))),
 'unresolved_old_generation',(SELECT count(*) FROM stock_hold_intent i JOIN stock_hold_progress p ON p.intent_id=i.id JOIN stock_projection g ON g.ticket_id=i.ticket_id WHERE p.state<>'ABORTED' AND NOT EXISTS(SELECT 1 FROM stock_hold_ack a WHERE a.intent_id=i.id) AND i.generation<>g.generation),
 'abandonment_gate_open',(SELECT count(*) FROM stock_hold_intent i JOIN stock_hold_progress p ON p.intent_id=i.id JOIN stock_projection g ON g.ticket_id=i.ticket_id WHERE p.state IN ('ABORTING','ABORTED') AND i.generation=g.generation AND g.state<>'DIRTY'),
 'missing_abort_decision',(SELECT count(*) FROM stock_hold_progress WHERE state IN ('ABORTING','ABORTED') AND (abort_at IS NULL OR abort_reason IS DISTINCT FROM 'UNUSED_INTENT_ABANDONED')),
 'missing_resolution_evidence',(SELECT count(*) FROM stock_hold_progress WHERE state='ABORTED' AND (resolved_at IS NULL OR resolution IS NULL))
);
