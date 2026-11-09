-- Operational observations only, not business/audit evidence. No backfill or job mutations.
CREATE TABLE runtime_heartbeat (
 boot_id uuid PRIMARY KEY,
 role varchar(12) NOT NULL CHECK(role IN ('API','WORKER','EMBEDDED')),
 first_seen timestamptz NOT NULL DEFAULT clock_timestamp(),
 last_seen timestamptz NOT NULL DEFAULT clock_timestamp(),
 consumer_running boolean NOT NULL DEFAULT false,
 CHECK(last_seen>=first_seen),
 CHECK(role<>'API' OR NOT consumer_running)
);
CREATE INDEX runtime_heartbeat_recent ON runtime_heartbeat(role,last_seen);
