-- Read only. The cutover registry does not invent historical time proof.
SELECT 'window_command_exact_audit',count(*) FROM ticket_window_command c LEFT JOIN ticket_window_audit a USING(request_id)
 WHERE to_jsonb(c) IS DISTINCT FROM to_jsonb(a);
SELECT 'window_command_exact_policy',count(*) FROM ticket_window_command c LEFT JOIN ticket_window_policy p ON p.request_id=c.request_id
 WHERE (c.state='APPLIED' AND (p.request_id IS NULL OR p.session_id<>c.session_id OR p.version<>c.expected_version+1
  OR p.version<>c.policy_version OR p.opens_at<>c.opens_at_text::timestamptz OR p.closes_at<>c.closes_at_text::timestamptz OR p.created_at<>c.created_at))
 OR (c.state='FENCED' AND p.request_id IS NOT NULL);
SELECT 'window_policy_contiguous',count(*) FROM ticket_window_policy p
 WHERE p.version>1 AND NOT EXISTS(SELECT 1 FROM ticket_window_policy prior WHERE prior.session_id=p.session_id AND prior.version=p.version-1);
SELECT 'new_admission_time_proof',count(*) FROM ticket_admission_command c LEFT JOIN ticket_window_legacy l USING(request_id)
 LEFT JOIN ticket_window_proof p USING(request_id)
 WHERE c.action='CHECK_IN' AND l.request_id IS NULL AND p.request_id IS NULL;
SELECT 'exact_admission_time_proof',count(*) FROM ticket_window_proof p JOIN ticket_admission_command c USING(request_id)
 JOIN ticket_window_policy w ON w.session_id=p.session_id AND w.version=p.policy_version
 JOIN issued_ticket t ON t.id=p.ticket_id JOIN ticket_admission_audit a ON a.request_id=p.request_id
 WHERE c.action<>'CHECK_IN' OR c.ticket_id<>p.ticket_id OR c.session_id<>p.session_id
 OR t.state<>'CHECKED_IN' OR t.changed_request_id<>p.request_id OR t.changed_at<>p.checked_at
 OR a.state<>'CHECKED_IN' OR a.actor_id<>c.actor_id OR a.ticket_id<>p.ticket_id
 OR NOT ticket_window_contains(w.opens_at,w.closes_at,p.checked_at);
SELECT 'legacy_has_no_invented_time_proof',count(*) FROM ticket_window_legacy l JOIN ticket_admission_command c USING(request_id)
 WHERE c.action<>'CHECK_IN' OR EXISTS(SELECT 1 FROM ticket_window_proof p WHERE p.request_id=l.request_id);
