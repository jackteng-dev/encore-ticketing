-- M3 活動分類、場館、演出者與活動檔案。
-- 只新增表格與索引，不修改既有 event／event_session／ticket_type，舊版程式在新 schema 上可照常運作。
CREATE TABLE category (
 id uuid PRIMARY KEY,
 slug varchar(40) UNIQUE NOT NULL CHECK(slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
 name varchar(40) NOT NULL CHECK(length(btrim(name))>0),
 sort_order integer NOT NULL DEFAULT 0
);
INSERT INTO category(id,slug,name,sort_order) VALUES
 ('6d1c0e2a-25a0-4c7e-9a10-000000000001','concert','演唱會',10),
 ('6d1c0e2a-25a0-4c7e-9a10-000000000002','theatre','音樂劇／戲劇',20),
 ('6d1c0e2a-25a0-4c7e-9a10-000000000003','sports','運動賽事',30),
 ('6d1c0e2a-25a0-4c7e-9a10-000000000004','exhibition','展覽',40),
 ('6d1c0e2a-25a0-4c7e-9a10-000000000005','family','親子',50),
 ('6d1c0e2a-25a0-4c7e-9a10-000000000006','other','其他',90);

CREATE TABLE venue (
 id uuid PRIMARY KEY,
 slug varchar(80) UNIQUE NOT NULL CHECK(slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
 name varchar(160) NOT NULL CHECK(length(btrim(name))>0),
 city varchar(40) NOT NULL DEFAULT '',
 address varchar(200) NOT NULL DEFAULT '',
 description varchar(3000) NOT NULL DEFAULT '',
 image_url varchar(500) NULL CHECK(image_url IS NULL OR image_url ~ '^(https://|/)[^[:space:]"<>]+$'),
 version bigint NOT NULL DEFAULT 1 CHECK(version>0),
 created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE performer (
 id uuid PRIMARY KEY,
 slug varchar(80) UNIQUE NOT NULL CHECK(slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
 name varchar(120) NOT NULL CHECK(length(btrim(name))>0),
 description varchar(3000) NOT NULL DEFAULT '',
 image_url varchar(500) NULL CHECK(image_url IS NULL OR image_url ~ '^(https://|/)[^[:space:]"<>]+$'),
 version bigint NOT NULL DEFAULT 1 CHECK(version>0),
 created_at timestamptz NOT NULL DEFAULT now()
);

-- 展示用資料（分類、場館連結、主視覺），發布後仍可調整，不影響票額、場次與售票條件。
CREATE TABLE event_profile (
 event_id uuid PRIMARY KEY REFERENCES event(id),
 category_id uuid NULL REFERENCES category(id),
 venue_id uuid NULL REFERENCES venue(id),
 image_url varchar(500) NULL CHECK(image_url IS NULL OR image_url ~ '^(https://|/)[^[:space:]"<>]+$'),
 version bigint NOT NULL DEFAULT 1 CHECK(version>0)
);
CREATE INDEX ON event_profile(category_id,event_id);
CREATE INDEX ON event_profile(venue_id,event_id);

CREATE TABLE event_performer (
 event_id uuid NOT NULL REFERENCES event(id),
 performer_id uuid NOT NULL REFERENCES performer(id),
 position smallint NOT NULL CHECK(position BETWEEN 0 AND 19),
 PRIMARY KEY(event_id,performer_id),
 UNIQUE(event_id,position)
);
CREATE INDEX ON event_performer(performer_id,event_id);
