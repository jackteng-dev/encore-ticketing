-- Read-only on own isolated database. Each count must be zero; no receipt implies business delivery.
SELECT 'onePublicationPerIntent' AS invariant,count(*) AS violations FROM purchase_outbox o LEFT JOIN outbox_publication d ON d.event_id=o.id WHERE d.event_id IS NULL
UNION ALL SELECT 'publicationOwnership',count(*) FROM outbox_publication d LEFT JOIN purchase_outbox o ON o.id=d.event_id WHERE o.id IS NULL
UNION ALL SELECT 'claimPair',count(*) FROM outbox_publication WHERE (lease_token IS NULL)<>(lease_until IS NULL)
UNION ALL SELECT 'publishedNotLeased',count(*) FROM outbox_publication WHERE published_at IS NOT NULL AND (lease_token IS NOT NULL OR lease_until IS NOT NULL OR last_error IS NOT NULL)
UNION ALL SELECT 'publishedAfterAttempt',count(*) FROM outbox_publication WHERE published_at IS NOT NULL AND attempts<1
UNION ALL SELECT 'errorShape',count(*) FROM outbox_publication WHERE last_error IS NOT NULL AND last_error NOT IN ('NACK','RETURNED','UNKNOWN')
UNION ALL SELECT 'deliveryFaultConstraintsRemaining',count(*) FROM pg_constraint WHERE conname LIKE 'm4b%fault';
