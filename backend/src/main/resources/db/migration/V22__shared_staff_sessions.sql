-- Existing business records and V1-V21 are untouched; old memory sessions are not imported.
ALTER TABLE staff_account ADD COLUMN session_version bigint NOT NULL DEFAULT 1 CHECK(session_version>0);
CREATE TABLE staff_session (
 token_hash char(64) PRIMARY KEY CHECK(token_hash ~ '^[0-9a-f]{64}$'),
 staff_id uuid NOT NULL REFERENCES staff_account(id),
 session_version bigint NOT NULL CHECK(session_version>0),
 role_snapshot varchar(16) NOT NULL CHECK(role_snapshot IN ('MANAGER','VIEWER')),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 expires_at timestamptz NOT NULL,
 CHECK(isfinite(created_at) AND isfinite(expires_at) AND expires_at=created_at+interval '30 minutes')
);
CREATE INDEX staff_session_owner ON staff_session(staff_id,created_at,token_hash);
CREATE INDEX staff_session_expiry ON staff_session(expires_at);
CREATE FUNCTION revoke_staff_sessions() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.enabled IS DISTINCT FROM OLD.enabled OR NEW.role IS DISTINCT FROM OLD.role
 OR NEW.password_hash IS DISTINCT FROM OLD.password_hash OR NEW.username IS DISTINCT FROM OLD.username THEN
  NEW.session_version:=OLD.session_version+1;
 ELSIF NEW.session_version<OLD.session_version THEN
  RAISE EXCEPTION 'Staff session version cannot decrease' USING ERRCODE='23514';
 END IF;
 IF NEW.session_version<>OLD.session_version THEN DELETE FROM staff_session WHERE staff_id=OLD.id; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER staff_session_revocation BEFORE UPDATE ON staff_account FOR EACH ROW EXECUTE FUNCTION revoke_staff_sessions();
CREATE FUNCTION guard_staff_session() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 PERFORM 1 FROM staff_account WHERE id=NEW.staff_id AND enabled
 AND session_version=NEW.session_version AND role=NEW.role_snapshot FOR SHARE;
 IF NOT FOUND THEN RAISE EXCEPTION 'Session requires current enabled staff' USING ERRCODE='23514'; END IF;
 NEW.created_at:=clock_timestamp();NEW.expires_at:=NEW.created_at+interval '30 minutes';
 RETURN NEW;
END $$;
CREATE TRIGGER staff_session_guard BEFORE INSERT ON staff_session FOR EACH ROW EXECUTE FUNCTION guard_staff_session();
