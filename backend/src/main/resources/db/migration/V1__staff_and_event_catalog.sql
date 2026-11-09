CREATE TABLE staff_account (
 id uuid PRIMARY KEY, username varchar(80) UNIQUE NOT NULL,
 password_hash varchar(100) NOT NULL, role varchar(20) NOT NULL CHECK(role IN ('MANAGER','VIEWER')),
 enabled boolean NOT NULL DEFAULT true
);
CREATE TABLE catalog_guard (id integer PRIMARY KEY CHECK(id=1));
INSERT INTO catalog_guard VALUES(1);
CREATE TABLE event (
 id uuid PRIMARY KEY, slug varchar(80) UNIQUE NOT NULL CHECK(slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
 title varchar(120) NOT NULL CHECK(length(btrim(title))>0), description varchar(3000) NOT NULL,
 venue varchar(160) NOT NULL CHECK(length(btrim(venue))>0),
 status varchar(20) NOT NULL DEFAULT 'DRAFT' CHECK(status IN ('DRAFT','PUBLISHED','ARCHIVED')),
 version bigint NOT NULL DEFAULT 1 CHECK(version>0), created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE event_session (
 id uuid PRIMARY KEY, event_id uuid NOT NULL REFERENCES event(id),
 name varchar(120) NOT NULL CHECK(length(btrim(name))>0), starts_at timestamptz NOT NULL,
 sales_start timestamptz NOT NULL, sales_end timestamptz NOT NULL,
 version bigint NOT NULL DEFAULT 1 CHECK(version>0),
 CHECK(sales_start<sales_end AND sales_end<=starts_at)
);
CREATE INDEX ON event_session(event_id,starts_at,id);
CREATE TABLE ticket_type (
 id uuid PRIMARY KEY, session_id uuid NOT NULL REFERENCES event_session(id),
 name varchar(100) NOT NULL CHECK(length(btrim(name))>0),
 price_twd integer NOT NULL CHECK(price_twd BETWEEN 1 AND 1000000),
 capacity integer NOT NULL CHECK(capacity BETWEEN 1 AND 100000),
 max_per_customer integer NOT NULL CHECK(max_per_customer BETWEEN 1 AND 10 AND max_per_customer<=capacity),
 version bigint NOT NULL DEFAULT 1 CHECK(version>0)
);
CREATE INDEX ON ticket_type(session_id,id);
CREATE TABLE catalog_command (
 request_id uuid PRIMARY KEY, actor_id uuid NOT NULL REFERENCES staff_account(id),
 action varchar(40) NOT NULL, resource_id uuid NOT NULL,
 fingerprint char(64) NOT NULL, result jsonb NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ON catalog_command(resource_id,created_at,request_id);
