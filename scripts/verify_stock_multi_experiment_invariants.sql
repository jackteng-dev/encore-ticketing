-- READ ONLY. C3 test schema must already exist; never initializes or repairs it.
SELECT 'multi_command_origin' AS check_name,count(*) AS violations
FROM stock_multi_experiment.command c LEFT JOIN stock_revision_origin o ON o.ticket_id=c.ticket_id AND o.to_revision=c.to_revision
LEFT JOIN stock_revision_context x ON x.ticket_id=c.ticket_id AND x.to_revision=c.to_revision
WHERE o.intent_id IS NULL OR o.kind NOT IN ('ORDER_HOLD','ORDER_RELEASE','PAYMENT')
 OR ROW(o.kind,o.intent_id::text,o.order_id::text,o.payment_event_id::text) IS DISTINCT FROM
 ROW(c.plan->>'kind',c.plan->>'intent',c.plan->>'order',c.plan->>'paymentEvent')
 OR x.from_revision IS DISTINCT FROM c.from_revision OR x.generation IS DISTINCT FROM c.generation;
SELECT 'multi_command_stock_images' AS check_name,count(*) AS violations
FROM stock_multi_experiment.command c JOIN stock_revision b ON b.ticket_id=c.ticket_id AND b.revision=c.from_revision
JOIN stock_revision a ON a.ticket_id=c.ticket_id AND a.revision=c.to_revision
WHERE b.capacity-b.held-b.sold IS DISTINCT FROM (c.plan->>'beforeAvailable')::integer
 OR a.capacity-a.held-a.sold IS DISTINCT FROM (c.plan->>'afterAvailable')::integer
 OR ROW(a.capacity,a.ticket_version) IS DISTINCT FROM ROW(b.capacity,b.ticket_version);
SELECT 'multi_ack_digest' AS check_name,count(*) AS violations FROM stock_multi_experiment.ack a
JOIN stock_multi_experiment.command c USING(request_id) WHERE a.digest<>c.digest;
SELECT 'multi_cursor_contiguous' AS check_name,count(*) AS violations FROM stock_multi_experiment.cursor x
WHERE x.revision<>x.root_revision+(SELECT count(*) FROM stock_multi_experiment.command c
 JOIN stock_multi_experiment.ack a USING(request_id) WHERE c.ticket_id=x.ticket_id AND c.generation=x.generation)
 OR EXISTS(SELECT 1 FROM stock_multi_experiment.command c JOIN stock_multi_experiment.ack a USING(request_id)
  WHERE c.ticket_id=x.ticket_id AND c.generation=x.generation AND c.to_revision>x.revision);
SELECT 'multi_cursor_image_and_last' AS check_name,count(*) AS violations FROM stock_multi_experiment.cursor x
WHERE (x.revision=x.root_revision AND (x.available<>x.baseline OR x.last_request IS NOT NULL))
 OR (x.revision>x.root_revision AND NOT EXISTS(SELECT 1 FROM stock_multi_experiment.command c
  JOIN stock_multi_experiment.ack a USING(request_id) WHERE c.request_id=x.last_request AND c.ticket_id=x.ticket_id
   AND c.generation=x.generation AND c.to_revision=x.revision AND (c.plan->>'afterAvailable')::integer=x.available));
SELECT 'multi_phase_audits' AS check_name,count(*) AS violations FROM stock_multi_experiment.command c
WHERE NOT EXISTS(SELECT 1 FROM stock_multi_experiment.audit u WHERE u.request_id=c.request_id AND u.phase='REGISTERED')
 OR EXISTS(SELECT 1 FROM stock_multi_experiment.ack a WHERE a.request_id=c.request_id) IS DISTINCT FROM
 EXISTS(SELECT 1 FROM stock_multi_experiment.audit u WHERE u.request_id=c.request_id AND u.phase='APPLIED');
SELECT 'multi_pristine_root' AS check_name,count(*) AS violations FROM stock_multi_experiment.cursor x
JOIN stock_revision r ON r.ticket_id=x.ticket_id AND r.revision=x.root_revision
WHERE r.held<>0 OR r.sold<>0 OR r.capacity<>x.baseline;
SELECT 'multi_original_claims' AS check_name,count(*) AS violations FROM stock_multi_experiment.command c
CROSS JOIN LATERAL jsonb_array_elements(c.plan->'claims') claim
WHERE NOT EXISTS(SELECT 1 FROM stock_hold_intent i JOIN stock_hold_decision d ON d.intent_id=i.id
 JOIN stock_hold_ack a ON a.intent_id=i.id WHERE i.id::text=claim->>'requestId' AND i.ticket_id=c.ticket_id
  AND i.generation=c.generation AND i.customer_id::text=claim->>'owner' AND i.quantity=(claim->>'quantity')::integer
  AND i.claim_fingerprint=claim->>'fingerprint' AND floor(extract(epoch FROM i.deadline_at)*1000)::bigint=(claim->>'deadline')::bigint
  AND d.target='COMMIT' AND a.resolution='COMMITTED' AND claim->>'status'='COMMITTED');
