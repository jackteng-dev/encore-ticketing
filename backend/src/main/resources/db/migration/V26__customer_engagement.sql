-- M4 收藏、開賣提醒與站內通知。只新增表格與索引，舊版程式在新 schema 上可照常運作。
CREATE TABLE customer_favorite (
 customer_id uuid NOT NULL REFERENCES customer_account(id),
 event_id uuid NOT NULL REFERENCES event(id),
 created_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(customer_id,event_id)
);
CREATE INDEX ON customer_favorite(customer_id,created_at DESC);

CREATE TABLE sale_reminder (
 customer_id uuid NOT NULL REFERENCES customer_account(id),
 event_id uuid NOT NULL REFERENCES event(id),
 due_at timestamptz NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(),
 notified_at timestamptz NULL,
 PRIMARY KEY(customer_id,event_id)
);
-- due_at：登記當下下一個尚未開賣場次的開賣時間；到這個時間就通知，不論之後售票是否已結束。
CREATE INDEX ON sale_reminder(customer_id,due_at) WHERE notified_at IS NULL;

-- 站內通知；dedupe_key 確保同一提醒在多個 API 同時讀取時只建立一次。
CREATE TABLE customer_notification (
 id uuid PRIMARY KEY,
 customer_id uuid NOT NULL REFERENCES customer_account(id),
 kind varchar(30) NOT NULL CHECK(kind IN ('SALE_STARTED')),
 title varchar(160) NOT NULL,
 body varchar(500) NOT NULL,
 link varchar(300) NOT NULL CHECK(link ~ '^/[^/]'),
 dedupe_key varchar(120) NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(),
 read_at timestamptz NULL,
 UNIQUE(customer_id,dedupe_key)
);
CREATE INDEX ON customer_notification(customer_id,created_at DESC,id);
CREATE INDEX ON customer_notification(customer_id) WHERE read_at IS NULL;
