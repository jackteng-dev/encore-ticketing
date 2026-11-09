CREATE TABLE customer_account (
 id uuid PRIMARY KEY,
 handle varchar(40) NOT NULL UNIQUE CHECK (handle ~ '^[a-z0-9][a-z0-9_-]{2,39}$'),
 display_name varchar(80) NOT NULL CHECK (length(trim(display_name)) > 0),
 password_hash varchar(100) NOT NULL,
 enabled boolean NOT NULL DEFAULT true,
 session_version bigint NOT NULL DEFAULT 1 CHECK (session_version > 0),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE customer_session (
 token_hash char(64) PRIMARY KEY CHECK (token_hash ~ '^[0-9a-f]{64}$'),
 customer_id uuid NOT NULL REFERENCES customer_account(id),
 session_version bigint NOT NULL,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 expires_at timestamptz NOT NULL,
 CHECK (expires_at > created_at)
);
CREATE INDEX customer_session_owner ON customer_session(customer_id, created_at);
CREATE INDEX customer_session_expiry ON customer_session(expires_at);
-- A disable/re-enable or password change must never revive an old session.
CREATE FUNCTION revoke_customer_sessions() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.enabled IS DISTINCT FROM OLD.enabled OR NEW.password_hash IS DISTINCT FROM OLD.password_hash THEN
  NEW.session_version := OLD.session_version + 1;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER customer_session_revocation BEFORE UPDATE ON customer_account
 FOR EACH ROW EXECUTE FUNCTION revoke_customer_sessions();
