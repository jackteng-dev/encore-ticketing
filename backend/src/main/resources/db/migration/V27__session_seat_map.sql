-- M5 場次區域座位圖：只記錄版面（票種對應的區塊位置），不影響票額、保票、付款或出票。
-- 只新增表格，舊版程式在新 schema 上可照常運作。
CREATE TABLE seat_map (
 session_id uuid PRIMARY KEY REFERENCES event_session(id),
 stage_label varchar(40) NOT NULL CHECK(length(btrim(stage_label))>0),
 version bigint NOT NULL DEFAULT 1 CHECK(version>0)
);

-- 座標以 0–100 的百分比表示，區塊不可超出畫布。
CREATE TABLE seat_zone (
 ticket_type_id uuid PRIMARY KEY REFERENCES ticket_type(id),
 session_id uuid NOT NULL REFERENCES seat_map(session_id),
 label varchar(40) NOT NULL CHECK(length(btrim(label))>0),
 x smallint NOT NULL CHECK(x BETWEEN 0 AND 99),
 y smallint NOT NULL CHECK(y BETWEEN 0 AND 99),
 w smallint NOT NULL CHECK(w BETWEEN 1 AND 100),
 h smallint NOT NULL CHECK(h BETWEEN 1 AND 100),
 CHECK(x+w<=100 AND y+h<=100)
);
CREATE INDEX ON seat_zone(session_id);
