-- Read-only PG checks. Redis/PG claim and orphan reconciliation is not implemented yet.
SELECT 'missing_projection',count(*) FROM ticket_type t LEFT JOIN stock_projection p ON p.ticket_id=t.id WHERE p.ticket_id IS NULL
UNION ALL
SELECT 'revision_head_mismatch',count(*) FROM stock_projection p WHERE p.source_revision<>(SELECT max(r.revision) FROM stock_revision r WHERE r.ticket_id=p.ticket_id)
UNION ALL
SELECT 'journal_gap',count(*) FROM (SELECT ticket_id FROM stock_revision GROUP BY ticket_id HAVING min(revision)<>1 OR max(revision)<>count(*)) x
UNION ALL
SELECT 'current_stock_mismatch',count(*) FROM ticket_type t JOIN stock_projection p ON p.ticket_id=t.id JOIN stock_revision r ON r.ticket_id=p.ticket_id AND r.revision=p.source_revision WHERE ROW(t.capacity,t.held,t.sold,t.version)<>ROW(r.capacity,r.held,r.sold,r.ticket_version)
UNION ALL
SELECT 'generation_head_mismatch',count(*) FROM stock_projection p WHERE p.generation<>COALESCE((SELECT max(c.generation) FROM stock_rebuild_command c WHERE c.ticket_id=p.ticket_id),0)
UNION ALL
SELECT 'command_snapshot_mismatch',count(*) FROM stock_rebuild_command c JOIN stock_revision r ON r.ticket_id=c.ticket_id AND r.revision=c.source_revision WHERE c.available<>r.capacity-r.held-r.sold
UNION ALL
SELECT 'ready_checkpoint_mismatch',count(*) FROM stock_projection p JOIN stock_rebuild_command c ON c.request_id=p.rebuild_request_id WHERE p.state='READY' AND (p.prepared_revision IS DISTINCT FROM p.source_revision OR c.source_revision<>p.source_revision OR p.prepared_available IS DISTINCT FROM c.available);
